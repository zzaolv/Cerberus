// daemon/cpp/state_manager.cpp
#include "state_manager.h"
#include "action_executor.h"
#include <android/log.h>
#include <cstdio>
#include <memory>
#include <array>
#include <sstream>
#include <filesystem>
#include <fstream>
#include <algorithm> // For std::all_of

#define LOG_TAG "cerberusd_state"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;

// 【核心修复】将 status_to_string_local 函数的定义移到文件顶部，并移除 StateManager:: 作用域
// 使其成为一个本文件内可见的静态辅助函数。
static std::string status_to_string_local(AppRuntimeState::Status status) {
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

int StateManager::get_pid_for_package(const std::string& package_name) {
    for (const auto& entry : fs::directory_iterator("/proc")) {
        if (!entry.is_directory()) continue;
        
        std::string pid_str = entry.path().filename().string();
        if (pid_str.empty() || !std::all_of(pid_str.begin(), pid_str.end(), ::isdigit)) {
            continue;
        }

        std::ifstream cmdline_file(entry.path() / "cmdline");
        if (cmdline_file.is_open()) {
            std::string cmdline;
            std::getline(cmdline_file, cmdline, '\0'); // 读取到第一个NUL字符
            if (!cmdline.empty() && cmdline == package_name) {
                try {
                    return std::stoi(pid_str);
                } catch (const std::invalid_argument&) {
                    return -1;
                }
            }
        }
    }
    return -1;
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
    
    // 现在调用 status_to_string_local 是安全的
    LOGI("State transition for %s: %s -> %s", 
         app.package_name.c_str(), 
         status_to_string_local(app.current_status).c_str(), 
         status_to_string_local(new_status).c_str());

    app.current_status = new_status;
    app.last_state_change_time = std::chrono::steady_clock::now();
}

void StateManager::refresh_installed_apps() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    LOGI("Refreshing installed apps list...");
    managed_apps_.clear();

    std::ifstream packages_file("/data/system/packages.list");
    if (!packages_file.is_open()) {
        LOGE("Failed to open /data/system/packages.list. Cannot get app list.");
        return;
    }

    std::string line;
    while (std::getline(packages_file, line)) {
        std::stringstream ss(line);
        std::string package_name, uid_str;
        
        ss >> package_name >> uid_str;
        if (package_name.empty() || uid_str.empty()) continue;

        try {
            int full_uid = std::stoi(uid_str);
            
            AppRuntimeState app_state;
            app_state.package_name = package_name;
            app_state.app_name = package_name;
            app_state.uid = full_uid;
            app_state.user_id = full_uid / 100000;

            auto config_opt = db_manager_->get_app_config(package_name);
            if (config_opt) {
                app_state.config = *config_opt;
            } else {
                app_state.config.package_name = package_name;
                db_manager_->set_app_config(app_state.config);
            }
            
            transition_state(app_state, AppRuntimeState::Status::STOPPED);
            managed_apps_[package_name] = app_state;

        } catch (const std::exception& e) {
            LOGW("Failed to parse line in packages.list: %s", line.c_str());
        }
    }
    LOGI("Found and loaded %zu installed apps from packages.list.", managed_apps_.size());
}

void StateManager::on_app_killed(const std::string& package_name) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    auto it = managed_apps_.find(package_name);
    if (it != managed_apps_.end()) {
        if(it->second.current_status == AppRuntimeState::Status::FROZEN){
             action_executor_->unfreeze_uid(it->second.uid);
        }
        transition_state(it->second, AppRuntimeState::Status::STOPPED);
        it->second.mem_usage_kb = 0;
        it->second.cpu_usage_percent = 0.0f;
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
            app.mem_usage_kb = 0;
            app.cpu_usage_percent = 0.0f;
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

nlohmann::json StateManager::get_dashboard_payload() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    using json = nlohmann::json;
    json payload;
    payload["global_stats"] = {
        {"total_cpu_usage_percent", global_stats_.total_cpu_usage_percent},
        {"total_mem_kb", global_stats_.total_mem_kb},
        {"avail_mem_kb", global_stats_.avail_mem_kb},
        {"swap_total_kb", global_stats_.swap_total_kb},
        {"swap_free_kb", global_stats_.swap_free_kb},
        {"active_profile_name", "⚡️ 省电模式"}
    };
    
    json apps_state = json::array();
    for (const auto& [pkg, app] : managed_apps_) {
        if (app.current_status != AppRuntimeState::Status::STOPPED || app.is_foreground_for_display) {
            json app_json;
            app_json["package_name"] = app.package_name;
            app_json["app_name"] = app.app_name;
            app_json["user_id"] = app.user_id;
            app_json["display_status"] = status_to_string_local(app.current_status);
            app_json["mem_usage_kb"] = app.mem_usage_kb;
            app_json["cpu_usage_percent"] = app.cpu_usage_percent;
            app_json["is_whitelisted"] = (app.config.policy == AppPolicy::EXEMPTED);
            app_json["is_foreground"] = (app.current_status == AppRuntimeState::Status::FOREGROUND);
            apps_state.push_back(app_json);
        }
    }
    payload["apps_runtime_state"] = apps_state;
    return payload;
}