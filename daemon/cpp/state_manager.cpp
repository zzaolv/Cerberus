// daemon/cpp/state_manager.cpp
#include "state_manager.h"
#include "action_executor.h"
#include <android/log.h>
#include <filesystem>
#include <fstream>
#include <string>
#include <sstream>
#include <algorithm>
#include <sys/stat.h>
#include <unistd.h>
#include <set>

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

void read_pids_from_cgroup_tasks(const std::string& path, std::vector<int>& pids) {
    std::ifstream file(path);
    if (!file.is_open()) return;
    int pid;
    while (file >> pid) pids.push_back(pid);
}

// 【优化】更健壮的包名解析，处理 _u999 这样的后缀
std::string parse_package_name_from_cgroup_line(const std::string& line) {
    auto pos = line.find("apps/");
    if (pos == std::string::npos) return "";

    std::string sub = line.substr(pos + 5);
    auto end_pos = sub.find('/');
    if (end_pos != std::string::npos) {
        sub = sub.substr(0, end_pos);
    }
    
    // 移除可能的 _uXXX 后缀
    auto suffix_pos = sub.rfind("_u");
    if (suffix_pos != std::string::npos) {
        // 确保后面是数字
        bool is_user_suffix = true;
        for(size_t i = suffix_pos + 2; i < sub.length(); ++i) {
            if(!isdigit(sub[i])) {
                is_user_suffix = false;
                break;
            }
        }
        if(is_user_suffix) {
            sub = sub.substr(0, suffix_pos);
        }
    }

    // 检查是否是有效的包名（简单检查）
    if (sub.find('.') == std::string::npos) return "";

    return sub;
}


void StateManager::build_process_cache_from_cgroups() {
    process_info_cache_.clear();
    std::vector<int> pids;
    read_pids_from_cgroup_tasks("/dev/cpuset/foreground/tasks", pids);
    read_pids_from_cgroup_tasks("/dev/cpuset/background/tasks", pids);
    // 使用set去重
    std::set<int> unique_pids(pids.begin(), pids.end());

    for (int pid : unique_pids) {
        struct stat st;
        std::string proc_path_str = "/proc/" + std::to_string(pid);
        if (stat(proc_path_str.c_str(), &st) != 0 || st.st_uid < 10000) continue;

        std::ifstream cgroup_file(proc_path_str + "/cgroup");
        if (!cgroup_file.is_open()) continue;

        std::string line, package_name;
        while (std::getline(cgroup_file, line)) {
            package_name = parse_package_name_from_cgroup_line(line);
            if (!package_name.empty()) break;
        }

        if (package_name.empty()) continue;

        bool is_foreground = fs::exists("/dev/cpuset/foreground/tasks") && 
                             std::ifstream("/dev/cpuset/foreground/tasks").peek() != EOF &&
                             find(pids.begin(), pids.end(), pid) != pids.end();
        CgroupState cgroup_state = is_foreground ? CgroupState::FOREGROUND : CgroupState::BACKGROUND;
        
        process_info_cache_[pid] = {pid, static_cast<int>(st.st_uid), package_name, cgroup_state};
    }
}

AppRuntimeState* StateManager::get_or_create_app_state(const std::string& package_name, int user_id) {
    AppInstanceKey key = {package_name, user_id};
    auto it = managed_apps_.find(key);
    if (it != managed_apps_.end()) return &it->second;

    LOGI("Dynamically discovered new instance: %s (user %d). Creating state.", package_name.c_str(), user_id);
    AppRuntimeState new_app_state;
    new_app_state.package_name = package_name;
    new_app_state.user_id = user_id;
    new_app_state.app_name = package_name;

    auto main_app_it = managed_apps_.find({package_name, 0});
    if (main_app_it != managed_apps_.end()) {
        int app_id = main_app_it->second.uid % PER_USER_RANGE;
        new_app_state.uid = user_id * PER_USER_RANGE + app_id;
        new_app_state.app_name = main_app_it->second.app_name;
    } else {
        new_app_state.uid = -1;
    }

    auto config_opt = db_manager_->get_app_config(package_name);
    if (config_opt) new_app_state.config = *config_opt;
    else {
        new_app_state.config.package_name = package_name;
        db_manager_->set_app_config(new_app_state.config);
    }
    
    auto result = managed_apps_.emplace(key, new_app_state);
    return &result.first->second;
}


