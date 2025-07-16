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
#include <algorithm>
#include <sys/stat.h>

#define LOG_TAG "cerberusd_state"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;

constexpr int PER_USER_RANGE = 100000;

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


StateManager::StateManager(std::shared_ptr<DatabaseManager> db_manager, std::shared_ptr<SystemMonitor> sys_monitor, std::shared_ptr<ActionExecutor> action_executor)
    : db_manager_(std::move(db_manager)), 
      sys_monitor_(std::move(sys_monitor)),
      action_executor_(std::move(action_executor)) {
    LOGI("StateManager initialized.");
    refresh_installed_apps();
}

void StateManager::transition_state(AppRuntimeState& app, AppRuntimeState::Status new_status) {
    if (app.current_status == new_status) return;
    
    LOGI("State transition for %s (user %d): %s -> %s", 
         app.package_name.c_str(), app.user_id,
         status_to_string_local(app.current_status).c_str(), 
         status_to_string_local(new_status).c_str());

    app.current_status = new_status;
    app.last_state_change_time = std::chrono::steady_clock::now();
}

void StateManager::refresh_installed_apps() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    LOGI("Refreshing installed apps list...");
    managed_apps_.clear();
    app_is_system_map_.clear();

    std::ifstream packages_file("/data/system/packages.list");
    if (!packages_file.is_open()) {
        LOGE("Failed to open /data/system/packages.list. Cannot get app list.");
        return;
    }

    std::string line;
    while (std::getline(packages_file, line)) {
        std::stringstream ss(line);
        std::string package_name, app_id_str, seinfo, data_dir_path;
        
        ss >> package_name >> app_id_str >> seinfo >> data_dir_path;
        if (package_name.empty() || app_id_str.empty() || data_dir_path.empty()) continue;

        try {
            int app_id = std::stoi(app_id_str);
            int user_id = 0; // 只预加载主用户
            int full_uid = user_id * PER_USER_RANGE + app_id;

            // 【新增】通过数据目录路径判断是否为系统应用
            // 第三方应用路径通常以 /data/app/ 开头
            bool is_system = (data_dir_path.rfind("/data/app/", 0) != 0);
            app_is_system_map_[package_name] = is_system;

            AppRuntimeState app_state;
            app_state.package_name = package_name;
            app_state.app_name = package_name;
            app_state.uid = full_uid;
            app_state.user_id = user_id;

            auto config_opt = db_manager_->get_app_config(package_name);
            if (config_opt) {
                app_state.config = *config_opt;
            } else {
                app_state.config.package_name = package_name;
                db_manager_->set_app_config(app_state.config);
            }
            
            transition_state(app_state, AppRuntimeState::Status::STOPPED);
            managed_apps_[{package_name, user_id}] = app_state;

        } catch (const std::exception& e) {
            LOGW("Failed to parse line in packages.list: %s", line.c_str());
        }
    }
    LOGI("Pre-loaded %zu app instances for User 0.", managed_apps_.size());
}


void StateManager::build_process_cache() {
    uid_to_pids_map_.clear();
    for (const auto& entry : fs::directory_iterator("/proc")) {
        if (!entry.is_directory()) continue;

        std::string pid_str = entry.path().filename().string();
        if (pid_str.empty() || !std::all_of(pid_str.begin(), pid_str.end(), ::isdigit)) {
            continue;
        }

        struct stat st;
        if (stat(entry.path().c_str(), &st) == 0) {
            try {
                int pid = std::stoi(pid_str);
                uid_to_pids_map_[st.st_uid].push_back(pid);
            } catch (const std::exception&) {
                // Ignore conversion errors
            }
        }
    }
}

