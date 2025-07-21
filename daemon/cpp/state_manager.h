// daemon/cpp/state_manager.h
#ifndef CERBERUS_STATE_MANAGER_H
#define CERBERUS_STATE_MANAGER_H

#include <nlohmann/json.hpp>
#include <string>
#include <vector>
#include <map>
#include <memory>
#include <mutex>
#include <unordered_set>
#include "database_manager.h"
#include "system_monitor.h"
#include "action_executor.h" // 包含ActionExecutor以使用FreezeMethod

using json = nlohmann::json;

struct AppRuntimeState {
    // 状态机枚举，比freezeitVS更明确
    enum class Status { 
        STOPPED,        // 进程不存在
        RUNNING,        // 正在运行 (前台或后台等待冻结)
        FROZEN          // 已被冻结
    } current_status = Status::STOPPED;

    std::string package_name;
    std::string app_name;
    int uid = -1;
    int user_id = 0;
    std::vector<int> pids; 
    AppConfig config;
    
    bool is_foreground = false;
    // [核心] 引入时间戳来管理待冻结状态，0表示不在等待队列
    time_t background_since = 0;

    // 资源使用统计
    float cpu_usage_percent = 0.0f;
    long mem_usage_kb = 0;
    long swap_usage_kb = 0;
};

class StateManager {
public:
    StateManager(std::shared_ptr<DatabaseManager>, std::shared_ptr<SystemMonitor>, std::shared_ptr<ActionExecutor>);

    // 主循环调用的核心决策函数
    bool tick();
    
    // 处理来自UI的配置变更
    bool on_config_changed_from_ui(const json& payload);

    // [新增] 处理来自Probe的事件
    bool on_app_foreground(const json& payload);
    bool on_app_background(const json& payload);

    // 为UI和Probe提供数据
    json get_dashboard_payload();
    json get_full_config_for_ui();
    json get_probe_config_payload();

private:
    bool reconcile_process_state_full(); 
    void load_all_configs();
    std::string get_package_name_from_pid(int pid, int& uid, int& user_id);
    void add_pid_to_app(int pid, const std::string&, int user_id, int uid);
    void remove_pid_from_app(int pid);
    
    AppRuntimeState* get_or_create_app_state(const std::string&, int user_id);
    bool is_critical_system_app(const std::string&) const;

    std::shared_ptr<DatabaseManager> db_manager_;
    std::shared_ptr<SystemMonitor> sys_monitor_;
    std::shared_ptr<ActionExecutor> action_executor_;

    std::mutex state_mutex_;
    GlobalStatsData global_stats_;
    FreezeMethod default_freeze_method_ = FreezeMethod::SIGSTOP; // 默认使用兼容性最强的SIGSTOP
    
    using AppInstanceKey = std::pair<std::string, int>; 
    std::map<AppInstanceKey, AppRuntimeState> managed_apps_;
    std::map<int, AppRuntimeState*> pid_to_app_map_;
    std::unordered_set<std::string> critical_system_apps_;
};

#endif //CERBERUS_STATE_MANAGER_H