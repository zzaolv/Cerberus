// daemon/cpp/state_manager.cpp
#include "state_manager.h"
#include <android/log.h>
#include <cstdio>
#include <memory>
#include <array>
#include <sstream>

#define LOG_TAG "cerberusd_state"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

std::string exec_shell(const char* cmd) {
    std::array<char, 256> buffer;
    std::string result;
    std::unique_ptr<FILE, decltype(&pclose)> pipe(popen(cmd, "r"), pclose);
    if (!pipe) {
        LOGE("popen() failed!");
        return "";
    }
    while (fgets(buffer.data(), buffer.size(), pipe.get()) != nullptr) {
        result += buffer.data();
    }
    return result;
}

StateManager::StateManager(std::shared_ptr<DatabaseManager> db_manager, std::shared_ptr<SystemMonitor> sys_monitor, std::shared_ptr<ActionExecutor> action_executor)
    : db_manager_(std::move(db_manager)), 
      sys_monitor_(std::move(sys_monitor)),
      action_executor_(std::move(action_executor)) {
    LOGI("StateManager initialized.");
    refresh_installed_apps();
}

void StateManager::transition_state(AppRuntimeState& app, AppRuntimeState::Status new_status) {
    if (app.current_status == new_status) return;
    // TODO: Add logging for state transitions
    app.current_status = new_status;
    app.last_state_change_time = std::chrono::steady_clock::now();
}

void StateManager::refresh_installed_apps() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    LOGI("Refreshing installed apps list...");
    
    managed_apps_.clear();
    std::string package_list_str = exec_shell("pm list packages -U -3");

    std::stringstream ss(package_list_str);
    std::string line;
    while (std::getline(ss, line, '\n')) {
        size_t pkg_pos = line.find("package:");
        size_t uid_pos = line.find(" uid:");
        if (uid_pos == std::string::npos || pkg_pos == std::string::npos) continue;

        std::string package_name = line.substr(pkg_pos + 8, uid_pos - (pkg_pos + 8));
        
        AppRuntimeState app_state;
        app_state.package_name = package_name;
        app_state.app_name = package_name; 
        app_state.uid = std::stoi(line.substr(uid_pos + 5));
        
        auto config_opt = db_manager_->get_app_config(package_name);
        if (config_opt) {
            app_state.config = *config_opt;
        } else {
            app_state.config.package_name = package_name;
            db_manager_->set_app_config(app_state.config);
        }
        
        transition_state(app_state, (app_state.config.policy == AppPolicy::EXEMPTED)
                                    ? AppRuntimeState::Status::EXEMPTED
                                    : AppRuntimeState::Status::BACKGROUND_IDLE);
        
        managed_apps_[package_name] = app_state;
    }
    LOGI("Found %zu third-party apps.", managed_apps_.size());
}

// 【新增】处理来自 Probe 的事件
void StateManager::on_app_killed(const std::string& package_name) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    auto it = managed_apps_.find(package_name);
    if (it != managed_apps_.end()) {
        LOGI("Event: App %s killed. Transitioning to BACKGROUND_IDLE.", package_name.c_str());
        // 任何时候进程被杀，都重置回后台空闲状态
        transition_state(it->second, AppRuntimeState::Status::BACKGROUND_IDLE);
    }
}
void StateManager::on_app_started(const std::string& package_name) {
     std::lock_guard<std::mutex> lock(state_mutex_);
    auto it = managed_apps_.find(package_name);
    if (it != managed_apps_.end()) {
        LOGI("Event: App %s started. Transitioning to FOREGROUND.", package_name.c_str());
        if(it->second.current_status == AppRuntimeState::Status::FROZEN){
            action_executor_->unfreeze_uid(it->second.uid);
        }
        transition_state(it->second, AppRuntimeState::Status::FOREGROUND);
    }
}

void StateManager::update_all_states() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    
    global_stats_ = sys_monitor_->get_stats();

    auto now = std::chrono::steady_clock::now();
    for (auto& [pkg, app] : managed_apps_) {
        // 【核心】状态机逻辑
        switch (app.current_status) {
            case AppRuntimeState::Status::BACKGROUND_IDLE: {
                auto elapsed_seconds = std::chrono::duration_cast<std::chrono::seconds>(now - app.last_state_change_time).count();
                // 简化：后台空闲超过 15 秒就准备冻结
                if (elapsed_seconds > 15) {
                    LOGI("State transition: %s from BACKGROUND_IDLE to AWAITING_FREEZE (timeout).", app.package_name.c_str());
                    transition_state(app, AppRuntimeState::Status::AWAITING_FREEZE);
                }
                break;
            }
            case AppRuntimeState::Status::AWAITING_FREEZE: {
                 auto elapsed_seconds = std::chrono::duration_cast<std::chrono::seconds>(now - app.last_state_change_time).count();
                // 等待期 5 秒
                 if (elapsed_seconds > 5) {
                    if (action_executor_->freeze_uid(app.uid)) {
                        LOGI("State transition: %s from AWAITING_FREEZE to FROZEN (action success).", app.package_name.c_str());
                        transition_state(app, AppRuntimeState::Status::FROZEN);
                    } else {
                        LOGW("Freeze action failed for %s. Reverting to BACKGROUND_IDLE.", app.package_name.c_str());
                        transition_state(app, AppRuntimeState::Status::BACKGROUND_IDLE);
                    }
                 }
                break;
            }
            // 其他状态...
            default:
                break;
        }
    }
}

std::string status_to_string(AppRuntimeState::Status status) {
    switch (status) {
        case AppRuntimeState::Status::FOREGROUND: return "FOREGROUND";
        case AppRuntimeState::Status::BACKGROUND_ACTIVE: return "BACKGROUND_ACTIVE";
        case AppRuntimeState::Status::BACKGROUND_IDLE: return "BACKGROUND_IDLE";
        case AppRuntimeState::Status::AWAITING_FREEZE: return "AWAITING_FREEZE";
        case AppRuntimeState::Status::FROZEN: return "FROZEN";
        case AppRuntimeState::Status::EXEMPTED: return "EXEMPTED";
    }
    return "UNKNOWN";
}

nlohmann::json StateManager::get_dashboard_payload() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    using json = nlohmann::json;

    json payload;
    payload["global_stats"] = {
        {"total_cpu_usage_percent", global_stats_.total_cpu_usage_percent},
        {"total_mem_kb", global_stats_.total_mem_kb},
        {"avail_mem_kb", global_stats_.avail_mem_kb},
        {"net_down_speed_bps", global_stats_.net_down_speed_bps},
        {"net_up_speed_bps", global_stats_.net_up_speed_bps},
        {"active_profile_name", "⚡️ 省电模式"}
    };
    
    json apps_state = json::array();
    for (const auto& [pkg, app] : managed_apps_) {
        json app_json;
        app_json["package_name"] = app.package_name;
        // Daemon 不再负责应用名
        app_json["app_name"] = app.package_name;
        app_json["display_status"] = status_to_string(app.current_status);
        app_json["active_freeze_mode"] = "CGROUP";
        app_json["mem_usage_kb"] = app.mem_usage_kb;
        app_json["cpu_usage_percent"] = app.cpu_usage_percent;
        app_json["is_whitelisted"] = (app.config.policy == AppPolicy::EXEMPTED);
        app_json["is_foreground"] = (app.current_status == AppRuntimeState::Status::FOREGROUND);
        apps_state.push_back(app_json);
    }
    payload["apps_runtime_state"] = apps_state;
    return payload;
}