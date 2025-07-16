// daemon/cpp/state_manager.cpp
#include "state_manager.h"
#include "action_executor.h"
#include <android/log.h>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <sys/stat.h>
#include <unistd.h>
#include <algorithm>

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

StateManager::StateManager(std::shared_ptr<DatabaseManager> db, std::shared_ptr<SystemMonitor> sys, std::shared_ptr<ActionExecutor> act)
    : db_manager_(db), sys_monitor_(sys), action_executor_(act) {
    LOGI("StateManager (PID-Based Freeze) Initializing...");
    // 【根本性重构】移除所有硬编码白名单，因为新的冻结机制是安全的
    refresh_app_list_from_db();
    initial_scan();
    LOGI("StateManager Initialized.");
}

void StateManager::tick() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    auto now = std::chrono::steady_clock::now();

    check_and_update_foreground_status();

    for (auto& [key, app] : managed_apps_) {
        // 【根本性重构】判断逻辑简化。数据库里没有 = 豁免。有配置就按配置来。
        if (app.pids.empty() || app.config.policy == AppPolicy::EXEMPTED) {
            continue;
        }

        // --- 只有配置为非豁免的应用才会进入管理逻辑 ---
        if (app.current_status == AppRuntimeState::Status::BACKGROUND_IDLE) {
            auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(now - app.last_state_change_time).count();
            if (elapsed > 15) { 
                transition_state(app, AppRuntimeState::Status::AWAITING_FREEZE);
            }
        } else if (app.current_status == AppRuntimeState::Status::AWAITING_FREEZE) {
             auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(now - app.last_state_change_time).count();
            if (elapsed > 5) { 
                // 【根本性重构】调用新的、安全的PID冻结接口
                if (action_executor_->freeze_pids(app.pids)) {
                    transition_state(app, AppRuntimeState::Status::FROZEN);
                } else {
                    LOGW("Failed to freeze PIDs for %s, reverting to BACKGROUND_IDLE", app.package_name.c_str());
                    transition_state(app, AppRuntimeState::Status::BACKGROUND_IDLE);
                }
            }
        }
    }
}

AppRuntimeState* StateManager::get_or_create_app_state(const std::string& package_name, int user_id) {
    AppInstanceKey key = {package_name, user_id};
    auto it = managed_apps_.find(key);
    if (it != managed_apps_.end()) return &it->second;

    AppRuntimeState new_state;
    new_state.package_name = package_name;
    new_state.user_id = user_id;
    new_state.app_name = package_name;
    
    auto config_opt = db_manager_->get_app_config(package_name);
    if(config_opt) {
        new_state.config = *config_opt;
    } else {
        new_state.config.policy = AppPolicy::EXEMPTED; // Opt-in: 数据库没有就默认豁免
    }
    
    auto result = managed_apps_.emplace(key, new_state);
    return &result.first->second;
}

void StateManager::remove_pid_from_app(int pid) {
    auto it = pid_to_app_map_.find(pid);
    if (it == pid_to_app_map_.end()) return;

    AppRuntimeState* app = it->second;
    pid_to_app_map_.erase(it);

    auto& pids = app->pids;
    auto pid_it = std::remove(pids.begin(), pids.end(), pid);
    pids.erase(pid_it, pids.end());

    LOGI("Process %d exited for %s (user %d), remaining pids: %zu.", pid, app->package_name.c_str(), app->user_id, pids.size());

    if (pids.empty() && app->current_status != AppRuntimeState::Status::STOPPED) {
        if(app->current_status == AppRuntimeState::Status::FROZEN){
            // 【根本性重构】调用新的PID解冻接口
            // 尽管pids为空，但cgroup仍可能处于冻结状态，解冻它是安全的
            action_executor_->unfreeze_pids({}); 
        }
        transition_state(*app, AppRuntimeState::Status::STOPPED);
        app->mem_usage_kb = 0;
        app->swap_usage_kb = 0;
        app->cpu_usage_percent = 0.0f;
    }
}

void StateManager::check_and_update_foreground_status() {
    std::string fg_pkg;
    int fg_uid = -1;
    std::ifstream fg_file("/dev/cpuset/foreground/tasks");
    if(fg_file.is_open() && fg_file.peek() != std::ifstream::traits_type::eof()){
        int fg_pid;
        fg_file >> fg_pid;
        int user_id = -1;
        fg_pkg = get_package_name_from_pid(fg_pid, fg_uid, user_id);
    }
    
    for(auto& [key, app] : managed_apps_){
        if(app.pids.empty()) continue;

        bool is_foreground = (!fg_pkg.empty() && app.package_name == fg_pkg && app.uid == fg_uid);

        if(is_foreground && app.current_status != AppRuntimeState::Status::FOREGROUND){
             if(app.current_status == AppRuntimeState::Status::FROZEN){
                // 【根本性重构】调用新的PID解冻接口
                action_executor_->unfreeze_pids(app.pids);
             }
             transition_state(app, AppRuntimeState::Status::FOREGROUND);
        } else if (!is_foreground && app.current_status == AppRuntimeState::Status::FOREGROUND) {
            transition_state(app, AppRuntimeState::Status::BACKGROUND_IDLE);
        }
    }
}

