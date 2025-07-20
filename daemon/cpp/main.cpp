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

#define LOG_TAG "cerberusd_main_v12.2_debug"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

using json = nlohmann::json;
namespace fs = std::filesystem;

// --- Global Variables ---
std::unique_ptr<UdsServer> g_server;
std::shared_ptr<StateManager> g_state_manager;
std::atomic<bool> g_is_running = true;
std::atomic<int> g_probe_fd = -1;
std::mutex g_broadcast_mutex;

// --- Forward Declarations ---
void broadcast_dashboard_update();
void notify_probe_of_config_change();

// --- Message Handler ---
void handle_client_message(int client_fd, const std::string& message_str) {
    LOGD("[RECV MSG] From fd %d: %s", client_fd, message_str.c_str());
    try {
        json msg = json::parse(message_str);
        std::string type = msg.value("type", "");
        
        // [关键调试点]
        if (type == "event.probe_hello") {
            LOGI("<<<<< PROBE HELLO DETECTED from fd %d >>>>>", client_fd);
            g_probe_fd = client_fd;
            notify_probe_of_config_change();
            return; 
        }

        bool state_changed = false;
        bool config_changed = false;

        if (type == "cmd.freeze_process") {
            if (g_state_manager) {
                state_changed = g_state_manager->on_freeze_request_from_probe(msg.at("payload"));
            }
        }
        else if (type == "cmd.unfreeze_process") {
            if (g_state_manager) {
                state_changed = g_state_manager->on_unfreeze_request_from_probe(msg.at("payload"));
            }
        }
        else if (type == "cmd.set_policy") {
            if (g_state_manager) {
                if (g_state_manager->on_config_changed_from_ui(msg.at("payload"))) {
                    config_changed = true;
                }
                state_changed = true;
            }
        }
        else if (type == "query.get_all_policies") {
            if (g_state_manager) {
                json response_payload = g_state_manager->get_full_config_for_ui();
                json response_msg = {{"type", "resp.all_policies"}, {"req_id", msg.value("req_id", "")}, {"payload", response_payload}};
                g_server->send_message(client_fd, response_msg.dump());
            }
        }
        else if (type == "query.refresh_dashboard") {
            state_changed = true;
        }

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

// --- Broadcast & Notification Functions ---
void broadcast_dashboard_update() {
    std::lock_guard<std::mutex> lock(g_broadcast_mutex);
    if (g_server && g_server->has_clients() && g_state_manager) {
        LOGD("Broadcasting dashboard update...");
        json payload = g_state_manager->get_dashboard_payload();
        json message = {{"type", "stream.dashboard_update"}, {"payload", payload}};
        // [关键修复] 确保有Probe时，绝对不会向Probe发送dashboard消息
        g_server->broadcast_message_except(message.dump(), g_probe_fd.load());
    }
}

void notify_probe_of_config_change() {
    int probe_fd = g_probe_fd.load();
    if (g_server && probe_fd != -1 && g_state_manager) {
        LOGD("[NOTIFY_PROBE] Sending config update to Probe fd %d.", probe_fd);
        json payload = g_state_manager->get_probe_config_payload();
        json message = {{"type", "stream.probe_config_update"}, {"payload", payload}};
        g_server->send_message(probe_fd, message.dump());
    }
}

// --- Server Lifecycle & Worker Thread ---
void handle_client_disconnect(int client_fd) {
    LOGI("Client fd %d has disconnected.", client_fd);
    if (client_fd == g_probe_fd.load()) {
        LOGW("Probe has disconnected! fd: %d", client_fd);
        g_probe_fd = -1;
    }
}

// [关键修复] 实现信号处理函数，解决无法Ctrl+C退出的问题
void signal_handler(int signum) {
    LOGW("Signal %d received, initiating shutdown...", signum);
    g_is_running = false;
    if (g_server) {
        g_server->stop();
    }
}

void worker_thread_func() {
    LOGI("Worker thread started.");
    while (g_is_running) {
        // [关键修复] 缩短轮询周期，提高UI响应性
        std::this_thread::sleep_for(std::chrono::seconds(3)); 
        if (!g_is_running) break;
        
        if (g_state_manager) {
            if (g_state_manager->tick()) {
                LOGD("StateManager tick reported a change, broadcasting update.");
                broadcast_dashboard_update();
            }
        }
    }
    LOGI("Worker thread finished.");
}


// --- Main Entry Point ---
int main(int argc, char *argv[]) {
    // [关键修复] 注册所有必要的信号
    signal(SIGTERM, signal_handler);
    signal(SIGINT, signal_handler);
    signal(SIGPIPE, SIG_IGN); // 忽略此信号，防止写入断开的socket导致崩溃

    const std::string DATA_DIR = "/data/adb/cerberus";
    const std::string DB_PATH = DATA_DIR + "/cerberus.db";

    LOGI("Project Cerberus Daemon v12.1 (Fix) starting... (PID: %d)", getpid());
    
    // ... (rest of main is fine)
    try {
        if (!fs::exists(DATA_DIR)) fs::create_directories(DATA_DIR);
    } catch (const fs::filesystem_error& e) {
        LOGE("Failed to create data directory: %s. Exiting.", e.what());
        return 1;
    }

    auto db_manager = std::make_shared<DatabaseManager>(DB_PATH);
    auto action_executor = std::make_shared<ActionExecutor>();
    auto sys_monitor = std::make_shared<SystemMonitor>();
    g_state_manager = std::make_shared<StateManager>(db_manager, sys_monitor, action_executor);
    
    std::thread worker_thread(worker_thread_func);
    
    g_server = std::make_unique<UdsServer>("cerberus_socket");
    g_server->set_message_handler(handle_client_message);
    g_server->set_disconnect_handler(handle_client_disconnect);
    g_server->run();
    
    g_is_running = false;
    if (worker_thread.joinable()) worker_thread.join();
    
    LOGI("Cerberus Daemon has shut down cleanly.");
    return 0;
}