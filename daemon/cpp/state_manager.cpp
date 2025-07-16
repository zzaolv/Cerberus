// daemon/cpp/state_manager.cpp
#include "state_manager.h"
#include "action_executor.h"
#include <android/log.h>
#include <cstdio>
#include <memory>
#include <array>
#include <sstream>

#define LOG_TAG "cerberusd_state"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

std::string exec_shell_local_sm(const char* cmd) {
    std::array<char, 256> buffer;
    std::string result;
    std::unique_ptr<FILE, decltype(&pclose)> pipe(popen(cmd, "r"), pclose);
    if (!pipe) { return ""; }
    while (fgets(buffer.data(), buffer.size(), pipe.get()) != nullptr) {
        result += buffer.data();
    }
    return result;
}

int StateManager::get_pid_for_package(const std::string& package_name) {
    std::string cmd = "pidof -s " + package_name;
    std::string pid_str = exec_shell_local_sm(cmd.c_str());
    if (pid_str.empty()) return -1;
    try {
        return std::stoi(pid_str);
    } catch (const std::exception&) {
        return -1;
    }
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
    app.current_status = new_status;
    app.last_state_change_time = std::chrono::steady_clock::now();
}

void StateManager::refresh_installed_apps() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    LOGI("Refreshing installed apps list...");
    managed_apps_.clear();
    std::string package_list_str = exec_shell_local_sm("pm list packages -U");

    std::stringstream ss(package_list_str);
    std::string line;
    while (std::getline(ss, line, '\n')) {
        size_t pkg_pos = line.find("package:");
        size_t uid_pos = line.find(" uid:");
        if (uid_pos == std::string::npos || pkg_pos == std::string::npos) continue;

        std::string package_name = line.substr(pkg_pos + 8, uid_pos - (pkg_pos + 8));
        int full_uid = std::stoi(line.substr(uid_pos + 5));
        
        AppRuntimeState app_state;
        app_state.package_name = package_name;
        app_state.app_name = package_name; 
        app_state.uid = full_uid;
        app_state.user_id = full_uid / 100000;

        auto config_opt = db_manager_->get_app_config(package_name);
        if (config_opt) app_state.config = *config_opt;
        else {
            app_state.config.package_name = package_name;
            db_manager_->set_app_config(app_state.config);
        }
        
        transition_state(app_state, AppRuntimeState::Status::STOPPED);
        managed_apps_[package_name] = app_state;
    }
    LOGI("Found %zu installed apps.", managed_apps_.size());
}

void StateManager::on_app_killed(const std::string& package_name) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    auto it = managed_apps_.find(package_name);
    if (it != managed_apps_.end()) {
        if(it->second.current_status == AppRuntimeState::Status::FROZEN){
             action_executor_->unfreeze_uid(it->second.uid);
        }
        transition_state(it->second, AppRuntimeState::Status::STOPPED);
    }
}

void StateManager::on_app_started(const std::string& package_name) {
     std::lock_guard<std::mutex> lock(state_mutex_);
    auto it = managed_apps_.find(package_name);
    if (it != managed_apps_.end()) {
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
        if (app.config.policy == AppPolicy::EXEMPTED) {
            transition_state(app, AppRuntimeState::Status::EXEMPTED);
            continue;
        }

        int pid = get_pid_for_package(pkg);

        if (pid <= 0) {
            if (app.current_status == AppRuntimeState::Status::FROZEN) {
                action_executor_->unfreeze_uid(app.uid);
            }
            transition_state(app, AppRuntimeState::Status::STOPPED);
            app.mem_usage_kb = 0;
            app.cpu_usage_percent = 0.0f;
            continue;
        }
        
        AppStatsData app_stats = sys_monitor_->get_app_stats(pid);
        app.cpu_usage_percent = app_stats.cpu_usage_percent;
        app.mem_usage_kb = app_stats.mem_usage_kb;

        if(app.current_status == AppRuntimeState::Status::STOPPED){
            transition_state(app, AppRuntimeState::Status::BACKGROUND_IDLE);
        }
        
        switch (app.current_status) {
            case AppRuntimeState::Status::BACKGROUND_IDLE: {
                auto elapsed_seconds = std::chrono::duration_cast<std::chrono::seconds>(now - app.last_state_change_time).count();
                if (elapsed_seconds > 15) {
                    transition_state(app, AppRuntimeState::Status::AWAITING_FREEZE);
                }
                break;
            }
            case AppRuntimeState::Status::AWAITING_FREEZE: {
                 auto elapsed_seconds = std::chrono::duration_cast<std::chrono::seconds>(now - app.last_state_change_time).count();
                 if (elapsed_seconds > 5) {
                    if (action_executor_->freeze_uid(app.uid)) {
                        transition_state(app, AppRuntimeState::Status::FROZEN);
                    } else {
                        transition_state(app, AppRuntimeState::Status::BACKGROUND_IDLE);
                    }
                 }
                break;
            }
            default:
                break;
        }
    }
}

std::string status_to_string(AppRuntimeState::Status status) {
    switch (status) {
        case AppRuntimeState::Status::STOPPED: return "STOPPED";
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
        {"active_profile_name", "⚡️ 省电模式"}
    };
    
    json apps_state = json::array();
    for (const auto& [pkg, app] : managed_apps_) {
        json app_json;
        app_json["package_name"] = app.package_name;
        app_json["app_name"] = app.app_name;
        app_json["user_id"] = app.user_id;
        app_json["display_status"] = status_to_string(app.current_status);
        app_json["mem_usage_kb"] = app.mem_usage_kb;
        app_json["cpu_usage_percent"] = app.cpu_usage_percent;
        app_json["is_whitelisted"] = (app.config.policy == AppPolicy::EXEMPTED);
        app_json["is_foreground"] = (app.current_status == AppRuntimeState::Status::FOREGROUND);
        apps_state.push_back(app_json);
    }
    payload["apps_runtime_state"] = apps_state;
    return payload;
}