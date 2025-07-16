// daemon/cpp/state_manager.cpp
#include "state_manager.h"
#include "action_executor.h"
#include <android/log.h>
#include <cstdio>
#include <memory>
#include <array>
#include <sstream>
#include <filesystem> // C++17
#include <fstream>

#define LOG_TAG "cerberusd_state"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;

// 【已废弃】不再使用shell命令
// std::string exec_shell_local_sm(const char* cmd) { ... }

// 【核心修复】重写get_pid_for_package，不再依赖shell
int StateManager::get_pid_for_package(const std::string& package_name) {
    for (const auto& entry : fs::directory_iterator("/proc")) {
        if (!entry.is_directory()) continue;
        
        // 检查目录名是否为数字（即PID）
        std::string pid_str = entry.path().filename().string();
        if (!std::all_of(pid_str.begin(), pid_str.end(), ::isdigit)) {
            continue;
        }

        // 读取 /proc/[pid]/cmdline 文件
        std::ifstream cmdline_file(entry.path() / "cmdline");
        if (cmdline_file.is_open()) {
            std::string cmdline;
            std::getline(cmdline_file, cmdline);
            // cmdline中的参数以\0分隔，第一个通常就是包名
            // 我们直接比较字符串开头部分即可
            if (!cmdline.empty() && cmdline.rfind(package_name, 0) == 0) {
                try {
                    return std::stoi(pid_str);
                } catch (const std::invalid_argument&) {
                    // 基本不会发生
                    return -1;
                }
            }
        }
    }
    return -1; // 未找到
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
    
    // 【新增日志】方便调试状态转换
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

    // 【优化】直接读取 /data/system/packages.list 获取包名和UID，比pm命令更快更可靠
    std::ifstream packages_file("/data/system/packages.list");
    if (!packages_file.is_open()) {
        LOGE("Failed to open /data/system/packages.list. Cannot get app list.");
        return;
    }

    std::string line;
    while (std::getline(packages_file, line)) {
        std::stringstream ss(line);
        std::string package_name, uid_str, gid_str, data_dir, seinfo;
        
        // 文件格式: package_name user_id debug_flag data_dir seinfo ...
        ss >> package_name >> uid_str;
        if (package_name.empty() || uid_str.empty()) continue;

        try {
            int full_uid = std::stoi(uid_str);
            
            AppRuntimeState app_state;
            app_state.package_name = package_name;
            app_state.app_name = package_name; // 暂时用包名填充，UI层会用真实名称覆盖
            app_state.uid = full_uid;
            app_state.user_id = full_uid / 100000;

            auto config_opt = db_manager_->get_app_config(package_name);
            if (config_opt) {
                app_state.config = *config_opt;
            } else {
                app_state.config.package_name = package_name;
                // 新发现的应用，为其创建默认配置
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
        // 【新增】进程被杀，资源占用清零
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
        // TODO: 这里需要更精确地判断是前台还是后台启动，暂时默认为前台
        transition_state(it->second, AppRuntimeState::Status::FOREGROUND);
    }
}

void StateManager::update_all_states() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    global_stats_ = sys_monitor_->get_stats();
    auto now = std::chrono::steady_clock::now();

    for (auto& [pkg, app] : managed_apps_) {
        // 如果应用被设置为豁免，直接设置为EXEMPTED状态并跳过所有逻辑
        if (app.config.policy == AppPolicy::EXEMPTED) {
            transition_state(app, AppRuntimeState::Status::EXEMPTED);
            app.mem_usage_kb = 0;
            app.cpu_usage_percent = 0.0f;
            continue;
        }

        int pid = get_pid_for_package(pkg);

        // 如果找不到PID，则认为进程已停止
        if (pid <= 0) {
            // 如果之前是FROZEN状态，需要先解冻，防止cgroup残留
            if (app.current_status == AppRuntimeState::Status::FROZEN) {
                action_executor_->unfreeze_uid(app.uid);
            }
            transition_state(app, AppRuntimeState::Status::STOPPED);
            app.mem_usage_kb = 0;
            app.cpu_usage_percent = 0.0f;
            continue;
        }
        
        // 如果找到了PID，就获取它的资源使用情况
        AppStatsData app_stats = sys_monitor_->get_app_stats(pid);
        app.cpu_usage_percent = app_stats.cpu_usage_percent;
        app.mem_usage_kb = app_stats.mem_usage_kb;

        // 如果状态是STOPPED但我们找到了PID，说明它刚启动，置为后台空闲开始计时
        if(app.current_status == AppRuntimeState::Status::STOPPED){
            transition_state(app, AppRuntimeState::Status::BACKGROUND_IDLE);
        }
        
        // 状态机核心逻辑
        switch (app.current_status) {
            case AppRuntimeState::Status::BACKGROUND_IDLE: {
                auto elapsed_seconds = std::chrono::duration_cast<std::chrono::seconds>(now - app.last_state_change_time).count();
                // 简化逻辑：后台空闲超过15秒就准备冻结（后续会根据策略调整）
                if (elapsed_seconds > 15) {
                    transition_state(app, AppRuntimeState::Status::AWAITING_FREEZE);
                }
                break;
            }
            case AppRuntimeState::Status::AWAITING_FREEZE: {
                 auto elapsed_seconds = std::chrono::duration_cast<std::chrono::seconds>(now - app.last_state_change_time).count();
                 // 等待5秒后执行冻结
                 if (elapsed_seconds > 5) {
                    if (action_executor_->freeze_uid(app.uid)) {
                        transition_state(app, AppRuntimeState::Status::FROZEN);
                    } else {
                        // 如果冻结失败，回到后台空闲状态，避免循环尝试
                        transition_state(app, AppRuntimeState::Status::BACKGROUND_IDLE);
                    }
                 }
                break;
            }
            // TODO: 添加从FROZEN解冻的逻辑，例如收到推送事件
            default:
                break;
        }
    }
}

// 内部使用的辅助函数
std::string StateManager::status_to_string_local(AppRuntimeState::Status status) {
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
        // 【新增】将SWAP信息加入payload
        {"swap_total_kb", global_stats_.swap_total_kb},
        {"swap_free_kb", global_stats_.swap_free_kb},
        {"active_profile_name", "⚡️ 省电模式"}
    };
    
    json apps_state = json::array();
    // 只推送有状态变化或正在运行的应用，以减少数据量
    for (const auto& [pkg, app] : managed_apps_) {
        if (app.current_status != AppRuntimeState::Status::STOPPED) {
            json app_json;
            app_json["package_name"] = app.package_name;
            app_json["app_name"] = app.app_name;
            app_json["user_id"] = app.user_id;
            app_json["display_status"] = status_to_string_local(app.current_status);
            app_json["mem_usage_kb"] = app.mem_usage_kb;
            app_json["cpu_usage_percent"] = app.cpu_usage_percent;
            app_json["is_whitelisted"] = (app.config.policy == AppPolicy::EXEMPTED);
            app_json["is_foreground"] = (app.current_status == AppRuntimeState::Status::FOREGROUND);
            // ... 未来可以添加更多状态
            apps_state.push_back(app_json);
        }
    }
    payload["apps_runtime_state"] = apps_state;
    return payload;
}