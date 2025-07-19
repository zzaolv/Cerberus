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

class ActionExecutor;
using json = nlohmann::json;

struct AppRuntimeState {
    std::string package_name;
    std::string app_name;
    int uid = -1;
    int user_id = 0;
    std::vector<int> pids; 
    AppConfig config;
    enum class Status {
        STOPPED, FOREGROUND, BACKGROUND_IDLE, AWAITING_FREEZE, FROZEN, EXEMPTED
    } current_status = Status::STOPPED;
    std::chrono::steady_clock::time_point last_state_change_time;
    float cpu_usage_percent = 0.0f;
    long mem_usage_kb = 0;
    long swap_usage_kb = 0;
    bool is_foreground = false;
    bool has_audio = false;
    bool has_notification = false;
};

class StateManager {
public:
    StateManager(std::shared_ptr<DatabaseManager> db, 
                 std::shared_ptr<SystemMonitor> sys, 
                 std::shared_ptr<ActionExecutor> act);

    bool tick();
    
    void on_probe_hello(int probe_fd);
    void on_probe_disconnect();
    
    // [核心修复] 新增方法，处理来自Probe的高优先级、同步解冻请求，解决问题2（黑白屏）
    bool on_unfreeze_request(const json& payload);
    
    // 处理来自UI的配置变更
    bool on_config_changed_from_ui(const AppConfig& new_config);

    json get_dashboard_payload();
    json get_full_config_for_ui();
    json get_probe_config_payload();

private:
    void reconcile_process_state();
    void load_all_configs();

    std::string get_package_name_from_pid(int pid, int& uid, int& user_id);
    void add_pid_to_app(int pid, const std::string& package_name, int user_id, int uid);
    void remove_pid_from_app(int pid);
    
    bool transition_state(AppRuntimeState& app, AppRuntimeState::Status new_status, const std::string& reason);
    
    AppRuntimeState* get_or_create_app_state(const std::string& package_name, int user_id);
    
    // [核心修复] 新增方法和成员变量，用于检查是否为关键系统应用，解决问题1（系统卡死）
    bool is_critical_system_app(const std::string& package_name) const;

    std::shared_ptr<DatabaseManager> db_manager_;
    std::shared_ptr<SystemMonitor> sys_monitor_;
    std::shared_ptr<ActionExecutor> action_executor_;

    std::mutex state_mutex_;
    GlobalStatsData global_stats_;
    
    bool is_screen_on_ = true;

    // AppInstanceKey 现在是解决问题3（分身应用）的基础
    using AppInstanceKey = std::pair<std::string, int>; 
    std::map<AppInstanceKey, AppRuntimeState> managed_apps_;
    
    std::map<int, AppRuntimeState*> pid_to_app_map_;
    
    // [核心修复] 关键系统应用的安全列表 (Safety Net)
    std::unordered_set<std::string> critical_system_apps_;

    int probe_fd_ = -1; 
};

#endif //CERBERUS_STATE_MANAGER_H