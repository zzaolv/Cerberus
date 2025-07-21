// daemon/cpp/main.cpp
#include "uds_server.h"
#include "state_manager.h"
#include "system_monitor.h"
#include "database_manager.h"
#include "action_executor.h"
#include <nlohmann/json.hpp>
#include <android/log.h>
#include <csignal>
#include <thread>
#include <chrono>
#include <memory>
#include <atomic>
#include <filesystem>
#include <mutex>
#include <unistd.h>

#define LOG_TAG "cerberusd_main_v7_hotfix"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

using json = nlohmann::json;
namespace fs = std::filesystem;

// --- Globals ---
static std::unique_ptr<UdsServer> g_server;
static std::shared_ptr<StateManager> g_state_manager;
static std::shared_ptr<SystemMonitor> g_sys_monitor;
static std::atomic<bool> g_is_running = true;
static std::atomic<int> g_probe_fd = -1;
static std::thread g_worker_thread;
std::atomic<bool> has_new_top_app_event = false;

// --- Forward Declarations ---
void broadcast_dashboard_update();
void notify_probe_of_config_change();

// --- UDS Callbacks ---
void handle_client_message(int client_fd, const std::string& message_str) {
    try {
        json msg = json::parse(message_str);
        std::string type = msg.value("type", "");
        if (type == "cmd.set_policy") {
            if (g_state_manager && g_state_manager->on_config_changed_from_ui(msg.at("payload"))) {
                notify_probe_of_config_change();
            }
            // Force an immediate update after config change, UI will see the result faster
            has_new_top_app_event = true;
        } else if (type == "query.refresh_dashboard") {
            broadcast_dashboard_update();
        } else if (type == "query.get_all_policies") {
            if (g_state_manager) {
                json payload = g_state_manager->get_full_config_for_ui();
                g_server->send_message(client_fd, json{{"type", "resp.all_policies"}, {"req_id", msg.value("req_id", "")}, {"payload", payload}}.dump());
            }
        } else if (type == "event.probe_hello") {
            g_probe_fd = client_fd;
            notify_probe_of_config_change();
        }
    } catch (const json::exception& e) { 
        LOGE("JSON Error: %s in msg: %s", e.what(), message_str.c_str()); 
    }
}

void handle_client_disconnect(int client_fd) {
    LOGI("Client fd %d has disconnected.", client_fd);
    if (client_fd == g_probe_fd.load()) {
        LOGW("Probe has disconnected! fd: %d", client_fd);
        g_probe_fd = -1;
    }
}

// --- Global Functions ---
void broadcast_dashboard_update() {
    if (g_server && g_server->has_clients() && g_state_manager) {
        LOGD("Broadcasting dashboard update...");
        json payload = g_state_manager->get_dashboard_payload();
        g_server->broadcast_message_except(json{{"type", "stream.dashboard_update"}, {"payload", payload}}.dump(), g_probe_fd.load());
    }
}

void notify_probe_of_config_change() {
    int probe_fd = g_probe_fd.load();
    if (g_server && probe_fd != -1 && g_state_manager) {
        LOGD("Sending config update to Probe fd %d.", probe_fd);
        json payload = g_state_manager->get_probe_config_payload();
        g_server->send_message(probe_fd, json{{"type", "stream.probe_config_update"}, {"payload", payload}}.dump());
    }
}

void signal_handler(int signum) {
    LOGW("Signal %d received, shutting down...", signum);
    g_is_running = false;
    if (g_server) g_server->stop();
}

// --- Worker Thread ---
void worker_thread_func() {
    LOGI("Worker thread started with LPE model.");
    has_new_top_app_event = true; // Force initial update
    
    while (g_is_running) {
        bool needs_broadcast = false;
        
        if (has_new_top_app_event.exchange(false)) {
            auto top_pids = g_sys_monitor->get_current_top_pids();
            if (g_state_manager->update_foreground_state(top_pids)) {
                needs_broadcast = true;
            }
        }

        if (g_state_manager->tick()) {
            needs_broadcast = true;
        }

        if (needs_broadcast) {
            broadcast_dashboard_update();
        }

        std::this_thread::sleep_for(std::chrono::seconds(1));
    }
    LOGI("Worker thread finished.");
}

// --- Main Entry ---
int main(int argc, char *argv[]) {
    signal(SIGTERM, signal_handler);
    signal(SIGINT, signal_handler);
    signal(SIGPIPE, SIG_IGN);

    const std::string DATA_DIR = "/data/adb/cerberus";
    const std::string DB_PATH = DATA_DIR + "/cerberus.db";
    LOGI("Project Cerberus Daemon v7 (Hotfix) starting... (PID: %d)", getpid());
    
    try {
        if (!fs::exists(DATA_DIR)) fs::create_directories(DATA_DIR);
    } catch(const fs::filesystem_error& e) {
        LOGE("Failed to create data dir: %s", e.what());
        return 1;
    }

    auto db_manager = std::make_shared<DatabaseManager>(DB_PATH);
    auto action_executor = std::make_shared<ActionExecutor>();
    g_sys_monitor = std::make_shared<SystemMonitor>();
    g_state_manager = std::make_shared<StateManager>(db_manager, g_sys_monitor, action_executor);
    
    g_sys_monitor->start_top_app_monitor();
    g_worker_thread = std::thread(worker_thread_func);
    
    g_server = std::make_unique<UdsServer>("cerberus_socket");
    g_server->set_message_handler(handle_client_message);
    g_server->set_disconnect_handler(handle_client_disconnect);
    g_server->run();
    
    g_is_running = false;
    if(g_worker_thread.joinable()) g_worker_thread.join();
    
    g_sys_monitor->stop_top_app_monitor();

    LOGI("Cerberus Daemon has shut down cleanly.");
    return 0;
}