void StateManager::tick() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    auto now = std::chrono::steady_clock::now();

    // 1. 准备阶段：将所有心跳标记设为false
    for (auto& [key, app] : managed_apps_) {
        app.is_live_this_tick = false;
    }

    // 2. 标记阶段：遍历事实，标记存活的应用
    build_process_cache_from_cgroups();
    for (const auto& [pid, info] : process_info_cache_) {
        int user_id = info.uid / PER_USER_RANGE;
        AppRuntimeState* app = get_or_create_app_state(info.package_name, user_id);
        if (!app) continue;

        app->is_live_this_tick = true;
        if (app->uid == -1) app->uid = info.uid;

        // 状态同步
        if (app->current_status != AppRuntimeState::Status::FOREGROUND) {
            if (info.cgroup_state == CgroupState::FOREGROUND) {
                transition_state(*app, AppRuntimeState::Status::FOREGROUND);
            } else if (info.cgroup_state == CgroupState::BACKGROUND) {
                if (app->current_status != AppRuntimeState::Status::AWAITING_FREEZE &&
                    app->current_status != AppRuntimeState::Status::FROZEN) {
                    transition_state(*app, AppRuntimeState::Status::BACKGROUND_IDLE);
                }
            }
        }
    }

    // 3. 清扫与逻辑执行阶段
    for (auto& [key, app] : managed_apps_) {
        if (app.config.policy == AppPolicy::EXEMPTED) {
            transition_state(app, AppRuntimeState::Status::EXEMPTED);
            continue;
        }

        if (!app.is_live_this_tick) {
            // 清扫未被标记的应用
            if (app.current_status != AppRuntimeState::Status::STOPPED) {
                if (app.current_status == AppRuntimeState::Status::FROZEN) {
                    action_executor_->unfreeze_uid(app.uid);
                }
                transition_state(app, AppRuntimeState::Status::STOPPED);
                app.mem_usage_kb = 0;
                app.cpu_usage_percent = 0.0f;
                app.swap_usage_kb = 0;
            }
        } else {
            // 对存活的应用执行上层逻辑
            if (app.current_status == AppRuntimeState::Status::BACKGROUND_IDLE) {
                auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(now - app.last_state_change_time).count();
                if (elapsed > 15) transition_state(app, AppRuntimeState::Status::AWAITING_FREEZE);
            } else if (app.current_status == AppRuntimeState::Status::AWAITING_FREEZE) {
                auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(now - app.last_state_change_time).count();
                if (elapsed > 5) {
                    if (action_executor_->freeze_uid(app.uid)) transition_state(app, AppRuntimeState::Status::FROZEN);
                    else transition_state(app, AppRuntimeState::Status::BACKGROUND_IDLE);
                }
            }
        }
    }
}

// ============== 以下函数均无需修改 ==============
// StateManager 构造函数, transition_state, update_resource_stats, refresh_installed_apps, handle_app_event, get_dashboard_payload
// ... (这些函数的代码与上一版本完全相同，此处省略以节约篇幅) ...
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
        int representative_pid = -1;
        for(const auto& [pid, info] : process_info_cache_) {
            if (info.uid == app.uid) {
                representative_pid = pid;
                if (info.cgroup_state == CgroupState::FOREGROUND) break;
            }
        }
        if (representative_pid != -1) {
            AppStatsData app_stats = sys_monitor_->get_app_stats(representative_pid);
            app.cpu_usage_percent = app_stats.cpu_usage_percent;
            app.mem_usage_kb = app_stats.mem_usage_kb;
            app.swap_usage_kb = app_stats.swap_usage_kb;
        }
    }
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
            int user_id = 0;
            int full_uid = user_id * PER_USER_RANGE + app_id;
            bool is_system = (data_dir_path.rfind("/data/app/", 0) != 0);
            app_is_system_map_[package_name] = is_system;
            AppRuntimeState app_state;
            app_state.package_name = package_name;
            app_state.app_name = package_name;
            app_state.uid = full_uid;
            app_state.user_id = user_id;
            auto config_opt = db_manager_->get_app_config(package_name);
            if (config_opt) app_state.config = *config_opt;
            else {
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

void StateManager::handle_app_event(const std::string& package_name, int user_id, bool is_start) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    AppRuntimeState* app = get_or_create_app_state(package_name, user_id);
    if (!app) return;
    
    if (is_start) {
        if (app->current_status == AppRuntimeState::Status::FROZEN) {
            action_executor_->unfreeze_uid(app->uid);
        }
        transition_state(*app, AppRuntimeState::Status::FOREGROUND);
    } else {
        if (app->current_status == AppRuntimeState::Status::FROZEN) {
            action_executor_->unfreeze_uid(app->uid);
        }
        transition_state(*app, AppRuntimeState::Status::STOPPED);
        app->mem_usage_kb = 0;
        app->cpu_usage_percent = 0.0f;
        app->swap_usage_kb = 0;
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
            app_json["hasPlayback"] = false;
            app_json["hasNotification"] = false;
            app_json["hasNetworkActivity"] = false;
            app_json["pendingFreezeSec"] = 0;
            apps_state.push_back(app_json);
        }
    }
    payload["apps_runtime_state"] = apps_state;
    return payload;
}