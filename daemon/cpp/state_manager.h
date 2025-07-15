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

// 【核心修复】必须包含 system_monitor.h 因为需要 GlobalStatsData 的完整定义
#include "system_monitor.h" 
// 【核心修复】必须包含 database_manager.h 因为需要 AppConfig 的完整定义
#include "database_manager.h" 

// 【核心修复】对 ActionExecutor 使用前向声明
class ActionExecutor;

struct AppRuntimeState {
    std::string package_name;
    std::string app_name;
    int uid;

    AppConfig config;

    enum class Status {
        FOREGROUND, BACKGROUND_ACTIVE, BACKGROUND_IDLE, AWAITING_FREEZE, FROZEN, EXEMPTED
    } current_status = Status::BACKGROUND_IDLE;
    
    std::chrono::steady_clock::time_point last_state_change_time;

    float cpu_usage_percent = 0.0f;
    long mem_usage_kb = 0;
};

class StateManager {
public:
    StateManager(std::shared_ptr<DatabaseManager> db_manager, std::shared_ptr<SystemMonitor> sys_monitor, std::shared_ptr<ActionExecutor> action_executor);

    void update_all_states();
    nlohmann::json get_dashboard_payload();

    void on_app_killed(const std::string& package_name);
    void on_app_started(const std::string& package_name);

private:
    void refresh_installed_apps();
    void transition_state(AppRuntimeState& app, AppRuntimeState::Status new_status);

    std::shared_ptr<DatabaseManager> db_manager_;
    std::shared_ptr<SystemMonitor> sys_monitor_;
    std::shared_ptr<ActionExecutor> action_executor_;

    std::mutex state_mutex_;
    GlobalStatsData global_stats_;
    std::map<std::string, AppRuntimeState> managed_apps_;
};

#endif //CERBERUS_STATE_MANAGER_H