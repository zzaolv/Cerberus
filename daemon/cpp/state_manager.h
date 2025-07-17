// daemon/cpp/state_manager.h
#ifndef CERBERUS_STATE_MANAGER_H
#define CERBERUS_STATE_MANAGER_H

#include <nlohmann/json.hpp>
#include <string>
#include <vector>
#include <map>
#include <memory>
#include <mutex>
#include <chrono>
#include <unordered_set>
#include "system_monitor.h"
#include "database_manager.h"
#include "process_monitor.h"

class ActionExecutor;

struct AppRuntimeState {
    std::string package_name;
    std::string app_name;
    int uid = -1;
    int user_id;
    std::vector<int> pids; 

    AppConfig config;

    enum class Status {
        STOPPED, FOREGROUND, BACKGROUND_ACTIVE, BACKGROUND_IDLE, AWAITING_FREEZE, FROZEN, EXEMPTED
    } current_status = Status::STOPPED;

    std::chrono::steady_clock::time_point last_state_change_time;
    // 【新增】会话开始时间点
    std::chrono::steady_clock::time_point active_session_start_time;
    
    float cpu_usage_percent = 0.0f;
    long mem_usage_kb = 0;
    long swap_usage_kb = 0;

    bool has_notification = false;
    bool has_network_activity = false;
    long long last_rx_bytes = 0;
    long long last_tx_bytes = 0;
};

class StateManager {
public:
    StateManager(std::shared_ptr<DatabaseManager> db_manager, std::shared_ptr<SystemMonitor> sys_monitor, std::shared_ptr<ActionExecutor> action_executor);

    void process_event_handler(ProcessEventType type, int pid, int ppid);
    
    void handle_probe_event(const nlohmann::json& event);

    void tick(); 
    void update_all_resource_stats();
    nlohmann::json get_dashboard_payload();

    void update_app_config_from_ui(const AppConfig& new_config);

    const std::unordered_set<std::string>& get_safety_net_list() const;

private:
    void initial_scan();
    void refresh_app_list_from_db();
    std::string get_package_name_from_pid(int pid, int& uid, int& user_id);
    void add_pid_to_app(int pid, const std::string& package_name, int user_id, int uid);
    void remove_pid_from_app(int pid);
    void transition_state(AppRuntimeState& app, AppRuntimeState::Status new_status, const std::string& reason);
    void check_and_update_foreground_status();
    void check_system_events();

    AppRuntimeState* find_app_by_pid(int pid);
    AppRuntimeState* get_or_create_app_state(const std::string& package_name, int user_id);
    
    bool is_critical_system_app(const std::string& package_name) const;

    std::shared_ptr<DatabaseManager> db_manager_;
    std::shared_ptr<SystemMonitor> sys_monitor_;
    std::shared_ptr<ActionExecutor> action_executor_;

    std::mutex state_mutex_;
    GlobalStatsData global_stats_;
    bool is_screen_on_ = true;

    using AppInstanceKey = std::pair<std::string, int>;
    std::map<AppInstanceKey, AppRuntimeState> managed_apps_;
    
    std::map<int, AppRuntimeState*> pid_to_app_map_;
    
    std::unordered_set<std::string> critical_system_apps_;
};

#endif //CERBERUS_STATE_MANAGER_H