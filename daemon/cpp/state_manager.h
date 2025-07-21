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
#include <set> // [修复] 添加 set 头文件以使用 std::set
#include <chrono> // [性能优化] 引入chrono
#include "database_manager.h"
#include "system_monitor.h"
#include "action_executor.h"

using json = nlohmann::json;

struct AppRuntimeState {
    enum class Status { 
        STOPPED,
        RUNNING,
        FROZEN
    } current_status = Status::STOPPED;

    std::string package_name;
    std::string app_name;
    int uid = -1;
    int user_id = 0;
    std::vector<int> pids; 
    AppConfig config;
    
    bool is_foreground = false;
    time_t background_since = 0;

    float cpu_usage_percent = 0.0f;
    long mem_usage_kb = 0;
    long swap_usage_kb = 0;
};

class StateManager {
public:
    StateManager(std::shared_ptr<DatabaseManager>, std::shared_ptr<SystemMonitor>, std::shared_ptr<ActionExecutor>);

    bool tick();
    bool on_config_changed_from_ui(const json& payload);
    
    // 这两个函数现在作为后备方案
    bool on_app_foreground(const json& payload);
    bool on_app_background(const json& payload);

    json get_dashboard_payload();
    json get_full_config_for_ui();
    json get_probe_config_payload();

private:
    // [修复] 在 private 区域添加缺失的函数声明
    void on_top_app_changed(const std::set<int>& top_pids);

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

    // [性能优化] 节流阀时间戳
    std::chrono::steady_clock::time_point last_top_app_change_processed_ = {};

    std::mutex state_mutex_;
    GlobalStatsData global_stats_;
    FreezeMethod default_freeze_method_ = FreezeMethod::METHOD_SIGSTOP;
    
    using AppInstanceKey = std::pair<std::string, int>; 
    std::map<AppInstanceKey, AppRuntimeState> managed_apps_;
    std::map<int, AppRuntimeState*> pid_to_app_map_;
    std::unordered_set<std::string> critical_system_apps_;
};

#endif //CERBERUS_STATE_MANAGER_H