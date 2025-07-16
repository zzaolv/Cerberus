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
#include <utility> // For std::pair

#include "system_monitor.h" 
#include "database_manager.h" 

class ActionExecutor;

struct AppRuntimeState {
    std::string package_name;
    std::string app_name;
    int uid;
    int user_id;

    AppConfig config;

    enum class Status {
        STOPPED, FOREGROUND, BACKGROUND_ACTIVE, BACKGROUND_IDLE, AWAITING_FREEZE, FROZEN, EXEMPTED
    } current_status = Status::STOPPED;
    
    std::chrono::steady_clock::time_point last_state_change_time;
    float cpu_usage_percent = 0.0f;
    long mem_usage_kb = 0;
    long swap_usage_kb = 0; // 【新增】应用占用的交换空间
};

class StateManager {
public:
    StateManager(std::shared_ptr<DatabaseManager> db_manager, std::shared_ptr<SystemMonitor> sys_monitor, std::shared_ptr<ActionExecutor> action_executor);
    void update_all_states();
    nlohmann::json get_dashboard_payload();

    // 【核心重构】事件处理函数现在需要 user_id
    void on_app_killed(const std::string& package_name, int user_id);
    void on_app_started(const std::string& package_name, int user_id);

private:
    void refresh_installed_apps();
    void transition_state(AppRuntimeState& app, AppRuntimeState::Status new_status);
    
    // 【核心重构】查找PID的函数现在需要完整的UID
    int get_pid_for_app_instance(int uid);

    std::shared_ptr<DatabaseManager> db_manager_;
    std::shared_ptr<SystemMonitor> sys_monitor_;
    std::shared_ptr<ActionExecutor> action_executor_;

    std::mutex state_mutex_;
    GlobalStatsData global_stats_;
    
    // 【核心重构】使用复合键 (package_name, user_id) 来唯一标识应用实例
    using AppInstanceKey = std::pair<std::string, int>;
    std::map<AppInstanceKey, AppRuntimeState> managed_apps_;
};

#endif //CERBERUS_STATE_MANAGER_H