void StateManager::tick() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    auto now = std::chrono::steady_clock::now();

    build_process_cache();

    for (auto& [key, app] : managed_apps_) {
        if (app.uid == -1) continue;

        if (app.config.policy == AppPolicy::EXEMPTED) {
            transition_state(app, AppRuntimeState::Status::EXEMPTED);
            continue;
        }

        auto it = uid_to_pids_map_.find(app.uid);
        bool is_running = (it != uid_to_pids_map_.end() && !it->second.empty());

        if (!is_running) {
             if (app.current_status != AppRuntimeState::Status::STOPPED) {
                if (app.current_status == AppRuntimeState::Status::FROZEN) {
                    action_executor_->unfreeze_uid(app.uid);
                }
                transition_state(app, AppRuntimeState::Status::STOPPED);
                app.mem_usage_kb = 0;
                app.cpu_usage_percent = 0.0f;
                app.swap_usage_kb = 0;
            }
            continue;
        }

        if (app.current_status == AppRuntimeState::Status::STOPPED) {
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

void StateManager::update_resource_stats(bool update_user, bool update_system) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    
    sys_monitor_->update_all_stats();
    global_stats_ = sys_monitor_->get_stats();

    for (auto& [key, app] : managed_apps_) {
        if (app.current_status == AppRuntimeState::Status::STOPPED || app.current_status == AppRuntimeState::Status::EXEMPTED) {
            continue;
        }
        
        bool is_system = app_is_system_map_[app.package_name];
        if (!((is_system && update_system) || (!is_system && update_user))) {
            continue;
        }

        auto it = uid_to_pids_map_.find(app.uid);
        if (it == uid_to_pids_map_.end() || it->second.empty()) {
            continue;
        }
        
        int representative_pid = it->second.front();
        AppStatsData app_stats = sys_monitor_->get_app_stats(representative_pid);
        app.cpu_usage_percent = app_stats.cpu_usage_percent;
        app.mem_usage_kb = app_stats.mem_usage_kb;
        app.swap_usage_kb = app_stats.swap_usage_kb;
    }
}


void StateManager::handle_app_event(const std::string& package_name, int user_id, bool is_start) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    AppInstanceKey key = {package_name, user_id};
    auto it = managed_apps_.find(key);

    if (it == managed_apps_.end()) {
        if (!is_start) {
            LOGI("Ignoring kill event for unknown instance %s (user %d)", package_name.c_str(), user_id);
            return;
        }

        LOGI("Detected new app instance: %s (user %d). Creating state dynamically.", package_name.c_str(), user_id);
        
        AppRuntimeState new_app_state;
        new_app_state.package_name = package_name;
        new_app_state.app_name = package_name;
        new_app_state.user_id = user_id;

        auto main_user_it = managed_apps_.find({package_name, 0});
        if (main_user_it != managed_apps_.end()) {
            int app_id = main_user_it->second.uid % PER_USER_RANGE;
            new_app_state.uid = user_id * PER_USER_RANGE + app_id;
        } else {
            LOGW("Cannot determine UID for clone app %s, as main app is not found.", package_name.c_str());
            new_app_state.uid = -1; 
        }

        auto config_opt = db_manager_->get_app_config(package_name);
        if (config_opt) {
            new_app_state.config = *config_opt;
        } else {
            new_app_state.config.package_name = package_name;
            db_manager_->set_app_config(new_app_state.config);
        }

        auto result = managed_apps_.emplace(key, new_app_state);
        it = result.first;
    }

    AppRuntimeState& app = it->second;

    if (is_start) {
        if (app.current_status == AppRuntimeState::Status::FROZEN) {
            action_executor_->unfreeze_uid(app.uid);
        }
        transition_state(app, AppRuntimeState::Status::FOREGROUND);
    } else { 
        if (app.current_status == AppRuntimeState::Status::FROZEN) {
            action_executor_->unfreeze_uid(app.uid);
        }
        transition_state(app, AppRuntimeState::Status::STOPPED);
        app.mem_usage_kb = 0;
        app.cpu_usage_percent = 0.0f;
        app.swap_usage_kb = 0;
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
    for (const auto& [key, app] : managed_apps_) {
        if (app.current_status != AppRuntimeState::Status::STOPPED) {
            json app_json;
            app_json["package_name"] = app.package_name;
            app_json["app_name"] = app.app_name;
            app_json["user_id"] = app.user_id;
            app_json["display_status"] = status_to_string_local(app.current_status);
            app_json["mem_usage_kb"] = app.mem_usage_kb;
            app_json["swap_usage_kb"] = app.swap_usage_kb;
            app_json["cpu_usage_percent"] = app.cpu_usage_percent;
            app_json["is_whitelisted"] = (app.config.policy == AppPolicy::EXEMPTED);
            app_json["is_foreground"] = (app.current_status == AppRuntimeState::Status::FOREGROUND);
            apps_state.push_back(app_json);
        }
    }
    payload["apps_runtime_state"] = apps_state;
    return payload;
}


int StateManager::get_pid_for_app_instance(int target_uid) {
    for (const auto& entry : fs::directory_iterator("/proc")) {
        if (!entry.is_directory()) continue;
        
        std::string pid_str = entry.path().filename().string();
        if (pid_str.empty() || !std::all_of(pid_str.begin(), pid_str.end(), ::isdigit)) {
            continue;
        }

        struct stat st;
        if (stat(entry.path().c_str(), &st) == 0) {
            if (st.st_uid == target_uid) {
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