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
#include <utility> 

#include "system_monitor.h" 
#include "database_manager.h" 

class ActionExecutor;

struct AppRuntimeState {
    std::string package_name;
    std::string app_name;
    int uid = -1;
    int user_id;

    AppConfig config;

    enum class Status {
        STOPPED, FOREGROUND, BACKGROUND_ACTIVE, BACKGROUND_IDLE, AWAITING_FREEZE, FROZEN, EXEMPTED
    } current_status = Status::STOPPED;
    
    std::chrono::steady_clock::time_point last_state_change_time;
    float cpu_usage_percent = 0.0f;
    long mem_usage_kb = 0;
    long swap_usage_kb = 0;
};

class StateManager {
public:
    StateManager(std::shared_ptr<DatabaseManager> db_manager, std::shared_ptr<SystemMonitor> sys_monitor, std::shared_ptr<ActionExecutor> action_executor);

    /**
     * @brief 轻量级的状态机轮询，每秒执行。负责处理应用状态转移（如超时冻结）。
     */
    void tick();

    /**
     * @brief 按需更新应用的资源使用情况（CPU, 内存等）。这是一个耗时操作。
     * @param update_user 是否更新第三方应用的信息。
     * @param update_system 是否更新系统应用的信息。
     */
    void update_resource_stats(bool update_user, bool update_system);
    
    nlohmann::json get_dashboard_payload();
    void handle_app_event(const std::string& package_name, int user_id, bool is_start);

private:
    void refresh_installed_apps();
    void transition_state(AppRuntimeState& app, AppRuntimeState::Status new_status);
    void build_process_cache();
    int get_pid_for_app_instance(int uid);

    std::shared_ptr<DatabaseManager> db_manager_;
    std::shared_ptr<SystemMonitor> sys_monitor_;
    std::shared_ptr<ActionExecutor> action_executor_;

    std::mutex state_mutex_;
    GlobalStatsData global_stats_;
    
    using AppInstanceKey = std::pair<std::string, int>;
    std::map<AppInstanceKey, AppRuntimeState> managed_apps_;

    std::map<int, std::vector<int>> uid_to_pids_map_;
    
    // 【新增】缓存应用是否为系统应用
    std::map<std::string, bool> app_is_system_map_;
};

#endif //CERBERUS_STATE_MANAGER_H