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
#include <chrono> 
#include "database_manager.h"
#include "system_monitor.h"
#include "action_executor.h"
#include "logger.h"                 
#include "time_series_database.h"   

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
    int freeze_retry_count = 0;

    bool has_rogue_structure = false;
    int rogue_puppet_pid = -1;
    int rogue_master_pid = -1;
    
    bool has_logged_rogue_warning = false;

    int scheduled_unfreeze_idx = -1;

    float cpu_usage_percent = 0.0f;
    long mem_usage_kb = 0;
    long swap_usage_kb = 0;
    long long last_foreground_timestamp_ms = 0;
    long long total_runtime_ms = 0;    
};

class DozeManager {
public:
    enum class State { AWAKE, IDLE, INACTIVE, DEEP_DOZE };
    enum class DozeEvent { NONE, ENTERED_DEEP_DOZE, EXITED_DEEP_DOZE };
    
    DozeManager(std::shared_ptr<Logger> logger, std::shared_ptr<ActionExecutor> executor);
    DozeEvent process_metrics(const MetricsRecord& record);

private:
    void enter_state(State new_state, const MetricsRecord& record);
    
    State current_state_ = State::AWAKE;
    std::chrono::steady_clock::time_point state_change_timestamp_;
    std::chrono::steady_clock::time_point deep_doze_start_time_;
    std::shared_ptr<Logger> logger_;
    std::shared_ptr<ActionExecutor> action_executor_;
};

class StateManager {
public:
    StateManager(std::shared_ptr<DatabaseManager>, std::shared_ptr<SystemMonitor>, std::shared_ptr<ActionExecutor>,
                 std::shared_ptr<Logger>, std::shared_ptr<TimeSeriesDatabase>);
    
    bool evaluate_and_execute_strategy();

    void process_new_metrics(const MetricsRecord& record);
    bool tick_state_machine();
    bool perform_deep_scan();
    bool on_config_changed_from_ui(const json& payload);
    void update_master_config(const MasterConfig& config);
    json get_dashboard_payload();
    json get_full_config_for_ui();
    json get_probe_config_payload();
    void on_wakeup_request(const json& payload);
    
    // [核心修复] 新增主动解冻接口
    void on_proactive_unfreeze_request(const json& payload);

    void on_temp_unfreeze_request_by_pkg(const json& payload);
    void on_temp_unfreeze_request_by_uid(const json& payload);
    void on_temp_unfreeze_request_by_pid(const json& payload);
    // [核心架构] 添加处理前后台事件的接口
    void on_app_foreground_event(const json& payload);
    void on_app_background_event(const json& payload);    

private:
    void handle_charging_state_change(const MetricsRecord& old_record, const MetricsRecord& new_record);
    void generate_doze_exit_report();
    void analyze_battery_change(const MetricsRecord& old_record, const MetricsRecord& new_record);
    bool unfreeze_and_observe_nolock(AppRuntimeState& app, const std::string& reason);
    bool reconcile_process_state_full(); 
    void load_all_configs();
    std::string get_package_name_from_pid(int pid, int& uid, int& user_id);
    void add_pid_to_app(int pid, const std::string&, int user_id, int uid);
    void remove_pid_from_app(int pid);
    AppRuntimeState* get_or_create_app_state(const std::string&, int user_id);
    bool is_critical_system_app(const std::string&) const;
    bool is_app_playing_audio(const AppRuntimeState& app);
    void schedule_timed_unfreeze(AppRuntimeState& app);
    bool check_timed_unfreeze();
    void cancel_timed_unfreeze(AppRuntimeState& app);
    bool check_timers();

    bool update_foreground_state(const std::set<AppInstanceKey>& visible_app_keys);
    void audit_app_structures(const std::map<int, ProcessInfo>& process_tree);

    std::shared_ptr<DatabaseManager> db_manager_;
    std::shared_ptr<SystemMonitor> sys_monitor_;
    std::shared_ptr<ActionExecutor> action_executor_;
    std::shared_ptr<Logger> logger_;
    std::shared_ptr<TimeSeriesDatabase> ts_db_;
    
    MasterConfig master_config_;
    std::unique_ptr<DozeManager> doze_manager_;

    std::mutex state_mutex_;
    std::set<AppInstanceKey> last_known_visible_app_keys_;
    
    std::optional<MetricsRecord> last_metrics_record_;
    std::optional<std::pair<int, long long>> last_battery_level_info_; 
    
    uint32_t timeline_idx_ = 0;
    std::vector<int> unfrozen_timeline_;
    
    std::map<AppInstanceKey, long long> doze_start_cpu_jiffies_;
    
    std::map<AppInstanceKey, AppRuntimeState> managed_apps_;
    std::map<int, AppRuntimeState*> pid_to_app_map_;
    std::unordered_set<std::string> critical_system_apps_;
};

#endif //CERBERUS_STATE_MANAGER_H