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

void handle_message(const std::string& message_str) {
    LOGI("Received message: %s", message_str.c_str());
    try {
        json msg = json::parse(message_str);
        std::string type = msg.value("type", "");

        if (type.rfind("event.", 0) == 0) {
            json payload = msg.value("payload", json::object());
            std::string pkg_name = payload.value("package_name", "");
            int user_id = payload.value("user_id", -1);

            if (pkg_name.empty() || user_id == -1) {
                LOGW("Received event with missing package_name or user_id. Ignoring.");
                return;
            }
            
            if (type == "event.app_start") {
                g_state_manager->handle_app_event(pkg_name, user_id, true);
            } else if (type == "event.app_killed") {
                g_state_manager->handle_app_event(pkg_name, user_id, false);
            }
        }
        // TODO: Handle cmd.* and query.* messages from UI
    } catch (const json::parse_error& e) {
        LOGW("JSON parse error: %s", e.what());
    }
}

void signal_handler(int signum) {
    LOGI("Caught signal %d, initiating shutdown...", signum);
    g_is_running = false;
    if (g_server) g_server->stop();
}

void worker_thread() {
    LOGI("Unified worker thread started.");

    unsigned long long counter = 0;
    // 【新增】用于跟踪前一秒的连接状态
    bool was_ui_connected_previously = false;

    while (g_is_running) {
        // 主循环休眠1秒
        std::this_thread::sleep_for(std::chrono::seconds(1));
        counter++;

        // 轻量级的状态机轮询，每秒执行
        g_state_manager->tick(); 

        bool ui_is_connected = g_server && g_server->has_clients();

        // 【核心修复】如果UI刚刚连接上，则立即触发一次数据更新
        bool new_client_just_connected = ui_is_connected && !was_ui_connected_previously;

        // 只有当UI连接时，才进行消耗资源的统计和广播
        if (ui_is_connected) {
            // 定义资源更新频率
            bool update_user_apps = (counter % 10 == 0); // 每10秒更新用户应用
            bool update_system_apps = (counter % 15 == 0); // 每15秒更新系统应用
            
            // 如果是新连接，则强制刷新所有应用数据，提供即时反馈
            if (new_client_just_connected) {
                LOGI("New UI client connected. Forcing immediate full resource scan.");
                update_user_apps = true;
                update_system_apps = true;
            }

            // 按需更新资源信息
            if (update_user_apps || update_system_apps) {
                g_state_manager->update_resource_stats(update_user_apps, update_system_apps);
            }
            
            // 广播数据给UI
            json payload = g_state_manager->get_dashboard_payload();
            json message = {
                {"v", 1},
                {"type", "stream.dashboard_update"},
                {"payload", payload}
            };
            g_server->broadcast_message(message.dump());
        }

        // 【新增】在循环末尾更新上一秒的状态
        was_ui_connected_previously = ui_is_connected;
    }
    LOGI("Unified worker thread finished.");
}

int main() {
    signal(SIGTERM, signal_handler);
    signal(SIGINT, signal_handler);

    LOGI("Cerberus Daemon v1.8 (New Connection Logic) starting...");

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
    g_server->set_message_handler(handle_message);
    
    // 启动统一的工作线程
    std::thread worker(worker_thread);

    LOGI("Main thread starting UDS server event loop...");
    g_server->run(); // 主线程阻塞在这里处理网络IO

    LOGI("UDS server event loop has finished. Cleaning up threads...");
    g_is_running = false;
    if (worker.joinable()) worker.join();
    
    LOGI("Cerberus Daemon has shut down cleanly.");
    return 0;
}