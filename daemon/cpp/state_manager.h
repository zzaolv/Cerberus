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

// 【新增】用于描述进程在 cgroup 中的状态
enum class CgroupState { FOREGROUND, BACKGROUND, UNKNOWN };

// 【新增】用于缓存从 /proc 中读取的进程信息
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
    
    // 【重构】核心事实发现函数
    void build_process_cache_from_cgroups();
    // 【新增】辅助函数，用于动态发现和创建应用状态实例
    AppRuntimeState* get_or_create_app_state(const std::string& package_name, int user_id);

    std::shared_ptr<DatabaseManager> db_manager_;
    std::shared_ptr<SystemMonitor> sys_monitor_;
    std::shared_ptr<ActionExecutor> action_executor_;

    std::mutex state_mutex_;
    GlobalStatsData global_stats_;

    // 使用<包名, user_id>作为复合键
    using AppInstanceKey = std::pair<std::string, int>;
    std::map<AppInstanceKey, AppRuntimeState> managed_apps_;

    // 缓存应用是否为系统应用
    std::map<std::string, bool> app_is_system_map_;

    // 【新增】进程信息缓存，作为每轮 tick 的事实基础
    std::map<int, ProcessInfo> process_info_cache_;
};

#endif //CERBERUS_STATE_MANAGER_H