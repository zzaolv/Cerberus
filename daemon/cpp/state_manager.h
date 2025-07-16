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
#include <set>

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

    // 【新增】“心跳标记”，用于标记-清扫算法
    bool is_live_this_tick = false;
};

enum class CgroupState { FOREGROUND, BACKGROUND, UNKNOWN };

struct ProcessInfo {
    int pid;
    int uid;
    std::string package_name;
    CgroupState cgroup_state;
};

class StateManager {
public:
    StateManager(std::shared_ptr<DatabaseManager> db_manager, std::shared_ptr<SystemMonitor> sys_monitor, std::shared_ptr<ActionExecutor> action_executor);

    void tick();
    void update_resource_stats(bool update_user, bool update_system);

    nlohmann::json get_dashboard_payload();
    void handle_app_event(const std::string& package_name, int user_id, bool is_start);

private:
    void refresh_installed_apps();
    void transition_state(AppRuntimeState& app, AppRuntimeState::Status new_status);
    void build_process_cache_from_cgroups();
    AppRuntimeState* get_or_create_app_state(const std::string& package_name, int user_id);

    std::shared_ptr<DatabaseManager> db_manager_;
    std::shared_ptr<SystemMonitor> sys_monitor_;
    std::shared_ptr<ActionExecutor> action_executor_;

    std::mutex state_mutex_;
    GlobalStatsData global_stats_;

    using AppInstanceKey = std::pair<std::string, int>;
    std::map<AppInstanceKey, AppRuntimeState> managed_apps_;

    std::map<std::string, bool> app_is_system_map_;

    std::map<int, ProcessInfo> process_info_cache_;
};

#endif //CERBERUS_STATE_MANAGER_H