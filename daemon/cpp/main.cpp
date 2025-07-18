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
#include <condition_variable>
#include <unistd.h>

#define LOG_TAG "cerberusd_main_v3.0"
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
std::atomic<int> g_probe_fd = -1;

std::atomic<bool> g_force_refresh_flag = false;
std::mutex g_worker_mutex;
std::condition_variable g_worker_cv;

void trigger_state_broadcast() {
    g_force_refresh_flag = true;
    g_worker_cv.notify_one();
}

void notify_probe_of_config_change() {
    int probe_fd = g_probe_fd.load();
    if (g_server && probe_fd != -1 && g_state_manager) {
        json payload = g_state_manager->get_probe_config_payload();
        json message = {
            {"v", 3},
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

void handle_client_message(int client_fd, const std::string& message_str) {
    try {
        json msg = json::parse(message_str);
        std::string type = msg.value("type", "");
        
        // --- 事件处理 ---
        if (type.rfind("event.", 0) == 0) {
            if (client_fd != g_probe_fd.load() && type != "event.probe_hello") {
                LOGW("Ignoring event from non-probe client fd %d", client_fd);
                return;
            }
            if (type == "event.probe_hello") {
                LOGI("Probe hello received from fd %d.", client_fd);
                g_probe_fd = client_fd;
                if (g_state_manager) g_state_manager->on_probe_hello(client_fd);
                notify_probe_of_config_change();
            } else if (type == "event.app_state_changed") {
                if (g_state_manager) g_state_manager->on_app_state_changed_from_probe(msg.at("payload"));
                trigger_state_broadcast();
            } else if (type == "event.system_state_changed") {
                if (g_state_manager) g_state_manager->on_system_state_changed_from_probe(msg.at("payload"));
                trigger_state_broadcast();
            }
            return;
        }
        
        // --- 命令与查询处理 ---
        json response_payload;
        std::string response_type;

        if (type == "cmd.request_immediate_unfreeze") {
            if (client_fd == g_probe_fd.load()) {
                json payload = msg.at("payload");
                if (g_state_manager) {
                    g_state_manager->on_unfreeze_request_from_probe(payload);
                    json response_msg = {
                        {"v", 3},
                        {"type", "resp.unfreeze_complete"},
                        {"payload", payload}
                    };
                    g_server->send_message(client_fd, response_msg.dump());
                    notify_probe_of_config_change();
                    trigger_state_broadcast();
                }
            }
        } else if (type == "cmd.set_policy") {
            const auto& payload = msg.at("payload");
            LOGI("Received cmd.set_policy for %s", payload.value("package_name", "N/A").c_str());
            AppConfig new_config;
            new_config.package_name = payload.value("package_name", "");
            int user_id = payload.value("user_id", 0);
            new_config.policy = static_cast<AppPolicy>(payload.value("policy", 2));
            new_config.force_playback_exempt = payload.value("force_playback_exempt", false);
            new_config.force_network_exempt = payload.value("force_network_exempt", false);
            if (g_state_manager) {
                g_state_manager->on_config_changed_from_ui(new_config, user_id);
                notify_probe_of_config_change();
                trigger_state_broadcast();
            }
        } else if (type == "query.get_all_policies") {
            if (g_state_manager) {
                response_payload = g_state_manager->get_full_config_for_ui();
                response_type = "resp.all_policies";
            }
        } else if (type == "query.refresh_dashboard") {
            trigger_state_broadcast();
        } else {
            LOGW("Received unknown message type from fd %d: %s", client_fd, type.c_str());
        }

        if (!response_type.empty()) {
            std::string req_id = msg.value("req_id", "");
            json response_msg = {
                {"v", 3},
                {"type", response_type},
                {"req_id", req_id},
                {"payload", response_payload}
            };
            if (g_server) g_server->send_message(client_fd, response_msg.dump());
        }

    } catch (const std::exception& e) {
        LOGE("Error handling message from fd %d: %s. Message: %s", client_fd, e.what(), message_str.c_str());
    }
}

void signal_handler(int signum) {
    LOGI("Caught signal %d, initiating shutdown...", signum);
    g_is_running = false;
    g_worker_cv.notify_one();
    if (g_server) g_server->stop();
    if (g_proc_monitor) g_proc_monitor->stop();
}

void worker_thread_func() {
    LOGI("Worker thread started.");
    while (g_is_running) {
        {
            std::unique_lock<std::mutex> lock(g_worker_mutex);
            g_worker_cv.wait_for(lock, std::chrono::seconds(3), [&]{
                return !g_is_running || g_force_refresh_flag.load();
            });
        }

        if (!g_is_running) break;
        
        if (g_force_refresh_flag.load()) {
            LOGI("Forced refresh triggered.");
        }
        g_force_refresh_flag = false;
        
        if (g_state_manager) {
            g_state_manager->tick();
        }

        if (g_server && g_server->has_clients()) {
            json payload = g_state_manager->get_dashboard_payload();
            json message = {
                {"v", 3},
                {"type", "stream.dashboard_update"},
                {"payload", payload}
            };
            g_server->broadcast_message_except(message.dump(), g_probe_fd.load());
        }
    }
    LOGI("Worker thread finished.");
}

int main(int argc, char *argv[]) {
    signal(SIGTERM, signal_handler);
    signal(SIGINT, signal_handler);

    LOGI("Project Cerberus Daemon v3.0 starting... (PID: %d)", getpid());

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
            g_state_manager->on_process_event(type, pid, ppid);
            trigger_state_broadcast();
        }
    });

    std::thread worker_thread(worker_thread_func);

    g_server = std::make_unique<UdsServer>(SOCKET_NAME);
    g_server->set_message_handler(handle_client_message);
    g_server->set_disconnect_handler(handle_client_disconnect);
    g_server->run();

    LOGI("Server loop has finished. Cleaning up...");
    g_is_running = false;
    g_probe_fd = -1;
    if (worker_thread.joinable()) worker_thread.join();
    
    LOGI("Cerberus Daemon has shut down cleanly.");
    return 0;
}