// daemon/cpp/main.cpp
#include "uds_server.h"
#include "state_manager.h"
#include "system_monitor.h"
#include "database_manager.h"
#include "action_executor.h"
#include "main.h"
#include <nlohmann/json.hpp>
#include <android/log.h>
#include "logger.h"
#include "time_series_database.h"
#include <csignal>
#include <thread>
#include <chrono>
#include <memory>
#include <atomic>
#include <filesystem>
#include <mutex>
#include <unistd.h>

#define LOG_TAG "cerberusd_main_v21_hotfix"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

using json = nlohmann::json;
namespace fs = std::filesystem;

std::unique_ptr<UdsServer> g_server;
static std::shared_ptr<StateManager> g_state_manager;
static std::shared_ptr<SystemMonitor> g_sys_monitor;
static std::shared_ptr<Logger> g_logger;
static std::shared_ptr<TimeSeriesDatabase> g_ts_db;
static std::atomic<bool> g_is_running = true;
std::atomic<int> g_probe_fd = -1;
static std::thread g_worker_thread;
std::atomic<int> g_top_app_refresh_tickets = 0;

void handle_client_message(int client_fd, const std::string& message_str) {
    try {
        json msg = json::parse(message_str);
        std::string type = msg.value("type", "");

        if (type == "query.get_all_logs") {
            auto logs = g_logger->get_history(msg.value("limit", 200));
            json log_array = json::array();
            for(const auto& log : logs) { log_array.push_back(log.to_json()); }
            g_server->send_message(client_fd, json{ {"type", "resp.all_logs"}, {"req_id", msg.value("req_id", "")}, {"payload", log_array} }.dump());
            return;
        } else if (type == "query.get_history_stats") {
            auto records = g_ts_db->get_all_records();
            json record_array = json::array();
            for(const auto& record : records) { record_array.push_back(record.to_json()); }
            g_server->send_message(client_fd, json{ {"type", "resp.history_stats"}, {"req_id", msg.value("req_id", "")}, {"payload", record_array} }.dump());
            return;
        }

        if (!g_state_manager) return;

        // [核心架构] 完整处理所有来自Probe的事件
        if (type == "cmd.proactive_unfreeze") {
            g_state_manager->on_proactive_unfreeze_request(msg.at("payload"));
        } else if (type == "event.app_foreground") {
            g_state_manager->on_app_foreground_event(msg.at("payload"));
        } else if (type == "event.app_background") {
            g_state_manager->on_app_background_event(msg.at("payload"));
        }
        else if (type == "cmd.request_temp_unfreeze_pkg") {
            g_state_manager->on_temp_unfreeze_request_by_pkg(msg.at("payload"));
        } else if (type == "cmd.request_temp_unfreeze_uid") {
            g_state_manager->on_temp_unfreeze_request_by_uid(msg.at("payload"));
        } else if (type == "cmd.request_temp_unfreeze_pid") {
            g_state_manager->on_temp_unfreeze_request_by_pid(msg.at("payload"));
        }
        else if (type == "event.app_wakeup_request") {
            g_state_manager->on_wakeup_request(msg.at("payload"));
        } else if (type == "cmd.set_policy") {
            if (g_state_manager->on_config_changed_from_ui(msg.at("payload"))) {
                notify_probe_of_config_change();
            }
            g_top_app_refresh_tickets = 1; 
        } 
        else if (type == "cmd.set_master_config") {
            MasterConfig cfg;
            const auto& payload = msg.at("payload");
            cfg.standard_timeout_sec = payload.value("standard_timeout_sec", 90);
            cfg.is_timed_unfreeze_enabled = payload.value("is_timed_unfreeze_enabled", true);
            cfg.timed_unfreeze_interval_sec = payload.value("timed_unfreeze_interval_sec", 1800);
            g_state_manager->update_master_config(cfg);
        } 
        else if (type == "query.refresh_dashboard") {
            broadcast_dashboard_update();
        } else if (type == "query.get_all_policies") {
            json payload = g_state_manager->get_full_config_for_ui();
            g_server->send_message(client_fd, json{{"type", "resp.all_policies"}, {"req_id", msg.value("req_id", "")}, {"payload", payload}}.dump());
        } else if (type == "event.probe_hello") {
            g_probe_fd = client_fd;
            notify_probe_of_config_change();
        }
    } catch (const json::exception& e) { LOGE("JSON Error: %s in msg: %s", e.what(), message_str.c_str()); }
}

void handle_client_disconnect(int client_fd) {
    LOGI("Client fd %d has disconnected.", client_fd);
    if (client_fd == g_probe_fd.load()) {
        g_probe_fd = -1;
    }
}

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
        json payload = g_state_manager->get_probe_config_payload();
        g_server->send_message(probe_fd, json{{"type", "stream.probe_config_update"}, {"payload", payload}}.dump());
    }
}

