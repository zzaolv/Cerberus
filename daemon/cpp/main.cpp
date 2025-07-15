// daemon/cpp/main.cpp
#include "uds_server.h"
#include "state_manager.h"
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
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using json = nlohmann::json;

const std::string SOCKET_NAME = "cerberus_socket";
const std::string DATA_DIR = "/data/adb/cerberus";
const std::string DB_PATH = DATA_DIR + "/cerberus.db";

std::unique_ptr<UdsServer> g_server;
std::shared_ptr<StateManager> g_state_manager;
std::atomic<bool> g_is_running = true;

void signal_handler(int signum) {
    LOGI("Caught signal %d, initiating shutdown...", signum);
    g_is_running = false;
    if (g_server) g_server->stop();
}

void monitor_thread() {
    LOGI("Monitor thread started.");
    while (g_is_running) {
        if (g_state_manager) {
            // 这个函数现在包含了状态机逻辑
            g_state_manager->update_all_states();
        }
        std::this_thread::sleep_for(std::chrono::seconds(2));
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

    LOGI("Cerberus Daemon v1.2 (StateMachine-MVP) starting...");

    try {
        if (!std::filesystem::exists(DATA_DIR)) {
            std::filesystem::create_directories(DATA_DIR);
            LOGI("Created data directory: %s", DATA_DIR.c_str());
        }
    } catch(const std::filesystem::filesystem_error& e) {
        LOGE("Failed to create data directory: %s", e.what());
    }

    // --- 初始化核心组件 ---
    auto db_manager = std::make_shared<DatabaseManager>(DB_PATH);
    auto sys_monitor = std::make_shared<SystemMonitor>();
    auto action_executor = std::make_shared<ActionExecutor>(); // 【新增】
    g_state_manager = std::make_shared<StateManager>(db_manager, sys_monitor, action_executor); // 【注入】
    g_server = std::make_unique<UdsServer>(SOCKET_NAME);
    
    std::thread monitor(monitor_thread);
    std::thread broadcaster(broadcaster_thread);

    LOGI("Main thread starting UDS server loop...");
    g_server->run();

    LOGI("UDS server loop has finished. Cleaning up threads...");
    g_is_running = false;
    if (monitor.joinable()) monitor.join();
    if (broadcaster.joinable()) broadcaster.join();
    
    LOGI("Cerberus Daemon has shut down cleanly.");
    return 0;
}