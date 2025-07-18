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

// 应用的完整运行时状态
struct AppRuntimeState {
    std::string package_name;
    std::string app_name; // 应用名，用于UI显示
    int uid = -1;
    int user_id = 0;
    std::vector<int> pids; 

    AppConfig config; // 从数据库加载的或默认的配置

    enum class Status {
        STOPPED,          // 无运行中进程
        FOREGROUND,       // 前台运行
        BACKGROUND_IDLE,  // 后台空闲 (等待超时)
        AWAITING_FREEZE,  // 等待冻结 (宽限期)
        FROZEN,           // 已冻结
        EXEMPTED          // 豁免 (硬性安全网或用户配置)
    } current_status = Status::STOPPED;

    std::chrono::steady_clock::time_point last_state_change_time;

    // 资源占用统计
    float cpu_usage_percent = 0.0f;
    long mem_usage_kb = 0;
    long swap_usage_kb = 0;
};

class StateManager {
public:
    StateManager(std::shared_ptr<DatabaseManager> db, 
                 std::shared_ptr<SystemMonitor> sys, 
                 std::shared_ptr<ActionExecutor> act);

    // 事件处理入口
    void handle_process_event(ProcessEventType type, int pid, int ppid);
    void handle_probe_event(const nlohmann::json& event);

    // 定时任务，由主线程循环调用
    void tick(); 
    
    // UI 数据生成与指令处理
    nlohmann::json get_dashboard_payload();

    // [FIX] Corrected function declaration to match implementation.
    void update_app_config_from_ui(const AppConfig& new_config, int user_id);
    
    nlohmann::json get_full_config_for_ui();

private:
    void initial_process_scan();
    void load_all_configs();
    
    std::string get_package_name_from_pid(int pid, int& uid, int& user_id);
    void add_pid_to_app(int pid, const std::string& package_name, int user_id, int uid);
    void remove_pid_from_app(int pid);
    
    void transition_state(AppRuntimeState& app, AppRuntimeState::Status new_status, const std::string& reason);
    void check_foreground_app();

    AppRuntimeState* find_app_by_pid(int pid);
    AppRuntimeState* get_or_create_app_state(const std::string& package_name, int user_id);

    bool is_critical_system_app(const std::string& package_name) const;

    std::shared_ptr<DatabaseManager> db_manager_;
    std::shared_ptr<SystemMonitor> sys_monitor_;
    std::shared_ptr<ActionExecutor> action_executor_;

    std::mutex state_mutex_;
    GlobalStatsData global_stats_;

    using AppInstanceKey = std::pair<std::string, int>; 
    std::map<AppInstanceKey, AppRuntimeState> managed_apps_;
    
    std::map<int, AppRuntimeState*> pid_to_app_map_;
    
    std::unordered_set<std::string> critical_system_apps_;
    
    std::string foreground_package_;
    int foreground_uid_ = -1;
};

#endif //CERBERUS_STATE_MANAGER_H