void signal_handler(int signum) {
    LOGW("Signal %d received, shutting down...", signum);
    g_is_running = false;
    if (g_server) g_server->stop();
    if (g_logger) g_logger->stop();
}

void worker_thread_func() {
    LOGI("Worker thread started.");
    g_top_app_refresh_tickets = 2;
    
    // [核心修复] 重新加入轮询计时器
    int audio_scan_countdown = 5;
    int location_scan_countdown = 7;
    
    const int SAMPLING_INTERVAL_SEC = 2;

    while (g_is_running) {
        auto loop_start_time = std::chrono::steady_clock::now();
        bool state_changed = false;

        // 1. [数据采集]
        auto metrics_opt = g_sys_monitor->collect_current_metrics();
        if (metrics_opt) {
            g_ts_db->add_record(*metrics_opt);
            g_state_manager->process_new_metrics(*metrics_opt);
        }

        // 2. [情报与决策]
        if (g_top_app_refresh_tickets > 0) {
            g_top_app_refresh_tickets--;
            state_changed = true;
        }
        
        // 核心战略执行
        if (g_state_manager->evaluate_and_execute_strategy()) {
            state_changed = true;
        }

        // 定期深度扫描，同步进程列表
        if (g_state_manager->perform_deep_scan()) {
            state_changed = true;
        }
        
        // [核心修复] 重新加入音频和定位状态的周期性检查
        if (--audio_scan_countdown <= 0) {
            g_sys_monitor->update_audio_state();
            audio_scan_countdown = 3; // ~6 seconds
        }
        if (--location_scan_countdown <= 0) {
            g_sys_monitor->update_location_state();
            location_scan_countdown = 15; // ~30 seconds
        }

        // 3. [同步]
        if (state_changed) {
            broadcast_dashboard_update();
        }

        // 4. [循环周期控制]
        auto loop_end_time = std::chrono::steady_clock::now();
        auto duration = std::chrono::duration_cast<std::chrono::seconds>(loop_end_time - loop_start_time);
        if (duration.count() < SAMPLING_INTERVAL_SEC) {
            std::this_thread::sleep_for(std::chrono::seconds(SAMPLING_INTERVAL_SEC - duration.count()));
        }
    }
    LOGI("Worker thread finished.");
}


int main(int argc, char *argv[]) {
    signal(SIGTERM, signal_handler);
    signal(SIGINT, signal_handler);
    signal(SIGPIPE, SIG_IGN);

    const std::string DATA_DIR = "/data/adb/cerberus";
    const std::string DB_PATH = DATA_DIR + "/cerberus.db";
    const std::string LOG_DIR = DATA_DIR + "/logs";
    LOGI("Project Cerberus Daemon starting... (PID: %d)", getpid());
    
    try {
        if (!fs::exists(DATA_DIR)) fs::create_directories(DATA_DIR);
        if (!fs::exists(LOG_DIR)) fs::create_directories(LOG_DIR);
    } catch(const fs::filesystem_error& e) {
        LOGE("Failed to create data dir: %s", e.what());
        return 1;
    }

    auto db_manager = std::make_shared<DatabaseManager>(DB_PATH);
    auto action_executor = std::make_shared<ActionExecutor>();
    g_sys_monitor = std::make_shared<SystemMonitor>();
    g_logger = Logger::get_instance(LOG_DIR);
    g_ts_db = TimeSeriesDatabase::get_instance();
    g_state_manager = std::make_shared<StateManager>(db_manager, g_sys_monitor, action_executor, g_logger, g_ts_db);
    
    g_logger->log(LogLevel::EVENT, "Daemon", "守护进程已启动");
    
    g_sys_monitor->start_top_app_monitor();
    g_sys_monitor->start_network_snapshot_thread();
    g_worker_thread = std::thread(worker_thread_func);
    
    //g_server = std::make_unique<UdsServer>("cerberus_socket");
    const int DAEMON_PORT = 28900; // 定义我们的通信端口
    g_server = std::make_unique<UdsServer>(DAEMON_PORT); // [核心修改] 使用端口号实例化    
    g_server->set_message_handler(handle_client_message);
    g_server->set_disconnect_handler(handle_client_disconnect);
    g_server->run();
    
    g_is_running = false;
    if(g_worker_thread.joinable()) g_worker_thread.join();
    
    g_sys_monitor->stop_top_app_monitor();
    g_sys_monitor->stop_network_snapshot_thread();

    LOGI("Cerberus Daemon has shut down cleanly.");
    return 0;
}