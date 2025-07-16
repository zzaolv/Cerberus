// daemon/cpp/main.cpp
#include "uds_server.h"
#include "state_manager.h"
#include "system_monitor.h"
#include "database_manager.h"
#include "action_executor.h"
#include "process_monitor.h"
#include <nlohmann/json.hpp>
#include <android/log.h>
#include <csignal>
#include <thread>
#include <chrono>
#include <memory>
#include <atomic>
#include <filesystem>

#define LOG_TAG "cerberusd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using json = nlohmann::json;

const std::string SOCKET_NAME = "cerberus_socket";
const std::string DATA_DIR = "/data/adb/cerberus";
const std::string DB_PATH = DATA_DIR + "/cerberus.db";

std::unique_ptr<UdsServer> g_server;
std::shared_ptr<StateManager> g_state_manager;
std::unique_ptr<ProcessMonitor> g_proc_monitor;
std::atomic<bool> g_is_running = true;

// 【修复】提供完整的 handle_ui_message 实现
void handle_ui_message(const std::string& message_str) {
    LOGI("Received UI message: %s", message_str.c_str());
    try {
        json msg = json::parse(message_str);
        std::string type = msg.value("type", "");

        if (type.rfind("cmd.", 0) == 0) {
            // Placeholder for command handling
            LOGI("Handling command: %s", type.c_str());
        } else if (type.rfind("query.", 0) == 0) {
            // Placeholder for query handling
            LOGI("Handling query: %s", type.c_str());
        }
    } catch (const json::parse_error& e) {
        LOGW("JSON parse error from UI: %s", e.what());
    }
}

void signal_handler(int signum) {
    LOGI("Caught signal %d, initiating shutdown...", signum);
    g_is_running = false;
    if (g_proc_monitor) g_proc_monitor->stop();
    if (g_server) g_server->stop();
}

void worker_thread() {
    LOGI("Worker thread started.");
    while (g_is_running) {
        std::this_thread::sleep_for(std::chrono::seconds(1));
        if (!g_is_running) break;

        if(g_state_manager) g_state_manager->tick();

        if (g_server && g_server->has_clients()) {
            if(g_state_manager) g_state_manager->update_all_resource_stats();
            
            json payload = g_state_manager->get_dashboard_payload();
            json message = {
                {"v", 1},
                {"type", "stream.dashboard_update"},
                {"payload", payload}
            };
            g_server->broadcast_message(message.dump());
        }
    }
    LOGI("Worker thread finished.");
}

int main() {
    signal(SIGTERM, signal_handler);
    signal(SIGINT, signal_handler);

    LOGI("Cerberus Daemon v2.0 (Event-Driven) starting...");

    try {
        if (!fs::exists(DATA_DIR)) {
            fs::create_directories(DATA_DIR);
        }
    } catch (const fs::filesystem_error& e) {
        LOGE("Failed to create data directory: %s", e.what());
        return 1;
    }

    auto db_manager = std::make_shared<DatabaseManager>(DB_PATH);
    auto sys_monitor = std::make_shared<SystemMonitor>();
    auto action_executor = std::make_shared<ActionExecutor>();
    g_state_manager = std::make_shared<StateManager>(db_manager, sys_monitor, action_executor);
    
    g_proc_monitor = std::make_unique<ProcessMonitor>();
    g_proc_monitor->start([&](ProcessEventType type, int pid, int ppid) {
        if (g_state_manager) {
            g_state_manager->process_event_handler(type, pid, ppid);
        }
    });

    std::thread worker(worker_thread);

    g_server = std::make_unique<UdsServer>(SOCKET_NAME);
    g_server->set_message_handler(handle_ui_message);
    g_server->run(); 

    LOGI("UDS server event loop has finished. Cleaning up...");
    g_is_running = false;
    if (worker.joinable()) worker.join();
    
    LOGI("Cerberus Daemon has shut down cleanly.");
    return 0;
}