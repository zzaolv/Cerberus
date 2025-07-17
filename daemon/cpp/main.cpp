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
#include <unistd.h> // [修复] 添加此头文件以声明 getpid()

#define LOG_TAG "cerberusd"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using json = nlohmann::json;
namespace fs = std::filesystem;

const std::string SOCKET_NAME = "cerberus_socket";
const std::string DATA_DIR = "/data/adb/cerberus";
const std::string DB_PATH = DATA_DIR + "/cerberus.db";

std::unique_ptr<UdsServer> g_server;
std::shared_ptr<StateManager> g_state_manager;
std::unique_ptr<ProcessMonitor> g_proc_monitor;
std::shared_ptr<DatabaseManager> g_db_manager;
std::atomic<bool> g_is_running = true;

std::atomic<bool> g_probe_connected = false;
std::atomic<long long> g_last_probe_timestamp = 0;


void handle_incoming_message(int client_fd, const std::string& message_str) {
    try {
        json msg = json::parse(message_str);
        std::string type = msg.value("type", "");

        LOGI("Handling message of type: %s", type.c_str());

        if (type.rfind("event.", 0) == 0) {
            g_probe_connected = true;
            g_last_probe_timestamp = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
            if (g_state_manager) g_state_manager->handle_probe_event(msg);

        } else if (type.rfind("cmd.", 0) == 0) {
            const auto& payload = msg["payload"];
            if (type == "cmd.set_policy") {
                AppConfig new_config;
                new_config.package_name = payload.value("package_name", "");
                int policy_int = payload.value("policy", 2);
                new_config.policy = (policy_int >= 0 && policy_int <= 3) ? static_cast<AppPolicy>(policy_int) : AppPolicy::STANDARD;
                new_config.force_playback_exempt = payload.value("force_playback_exempt", false);
                new_config.force_network_exempt = payload.value("force_network_exempt", false);
                if (g_state_manager) g_state_manager->update_app_config_from_ui(new_config);
            } else if (type == "cmd.set_settings") {
                int freezer_int = payload.value("freezer_type", 0);
                int unfreeze_interval = payload.value("unfreeze_interval", 0);
                if (g_state_manager) {
                    g_state_manager->set_freezer_type(static_cast<FreezerType>(freezer_int));
                    g_state_manager->set_periodic_unfreeze_interval(unfreeze_interval);
                }
                LOGI("Settings updated: FreezerType=%d, UnfreezeInterval=%dmin", freezer_int, unfreeze_interval);
            } else if (type == "cmd.restart_daemon") {
                LOGI("Restart command received. Shutting down...");
                g_is_running = false;
                if (g_server) g_server->stop();
            } else if (type == "cmd.clear_stats") {
                if(g_db_manager) g_db_manager->clear_all_stats();
                LOGI("All resource statistics have been cleared.");
            }

        } else if (type.rfind("query.", 0) == 0) {
            json response_payload;
            std::string response_type;

            if (type == "query.get_resource_stats") {
                response_type = "resp.resource_stats";
                auto configs = g_db_manager->get_all_app_configs();
                response_payload = json::array();
                for (const auto& cfg : configs) {
                    if (cfg.background_cpu_seconds > 0 || cfg.background_traffic_bytes > 0 || cfg.background_wakeups > 0) {
                        response_payload.push_back({
                            {"package_name", cfg.package_name},
                            {"cpu_seconds", cfg.background_cpu_seconds},
                            {"traffic_bytes", cfg.background_traffic_bytes},
                            {"wakeups", cfg.background_wakeups}
                        });
                    }
                }
            } else if (type == "query.health_check") {
                response_type = "resp.health_check";
                long long now = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
                bool is_probe_alive = g_probe_connected.load() && (now - g_last_probe_timestamp) < 30000;
                response_payload = {
                    {"daemon_pid", getpid()},
                    {"is_probe_connected", is_probe_alive}
                };
            } else if (type == "query.get_all_policies") {
                response_type = "resp.all_policies";
                auto configs = g_db_manager->get_all_app_configs();
                json configs_json = json::array();
                for (const auto& cfg : configs) {
                    configs_json.push_back({
                        {"packageName", cfg.package_name},
                        {"appName", ""},
                        {"policy", cfg.policy},
                        {"forcePlaybackExemption", cfg.force_playback_exempt},
                        {"forceNetworkExemption", cfg.force_network_exempt}
                    });
                }
                response_payload = configs_json;
            } else if (type == "query.get_logs") {
                response_type = "resp.logs";
                int limit = msg["payload"].value("limit", 50);
                int offset = msg["payload"].value("offset", 0);
                auto logs = g_db_manager->get_logs(limit, offset);
                json logs_json = json::array();
                for (const auto& log : logs) {
                    logs_json.push_back({
                        {"timestamp", log.timestamp},
                        {"event_type", static_cast<int>(log.event_type)},
                        {"payload", log.payload}
                    });
                }
                response_payload = logs_json;
            }
            
            if (!response_type.empty()) {
                json response_msg = {
                    {"v", 1},
                    {"type", response_type},
                    {"req_id", msg.value("req_id", "")},
                    {"payload", response_payload}
                };
                LOGI("Sending response of type: %s", response_type.c_str());
                g_server->send_message_to_client(client_fd, response_msg.dump());
            }
        }

    } catch (const json::parse_error& e) {
        LOGW("JSON parse error: %s for message: %s", e.what(), message_str.c_str());
    } catch (const std::exception& e) {
        LOGE("Error handling message: %s", e.what());
    }
}

