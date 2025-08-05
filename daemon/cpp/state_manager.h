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
#include "rekernel_client.h"

using json = nlohmann::json;

// [核心修改] WakeupType 替换为更通用的 WakeupPolicy
enum class WakeupPolicy {
    // 决策结果
    IGNORE,                       // 忽略事件, 不解冻
    SHORT_OBSERVATION,            // 解冻并给予短观察期 (e.g., 3s)
    STANDARD_OBSERVATION,         // 解冻并给予标准观察期 (e.g., 10s)
    LONG_OBSERVATION,             // 解冻并给予长观察期 (e.g., 20s)
    UNFREEZE_UNTIL_BACKGROUND,    // 解冻, 直到下次被判定为后台

    // 事件类型 (用于传递信息)
    FROM_NOTIFICATION,            // 来自普通通知
    FROM_FCM,                     // 来自FCM推送
    FROM_PROBE_START,             // 来自Probe探测到的应用启动
    FROM_KERNEL                   // 来自内核事件 (统一归类)
};

struct AppRuntimeState {
    enum class Status {
        STOPPED,
        RUNNING,
        FROZEN
    } current_status = Status::STOPPED;

    enum class FreezeMethod {
        NONE,
        CGROUP,
        SIG_STOP
    } freeze_method = FreezeMethod::NONE;

    std::string package_name;
    std::string app_name;
    int uid = -1;
    int user_id = 0;
    std::vector<int> pids;
    AppConfig config;
    bool is_oom_protected = false;

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

    // [修改] 节流阀相关字段
    time_t last_wakeup_timestamp = 0; // 记录上一次有效唤醒尝试的时间窗口
    int wakeup_count_in_window = 0;   // 记录在时间窗口内的唤醒次数
    // [新增] 上次成功唤醒的时间戳，用于事件突发节流（消抖）
    time_t last_successful_wakeup_timestamp = 0;
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

// [核心重构] 为Doze报告增加一个专门的结构体
struct DozeProcessRecord {
    long long start_jiffies;
    std::string process_name;
    std::string package_name;
    int user_id;
};

class StateManager {
public:
    StateManager(std::shared_ptr<DatabaseManager>, std::shared_ptr<SystemMonitor>, std::shared_ptr<ActionExecutor>,
                 std::shared_ptr<Logger>, std::shared_ptr<TimeSeriesDatabase>);

    void initial_full_scan_and_warmup();
    bool evaluate_and_execute_strategy();
    bool handle_top_app_change_fast();
    void process_new_metrics(const MetricsRecord& record);
    bool tick_state_machine();
    bool perform_deep_scan();
    bool on_config_changed_from_ui(const json& payload);
    void update_master_config(const MasterConfig& config);
    json get_dashboard_payload();
    json get_full_config_for_ui();
    json get_probe_config_payload();
    void on_app_foreground_event(const json& payload);
    void on_app_background_event(const json& payload);
    void on_proactive_unfreeze_request(const json& payload);
    void on_wakeup_request(const json& payload);
    void on_temp_unfreeze_request_by_pkg(const json& payload);
    void on_temp_unfreeze_request_by_uid(const json& payload);
    void on_temp_unfreeze_request_by_pid(const json& payload);
    bool perform_staggered_stats_scan();
    void on_wakeup_request_from_probe(const json& payload);
    // [新增] 处理来自 Re-Kernel 的事件
    void on_signal_from_rekernel(const ReKernelSignalEvent& event);
    void on_binder_from_rekernel(const ReKernelBinderEvent& event);

private:
    void handle_charging_state_change(const MetricsRecord& old_record, const MetricsRecord& new_record);
    void generate_doze_exit_report();
    void analyze_battery_change(const MetricsRecord& old_record, const MetricsRecord& new_record);
    bool unfreeze_and_observe_nolock(AppRuntimeState& app, const std::string& reason, WakeupPolicy policy);
    bool reconcile_process_state_full();
    void load_all_configs();
    std::string get_package_name_from_pid(int pid, int& uid, int& user_id);
    void add_pid_to_app(int pid, const std::string&, int user_id, int uid);
    void remove_pid_from_app(int pid);
    AppRuntimeState* get_or_create_app_state(const std::string&, int user_id);
    bool is_critical_system_app(const std::string&) const;
    bool is_app_playing_audio(const AppRuntimeState& app);
    // [新增] 决策函数
    WakeupPolicy decide_wakeup_policy_for_probe(WakeupPolicy event_type);
    WakeupPolicy decide_wakeup_policy_for_kernel(const ReKernelSignalEvent& event);
    WakeupPolicy decide_wakeup_policy_for_kernel(const ReKernelBinderEvent& event);
    void schedule_timed_unfreeze(AppRuntimeState& app);
    bool check_timed_unfreeze();
    void cancel_timed_unfreeze(AppRuntimeState& app);
    bool check_timers();
    bool update_foreground_state_from_pids(const std::set<int>& top_pids);
    bool update_foreground_state(const std::set<AppInstanceKey>& visible_app_keys);
    void audit_app_structures(const std::map<int, ProcessInfo>& process_tree);
    void validate_pids_nolock(AppRuntimeState& app);

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

    // [新增] 遥测统计
    std::map<int, int> kernel_wakeup_source_stats_; // key: source_uid, value: count
    std::map<std::string, int> ignored_rpc_stats_;   // key: rpc_name, value: count

    // [核心重构] 修改Doze数据结构，以PID为key
    std::map<int, DozeProcessRecord> doze_start_process_info_;

    std::map<AppInstanceKey, AppRuntimeState> managed_apps_;
    std::map<int, AppRuntimeState*> pid_to_app_map_;
    std::unordered_set<std::string> critical_system_apps_;
    std::map<AppInstanceKey, AppRuntimeState>::iterator next_scan_iterator_;
};

#endif //CERBERUS_STATE_MANAGER_H