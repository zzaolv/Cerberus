// daemon/cpp/state_manager.h
#ifndef CERBERUS_STATE_MANAGER_H
#define CERBERUS_STATE_MANAGER_H

#include <nlohmann/json.hpp>
#include <string>
#include <vector>
#include <map>
#include <memory>
#include <mutex>
#include "database_manager.h"
#include "system_monitor.h"
#include <unordered_set>

class ActionExecutor;
using json = nlohmann::json;

struct AppRuntimeState {
    std::string package_name;
    std::string app_name;
    int uid = -1;
    int user_id = 0;
    std::vector<int> pids; 
    AppConfig config;
    enum class Status { STOPPED, RUNNING, FROZEN, EXEMPTED } current_status = Status::STOPPED;
    bool is_foreground = false; // Kept for UI display
    // Resource usage stats
    float cpu_usage_percent = 0.0f;
    long mem_usage_kb = 0;
    long swap_usage_kb = 0;
};

class StateManager {
public:
    StateManager(std::shared_ptr<DatabaseManager>, std::shared_ptr<SystemMonitor>, std::shared_ptr<ActionExecutor>);

    // Lightweight maintenance task (finds dead pids, updates stats)
    bool tick();
    
    // Handles config changes from UI. Returns true if a probe config update is needed.
    bool on_config_changed_from_ui(const json& payload);

    // [NEW] Handles explicit freeze/unfreeze commands from the Probe
    bool on_freeze_request_from_probe(const json& payload);
    bool on_unfreeze_request_from_probe(const json& payload);

    // Get payloads for UI and Probe
    json get_dashboard_payload();
    json get_full_config_for_ui();
    json get_probe_config_payload();

private:
    void reconcile_process_state();
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
    
    using AppInstanceKey = std::pair<std::string, int>; 
    std::map<AppInstanceKey, AppRuntimeState> managed_apps_;
    std::map<int, AppRuntimeState*> pid_to_app_map_;
    std::unordered_set<std::string> critical_system_apps_;
};

#endif //CERBERUS_STATE_MANAGER_H