void signal_handler(int signum) {
    LOGI("Caught signal %d, initiating shutdown...", signum);
    if(g_db_manager) g_db_manager->log_event(LogEventType::DAEMON_SHUTDOWN, {{"message", "Cerberus daemon shutting down."}});
    g_is_running = false;
    if (g_proc_monitor) g_proc_monitor->stop();
    if (g_server) g_server->stop();
}

void worker_thread() {
    LOGI("Worker thread started.");
    while (g_is_running) {
        std::this_thread::sleep_for(std::chrono::seconds(1));
        if (!g_is_running) break;

        if (g_state_manager) {
            g_state_manager->tick();
        }

        if (g_server && g_server->has_clients()) {
            if (g_state_manager) {
                g_state_manager->update_all_resource_stats();
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

int main() {
    signal(SIGTERM, signal_handler);
    signal(SIGINT, signal_handler);

    LOGI("Cerberus Daemon starting...");

    try {
        if (!fs::exists(DATA_DIR)) {
            fs::create_directories(DATA_DIR);
            LOGI("Created data directory: %s", DATA_DIR.c_str());
        }
    } catch (const fs::filesystem_error& e) {
        LOGE("Failed to create data directory: %s", e.what());
        return 1;
    }

    g_db_manager = std::make_shared<DatabaseManager>(DB_PATH);
    auto sys_monitor = std::make_shared<SystemMonitor>();
    auto action_executor = std::make_shared<ActionExecutor>();
    g_state_manager = std::make_shared<StateManager>(g_db_manager, sys_monitor, action_executor);
    
    g_proc_monitor = std::make_unique<ProcessMonitor>();
    g_proc_monitor->start([&](ProcessEventType type, int pid, int ppid) {
        if (g_state_manager) {
            g_state_manager->process_event_handler(type, pid, ppid);
        }
    });

    std::thread worker(worker_thread);

    g_server = std::make_unique<UdsServer>(SOCKET_NAME);
    g_server->set_message_handler(handle_incoming_message);
    g_server->run(); 

    LOGI("UDS server event loop has finished. Cleaning up...");
    g_is_running = false;
    if (worker.joinable()) worker.join();
    
    LOGI("Cerberus Daemon has shut down cleanly.");
    return 0;
}