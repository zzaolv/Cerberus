// daemon/cpp/state_manager.h
#ifndef CERBERUS_STATE_MANAGER_H
#define CERBERUS_STATE_MANAGER_H

#include "database_manager.h"
#include "system_monitor.h"
#include <nlohmann/json.hpp>
#include <string>
#include <vector>
#include <map>
#include <memory>
#include <mutex>

// 对应文档中的应用运行时状态
struct AppRuntimeState {
    std::string package_name;
    std::string app_name; // 应用名
    int uid;

    AppConfig config;

    // 运行时动态状态
    enum class Status {
        FOREGROUND, BACKGROUND_ACTIVE, BACKGROUND_IDLE, AWAITING_FREEZE, FROZEN, EXEMPTED
    } current_status = Status::BACKGROUND_IDLE;
    
    float cpu_usage_percent = 0.0f;
    long mem_usage_kb = 0;
};


class StateManager {
public:
    StateManager(std::shared_ptr<DatabaseManager> db_manager, std::shared_ptr<SystemMonitor> sys_monitor);

    void update_all_states();
    nlohmann::json get_dashboard_payload();

private:
    void refresh_installed_apps();

    std::shared_ptr<DatabaseManager> db_manager_;
    std::shared_ptr<SystemMonitor> sys_monitor_;

    std::mutex state_mutex_;
    GlobalStatsData global_stats_;
    std::map<std::string, AppRuntimeState> managed_apps_;
};

#endif //CERBERUS_STATE_MANAGER_H