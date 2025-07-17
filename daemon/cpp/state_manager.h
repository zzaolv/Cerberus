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
#include <unordered_map>
#include "system_monitor.h"
#include "database_manager.h"
#include "process_monitor.h"
#include "action_executor.h"

class ActionExecutor;

struct AppRuntimeState {
    std::string package_name;
    std::string app_name;
    int uid = -1;
    int user_id;
    std::vector<int> pids; 

    AppConfig config;

    enum class Status {
        STOPPED, FOREGROUND, BACKGROUND_ACTIVE, BACKGROUND_IDLE, AWAITING_FREEZE, FROZEN, EXEMPTED
    } current_status = Status::STOPPED;

    std::chrono::steady_clock::time_point last_state_change_time;
    std::chrono::steady_clock::time_point active_session_start_time;
    
    float cpu_usage_percent = 0.0f;
    long mem_usage_kb = 0;
    long swap_usage_kb = 0;

    bool has_notification = false;
    bool has_network_activity = false;
    bool is_network_blocked = false;
    long long last_rx_bytes = 0;
    long long last_tx_bytes = 0;
};

enum class DozeState {
    ACTIVE,
    IDLE,
    DEEP_IDLE,
};

class StateManager {
public:
    StateManager(std::shared_ptr<DatabaseManager> db_manager, std::shared_ptr<SystemMonitor> sys_monitor, std::shared_ptr<ActionExecutor> action_executor);

    void set_freezer_type(FreezerType type);
    void set_periodic_unfreeze_interval(int minutes);

    void process_event_handler(ProcessEventType type, int pid, int ppid);
    void handle_probe_event(const nlohmann::json& event);

    void tick();
    void update_all_resource_stats();
    nlohmann::json get_dashboard_payload();

    void update_app_config_from_ui(const AppConfig& new_config);
    const std::unordered_set<std::string>& get_safety_net_list() const;

private:
    void tick_app_states();
    void tick_doze_state();
    void tick_power_state();

    void handle_doze_event(const nlohmann::json& payload);
    void transition_doze_state(DozeState new_state, const std::string& reason);
    void enter_deep_doze_actions();
    void exit_deep_doze_actions();
    void start_doze_resource_snapshot();
    void generate_doze_exit_report();

    void initial_scan();
    void refresh_app_list_from_db();
    std::string get_package_name_from_pid(int pid, int& uid, int& user_id);
    void add_pid_to_app(int pid, const std::string& package_name, int user_id, int uid);
    void remove_pid_from_app(int pid);
    void transition_state(AppRuntimeState& app, AppRuntimeState::Status new_status, const std::string& reason);
    void check_and_update_foreground_status();
    void check_system_events();
    AppRuntimeState* find_app_by_pid(int pid);
    AppRuntimeState* get_or_create_app_state(const std::string& package_name, int user_id);
    bool is_critical_system_app(const std::string& package_name) const;

    std::shared_ptr<DatabaseManager> db_manager_;
    std::shared_ptr<SystemMonitor> sys_monitor_;
    std::shared_ptr<ActionExecutor> action_executor_;

    std::mutex state_mutex_;
    GlobalStatsData global_stats_;
    std::optional<BatteryStats> battery_stats_;
    bool is_screen_on_ = true;

    std::atomic<FreezerType> current_freezer_type_ = FreezerType::AUTO;
    std::atomic<int> periodic_unfreeze_interval_min_ = 0;

    DozeState doze_state_ = DozeState::ACTIVE;
    std::chrono::steady_clock::time_point last_doze_state_change_time_;
    std::unordered_map<int, long long> doze_cpu_snapshot_;

    std::chrono::steady_clock::time_point last_power_check_time_;
    int last_capacity_ = -1;

    using AppInstanceKey = std::pair<std::string, int>;
    std::map<AppInstanceKey, AppRuntimeState> managed_apps_;
    std::map<int, AppRuntimeState*> pid_to_app_map_;
    std::unordered_set<std::string> critical_system_apps_;
};

#endif //CERBERUS_STATE_MANAGER_H