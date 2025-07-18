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
#include <unistd.h> // 【核心修复】添加头文件以声明 getpid()

#define LOG_TAG "cerberusd_main"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using json = nlohmann::json;
namespace fs = std::filesystem;

// --- 全局变量与常量 ---
const std::string SOCKET_NAME = "cerberus_socket";
const std::string DATA_DIR = "/data/adb/cerberus";
const std::string DB_PATH = DATA_DIR + "/cerberus.db";

std::atomic<bool> g_is_running = true;

std::unique_ptr<UdsServer> g_server;
std::shared_ptr<StateManager> g_state_manager;
std::unique_ptr<ProcessMonitor> g_proc_monitor;


// --- 消息处理 ---
void handle_client_message(int client_fd, const std::string& message_str) {
    LOGI("Received message from client fd %d: %s", client_fd, message_str.c_str());
    try {
        json msg = json::parse(message_str);
        std::string type = msg.value("type", "");
        std::string req_id = msg.value("req_id", "");

        json response_payload;
        std::string response_type;

        if (type == "cmd.set_policy") {
            const auto& payload = msg.at("payload");
            AppConfig new_config;
            new_config.package_name = payload.value("package_name", "");
            int user_id = payload.value("user_id", 0);
            new_config.policy = static_cast<AppPolicy>(payload.value("policy", 2));
            new_config.force_playback_exempt = payload.value("force_playback_exempt", false);
            new_config.force_network_exempt = payload.value("force_network_exempt", false);
            
            if (!new_config.package_name.empty() && g_state_manager) {
                g_state_manager->update_app_config_from_ui(new_config, user_id);
            }
        }
        else if (type.rfind("event.", 0) == 0) {
             if (g_state_manager) {
                g_state_manager->handle_probe_event(msg);
             }
        }
        else if (type == "query.get_all_policies") {
            if (g_state_manager) {
                response_payload = g_state_manager->get_full_config_for_ui();
                response_type = "resp.all_policies";
            }
        } else if (type == "query.health_check") {
            response_payload = { {"status", "ok"}, {"daemon_pid", getpid()} };
            response_type = "resp.health_check";
        }
        else {
            LOGW("Received unknown message type: %s", type.c_str());
        }

        if (!response_type.empty() && !req_id.empty()) {
            json response_msg = {
                {"v", 1},
                {"type", response_type},
                {"req_id", req_id},
                {"payload", response_payload}
            };
            if (g_server) g_server->send_message(client_fd, response_msg.dump());
        }

    } catch (const json::exception& e) {
        LOGW("JSON processing error: %s. Message: %s", e.what(), message_str.c_str());
    } catch (const std::exception& e) {
        LOGE("Error handling client message: %s", e.what());
    }
}

// --- 信号处理与线程函数 ---
void signal_handler(int signum) {
    LOGI("Caught signal %d, initiating shutdown...", signum);
    g_is_running = false;
    if (g_server) g_server->stop();
    if (g_proc_monitor) g_proc_monitor->stop();
}

void worker_thread_func() {
    LOGI("Worker thread started.");
    while (g_is_running) {
        std::this_thread::sleep_for(std::chrono::seconds(1));
        if (!g_is_running) break;

        if (g_state_manager) g_state_manager->tick();

        if (g_server && g_server->has_clients()) {
            if (g_state_manager) {
                json payload = g_state_manager->get_dashboard_payload();
                json message = {
                    {"v", 1},
                    {"type", "stream.dashboard_update"},
                    {"payload", payload}
                };
                g_server->broadcast_message(message.dump());
            }
        }
    }
    LOGI("Worker thread finished.");
}


// --- 主函数 ---
int main() {
    signal(SIGTERM, signal_handler);
    signal(SIGINT, signal_handler);

    LOGI("Project Cerberus Daemon v1.1.1 starting... (PID: %d)", getpid());

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
    
    g_proc_monitor = std::make_unique<ProcessMonitor>();
    g_proc_monitor->start([&](ProcessEventType type, int pid, int ppid) {
        if (g_state_manager) {
            g_state_manager->handle_process_event(type, pid, ppid);
        }
    });

    std::thread worker_thread(worker_thread_func);

    g_server = std::make_unique<UdsServer>(SOCKET_NAME);
    g_server->set_message_handler(handle_client_message);
    g_server->run(); 

    LOGI("Server loop has finished. Cleaning up...");
    g_is_running = false;
    if (worker_thread.joinable()) worker_thread.join();
    
    LOGI("Cerberus Daemon has shut down cleanly.");
    return 0;
}