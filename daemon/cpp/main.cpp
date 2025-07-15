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
std::shared_ptr<SystemMonitor> g_sys_monitor;
std::atomic<bool> g_is_running = true;

void signal_handler(int signum) {
    LOGI("Caught signal %d, initiating shutdown...", signum);
    g_is_running = false;
    if (g_server) g_server->stop();
}

// 【新增】消息处理函数
void handle_message(const std::string& message_str) {
    LOGI("Received message: %s", message_str.c_str());
    try {
        json msg = json::parse(message_str);
        std::string type = msg.value("type", "");
        if (type.rfind("event.", 0) == 0) { // if type starts with "event."
            json payload = msg.value("payload", json::object());
            std::string pkg_name = payload.value("package_name", "");
            if (pkg_name.empty()) return;
            
            if (type == "event.app_start") {
                g_state_manager->on_app_started(pkg_name);
            } else if (type == "event.app_killed") {
                g_state_manager->on_app_killed(pkg_name);
            }
            // ...可以扩展其他事件
        }
        // ...可以扩展处理 "cmd." 或 "query."
    } catch (const json::parse_error& e) {
        LOGW("JSON parse error: %s", e.what());
    }
}

void monitor_thread() {
    LOGI("Monitor thread started.");
    while (g_is_running) {
        if (g_sys_monitor) {
            g_sys_monitor->update_all_stats();
        }
        if (g_state_manager) {
            g_state_manager->update_all_states();
        }
        std::this_thread::sleep_for(std::chrono::seconds(1));
    }
    LOGI("Monitor thread finished.");
}

void broadcaster_thread() {
    LOGI("Broadcaster thread started.");
    while (g_is_running) {
        if (g_server && g_state_manager) {
            json payload = g_state_manager->get_dashboard_payload();
            json message = {
                {"v", 1},
                {"type", "stream.dashboard_update"},
                {"payload", payload}
            };
            g_server->broadcast_message(message.dump());
        }
        std::this_thread::sleep_for(std::chrono::seconds(1));
    }
    LOGI("Broadcaster thread finished.");
}

int main() {
    signal(SIGTERM, signal_handler);
    signal(SIGINT, signal_handler);

    LOGI("Cerberus Daemon v1.5 (Select-IO-Fix) starting...");

    try {
        if (!std::filesystem::exists(DATA_DIR)) {
            std::filesystem::create_directories(DATA_DIR);
            LOGI("Created data directory: %s", DATA_DIR.c_str());
        }
    } catch (const std::filesystem::filesystem_error& e) {
        LOGE("Failed to create data directory: %s", e.what());
    }

    auto db_manager = std::make_shared<DatabaseManager>(DB_PATH);
    g_sys_monitor = std::make_shared<SystemMonitor>();
    auto action_executor = std::make_shared<ActionExecutor>();
    g_state_manager = std::make_shared<StateManager>(db_manager, g_sys_monitor, action_executor);
    
    g_server = std::make_unique<UdsServer>(SOCKET_NAME);
    // 【核心修复】设置消息处理器
    g_server->set_message_handler(handle_message);
    
    std::thread monitor(monitor_thread);
    std::thread broadcaster(broadcaster_thread);

    LOGI("Main thread starting UDS server event loop...");
    g_server->run();

    LOGI("UDS server event loop has finished. Cleaning up threads...");
    g_is_running = false;
    if (monitor.joinable()) monitor.join();
    if (broadcaster.joinable()) broadcaster.join();
    
    LOGI("Cerberus Daemon has shut down cleanly.");
    return 0;
}