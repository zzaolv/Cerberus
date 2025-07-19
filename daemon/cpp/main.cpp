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
#include <mutex> // [FIX] No longer need condition_variable

#define LOG_TAG "cerberusd_main_v11.0_refactored" // Version bump
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

using json = nlohmann::json;
namespace fs = std::filesystem;

const std::string SOCKET_NAME = "cerberus_socket";
const std::string DATA_DIR = "/data/adb/cerberus";
const std::string DB_PATH = DATA_DIR + "/cerberus.db";

std::atomic<bool> g_is_running = true;
std::unique_ptr<UdsServer> g_server;
std::shared_ptr<StateManager> g_state_manager;
std::atomic<int> g_probe_fd = -1;
std::mutex g_broadcast_mutex; // Mutex to protect broadcast calls

void broadcast_dashboard_update();
void notify_probe_of_config_change();

void handle_client_message(int client_fd, const std::string& message_str) {
    LOGD("[RECV MSG] From fd %d: %s", client_fd, message_str.c_str());
    try {
        json msg = json::parse(message_str);
        std::string type = msg.value("type", "");
        
        // [REFACTORED] Centralized logic for state changes and notifications
        bool state_changed = false;
        bool config_changed = false;

        if (type == "cmd.request_immediate_unfreeze") {
            bool success = g_state_manager ? g_state_manager->on_unfreeze_request(msg.at("payload")) : false;
            json response_msg = {
                {"v", msg.value("v", 1)},
                {"type", success ? "resp.unfreeze_complete" : "resp.unfreeze_failed"},
                {"req_id", msg.value("req_id", "")},
                {"payload", {}}
            };
            g_server->send_message(client_fd, response_msg.dump());
            if (success) {
                state_changed = true;
                config_changed = true;
            }
        } else if (type == "event.probe_hello") {
            LOGI("Probe hello received from fd %d. Registering as official Probe.", client_fd);
            g_probe_fd = client_fd;
            if (g_state_manager) g_state_manager->on_probe_hello(client_fd);
            state_changed = true;
            config_changed = true; // Send initial config
        } else if (type == "event.app_state_changed") {
            // [NEW] Handle detailed state changes from the Probe
            if (g_state_manager) {
                auto result = g_state_manager->on_app_state_changed_from_probe(msg.at("payload"));
                if (result.state_changed) state_changed = true;
                if (result.config_maybe_changed) config_changed = true;
            }
        } else if (type == "cmd.set_policy") {
            const auto& payload = msg.at("payload");
            AppConfig new_config;
            new_config.package_name = payload.value("package_name", "");
            new_config.user_id = payload.value("user_id", 0);
            new_config.policy = static_cast<AppPolicy>(payload.value("policy", 2));
            new_config.force_playback_exempt = payload.value("force_playback_exempt", false);
            new_config.force_network_exempt = payload.value("force_network_exempt", false);
            
            if (g_state_manager && g_state_manager->on_config_changed_from_ui(new_config)) {
                config_changed = true;
            }
            state_changed = true; // Policy change always requires a UI refresh
        } else if (type == "query.get_all_policies") {
            if (g_state_manager) {
                json response_payload = g_state_manager->get_full_config_for_ui();
                json response_msg = {
                    {"v", msg.value("v", 1)},
                    {"type", "resp.all_policies"},
                    {"req_id", msg.value("req_id", "")},
                    {"payload", response_payload}
                };
                 g_server->send_message(client_fd, response_msg.dump());
            }
        } else if (type == "query.refresh_dashboard") {
            state_changed = true;
        }

        // [REFACTORED] Centralized notification logic
        if (config_changed) {
            notify_probe_of_config_change();
        }
        if (state_changed) {
            broadcast_dashboard_update();
        }

    } catch (const json::exception& e) {
        LOGE("JSON Error: %s. Message: %s", e.what(), message_str.c_str());
    }
}

// [REFACTORED] A dedicated, thread-safe function for broadcasting updates
void broadcast_dashboard_update() {
    std::lock_guard<std::mutex> lock(g_broadcast_mutex);
    if (g_server && g_server->has_clients() && g_state_manager) {
        LOGD("Broadcasting dashboard update to UI clients...");
        json payload = g_state_manager->get_dashboard_payload();
        json message = {
            {"v", 11},
            {"type", "stream.dashboard_update"},
            {"payload", payload}
        };
        g_server->broadcast_message_except(message.dump(), g_probe_fd.load());
    }
}

void notify_probe_of_config_change() {
    int probe_fd = g_probe_fd.load();
    if (g_server && probe_fd != -1 && g_state_manager) {
        LOGD("[NOTIFY_PROBE] Sending config update to Probe fd %d.", probe_fd);
        json payload = g_state_manager->get_probe_config_payload();
        json message = {
            {"v", 11},
            {"type", "stream.probe_config_update"},
            {"payload", payload}
        };
        g_server->send_message(probe_fd, message.dump());
    }
}

void handle_client_disconnect(int client_fd) {
    if (client_fd == g_probe_fd.load()) {
        LOGW("Probe has disconnected (fd: %d).", client_fd);
        g_probe_fd = -1;
        if (g_state_manager) g_state_manager->on_probe_disconnect();
    } else {
        LOGI("UI client has disconnected (fd: %d).", client_fd);
    }
}

void signal_handler(int signum) {
    LOGI("Caught signal %d, initiating shutdown...", signum);
    g_is_running = false;
    if (g_server) g_server->stop();
}

// [REFACTORED] Worker thread is now a simple, lightweight maintenance loop
void worker_thread_func() {
    LOGI("Worker thread started. Role: Periodic Maintenance.");
    while (g_is_running) {
        std::this_thread::sleep_for(std::chrono::seconds(5)); // Reduced frequency
        if (!g_is_running) break;
        
        if (g_state_manager) {
            // tick() now only does lightweight process reconciliation and stat updates.
            // It no longer drives the main freeze logic.
            // If it reports a state change (e.g. process died), we update the UI.
            if (g_state_manager->tick()) {
                LOGD("State manager tick reported a change, broadcasting update.");
                broadcast_dashboard_update();
            }
        }
    }
    LOGI("Worker thread finished.");
}

int main(int argc, char *argv[]) {
    signal(SIGTERM, signal_handler);
    signal(SIGINT, signal_handler);
    LOGI("Project Cerberus Daemon v11.0 starting... (PID: %d)", getpid());
    try {
        if (!fs::exists(DATA_DIR)) {
            fs::create_directories(DATA_DIR);
            LOGI("Created data directory: %s", DATA_DIR.c_str());
        }
    } catch (const fs::filesystem_error& e) {
        LOGE("Failed to create data directory: %s. Exiting.", e.what());
        return 1;
    }
    auto db_manager = std::make_shared<DatabaseManager>(DB_PATH);
    auto action_executor = std::make_shared<ActionExecutor>();
    auto sys_monitor = std::make_shared<SystemMonitor>();
    g_state_manager = std::make_shared<StateManager>(db_manager, sys_monitor, action_executor);
    std::thread worker_thread(worker_thread_func);
    g_server = std::make_unique<UdsServer>(SOCKET_NAME);
    g_server->set_message_handler(handle_client_message);
    g_server->set_disconnect_handler(handle_client_disconnect);
    g_server->run();
    LOGI("Server loop has finished. Cleaning up...");
    g_is_running = false;
    if (worker_thread.joinable()) worker_thread.join();
    LOGI("Cerberus Daemon has shut down cleanly.");
    return 0;
}