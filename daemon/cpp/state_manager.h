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
#include <set>
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
    time_t observation_since = 0;
    time_t undetected_since = 0;

    float cpu_usage_percent = 0.0f;
    long mem_usage_kb = 0;
    long swap_usage_kb = 0;
};

class StateManager {
public:
    StateManager(std::shared_ptr<DatabaseManager>, std::shared_ptr<SystemMonitor>, std::shared_ptr<ActionExecutor>);
    
    bool tick_state_machine();
    bool perform_deep_scan();
    bool update_foreground_state(const std::set<int>& top_pids);
    bool on_config_changed_from_ui(const json& payload);
    void update_master_config(const MasterConfig& config);
    json get_dashboard_payload();
    json get_full_config_for_ui();
    json get_probe_config_payload();

    // [旧接口，保持不变]
    void on_wakeup_request(const json& payload);
    
    // --- [新接口] ---
    void on_temp_unfreeze_request_by_pkg(const json& payload);
    void on_temp_unfreeze_request_by_uid(const json& payload);
    void on_temp_unfreeze_request_by_pid(const json& payload);
    MasterConfig get_master_config();


private:
    bool reconcile_process_state_full(); 
    void load_all_configs();
    std::string get_package_name_from_pid(int pid, int& uid, int& user_id);
    void add_pid_to_app(int pid, const std::string&, int user_id, int uid);
    void remove_pid_from_app(int pid);
    AppRuntimeState* get_or_create_app_state(const std::string&, int user_id);
    bool is_critical_system_app(const std::string&) const;
    
    // 内部通用解冻逻辑
    void unfreeze_and_observe(AppRuntimeState& app, const std::string& reason);

    bool is_app_playing_audio(const AppRuntimeState& app);
    void schedule_timed_unfreeze(AppRuntimeState& app);
    bool check_timed_unfreeze();
    bool check_timers();

    std::shared_ptr<DatabaseManager> db_manager_;
    std::shared_ptr<SystemMonitor> sys_monitor_;
    std::shared_ptr<ActionExecutor> action_executor_;
    MasterConfig master_config_;

    std::mutex state_mutex_;
    std::set<int> last_known_top_pids_;
    
    GlobalStatsData global_stats_;
    FreezeMethod default_freeze_method_ = FreezeMethod::METHOD_SIGSTOP;

    uint32_t timeline_idx_ = 0;
    std::vector<int> unfrozen_timeline_;

    using AppInstanceKey = std::pair<std::string, int>; 
    std::map<AppInstanceKey, AppRuntimeState> managed_apps_;
    std::map<int, AppRuntimeState*> pid_to_app_map_;
    std::unordered_set<std::string> critical_system_apps_;
};

#endif //CERBERUS_STATE_MANAGER_H