// ----- 其他函数保持不变，无需修改 -----
// ... (update_app_config_from_ui, add_pid_to_app, get_dashboard_payload, etc.)
// ... 这些函数的内容是正确的，无需粘贴重复代码 ...
void StateManager::update_app_config_from_ui(const AppConfig& new_config) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    
    if (!db_manager_->set_app_config(new_config)) {
        LOGE("Failed to save app config to DB for %s", new_config.package_name.c_str());
    }

    for (auto& [key, app] : managed_apps_) {
        if (key.first == new_config.package_name) {
            app.config = new_config;
            LOGI("Updated in-memory config for %s (user %d). New policy: %d", 
                 app.package_name.c_str(), app.user_id, static_cast<int>(app.config.policy));
            
            if (app.config.policy == AppPolicy::EXEMPTED && app.current_status != AppRuntimeState::Status::EXEMPTED) {
                if(app.current_status == AppRuntimeState::Status::FROZEN) {
                    action_executor_->unfreeze_pids(app.pids);
                }
                transition_state(app, AppRuntimeState::Status::EXEMPTED);
            } 
            else if (app.config.policy != AppPolicy::EXEMPTED && app.current_status == AppRuntimeState::Status::EXEMPTED) {
                transition_state(app, AppRuntimeState::Status::BACKGROUND_IDLE);
            }
        }
    }
}

void StateManager::add_pid_to_app(int pid, const std::string& package_name, int user_id, int uid) {
    AppRuntimeState* app = get_or_create_app_state(package_name, user_id);
    if (!app) return;

    if (app->uid == -1) app->uid = uid;

    if (std::find(app->pids.begin(), app->pids.end(), pid) == app->pids.end()) {
        app->pids.push_back(pid);
        pid_to_app_map_[pid] = app;

        if (app->current_status == AppRuntimeState::Status::STOPPED) {
            if (app->config.policy == AppPolicy::EXEMPTED) {
                 transition_state(*app, AppRuntimeState::Status::EXEMPTED);
            } else {
                 transition_state(*app, AppRuntimeState::Status::BACKGROUND_IDLE);
            }
            LOGI("Discovered new process %d for %s (user %d), policy is %d. Initial state: %s", 
                pid, package_name.c_str(), user_id, static_cast<int>(app->config.policy), status_to_string_local(app->current_status).c_str());
        }
    }
}
void StateManager::process_event_handler(ProcessEventType type, int pid, int ppid) {
    std::lock_guard<std::mutex> lock(state_mutex_);
    
    if (type == ProcessEventType::EXIT) {
        remove_pid_from_app(pid);
    } else if (type == ProcessEventType::FORK) {
        AppRuntimeState* parent_app = find_app_by_pid(ppid);
        if (parent_app) {
             int uid = -1, user_id = -1;
             std::string pkg_name = get_package_name_from_pid(pid, uid, user_id);
             if(!pkg_name.empty() && pkg_name == parent_app->package_name){
                add_pid_to_app(pid, parent_app->package_name, parent_app->user_id, parent_app->uid);
             }
        }
    }
}

void StateManager::initial_scan() {
    LOGI("Performing initial process scan...");
    for (const auto& entry : fs::directory_iterator("/proc")) {
        if (!entry.is_directory()) continue;
        try {
            int pid = std::stoi(entry.path().filename().string());
            int uid = -1, user_id = -1;
            std::string pkg_name = get_package_name_from_pid(pid, uid, user_id);
            if (!pkg_name.empty() && uid >= 10000) {
                add_pid_to_app(pid, pkg_name, user_id, uid);
            }
        } catch (const std::invalid_argument&) { continue; }
    }
    LOGI("Initial scan completed. Found %zu tracked processes.", pid_to_app_map_.size());
}

void StateManager::refresh_app_list_from_db() {
    auto configs = db_manager_->get_all_app_configs();
    LOGI("Loading %zu app configs from database.", configs.size());
    for(const auto& config : configs){
        get_or_create_app_state(config.package_name, 0)->config = config;
    }
}

std::string StateManager::get_package_name_from_pid(int pid, int& uid, int& user_id) {
    struct stat st;
    std::string proc_path = "/proc/" + std::to_string(pid);
    if (stat(proc_path.c_str(), &st) != 0) return "";
    
    uid = st.st_uid;
    user_id = uid / PER_USER_RANGE;
    
    std::ifstream cmdline_file(proc_path + "/cmdline");
    if (!cmdline_file.is_open()) return "";
    
    std::string cmdline;
    std::getline(cmdline_file, cmdline, '\0'); 
    
    if (cmdline.find('/') == std::string::npos && cmdline.find('.') != std::string::npos) {
        if(cmdline.rfind("com.android.", 0) == 0 && cmdline.find(':') == std::string::npos) {
            if (cmdline == "com.android.systemui" || cmdline == "com.android.incallui" || cmdline == "com.android.phone") {
                return cmdline;
            }
            return "";
        }
        return cmdline;
    }
    return "";
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

AppRuntimeState* StateManager::find_app_by_pid(int pid) {
    auto it = pid_to_app_map_.find(pid);
    return it != pid_to_app_map_.end() ? it->second : nullptr;
}

void StateManager::update_all_resource_stats() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    sys_monitor_->update_global_stats();
    global_stats_ = sys_monitor_->get_global_stats();

    for(auto& [key, app] : managed_apps_){
        if(app.pids.empty()){
            app.mem_usage_kb = 0;
            app.swap_usage_kb = 0;
            app.cpu_usage_percent = 0.0f;
            continue;
        }
        AppStatsData total_stats;
        for(int pid : app.pids){
            AppStatsData pid_stats = sys_monitor_->get_app_stats(pid, app.package_name, app.user_id);
            total_stats.mem_usage_kb += pid_stats.mem_usage_kb;
            total_stats.swap_usage_kb += pid_stats.swap_usage_kb;
            total_stats.cpu_usage_percent += pid_stats.cpu_usage_percent;
        }
        app.mem_usage_kb = total_stats.mem_usage_kb;
        app.swap_usage_kb = total_stats.swap_usage_kb;
        app.cpu_usage_percent = total_stats.cpu_usage_percent;
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
        if (!app.pids.empty() || app.current_status != AppRuntimeState::Status::STOPPED) { 
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