// daemon/cpp/state_manager.cpp
#include "state_manager.h"
#include <android/log.h>
#include <cstdio>
#include <memory>
#include <array>
#include <sstream>

#define LOG_TAG "cerberusd_state"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 辅助函数：执行shell命令并获取其输出
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

StateManager::StateManager(std::shared_ptr<DatabaseManager> db_manager, std::shared_ptr<SystemMonitor> sys_monitor)
    : db_manager_(std::move(db_manager)), sys_monitor_(std::move(sys_monitor)) {
    LOGI("StateManager initialized.");
    refresh_installed_apps();
}

void StateManager::refresh_installed_apps() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    LOGI("Refreshing installed apps list...");
    
    managed_apps_.clear();
    // pm list packages: -f(path) -U(uid) -3(third-party only) --show-versioncode
    std::string package_list_str = exec_shell("pm list packages -U -3");

    std::stringstream ss(package_list_str);
    std::string line;
    while (std::getline(ss, line, '\n')) {
        // line format: package:com.example.app uid:10234
        size_t pkg_pos = line.find("package:");
        size_t uid_pos = line.find(" uid:");
        if (uid_pos == std::string::npos) continue;

        std::string package_name = line.substr(pkg_pos + 8, uid_pos - (pkg_pos + 8));
        
        AppRuntimeState app_state;
        app_state.package_name = package_name;
        // 临时用包名作为应用名，后续从Probe获取
        app_state.app_name = package_name; 
        app_state.uid = std::stoi(line.substr(uid_pos + 5));
        
        auto config_opt = db_manager_->get_app_config(package_name);
        if (config_opt) {
            app_state.config = *config_opt;
        } else {
            app_state.config.package_name = package_name;
            // 如果不在数据库，则存入默认配置
            db_manager_->set_app_config(app_state.config);
        }
        
        // 根据策略初始化状态
        app_state.current_status = (app_state.config.policy == AppPolicy::EXEMPTED)
                                    ? AppRuntimeState::Status::EXEMPTED
                                    : AppRuntimeState::Status::BACKGROUND_IDLE;
        
        managed_apps_[package_name] = app_state;
    }
    LOGI("Found %zu third-party apps.", managed_apps_.size());
}


void StateManager::update_all_states() {
    std::lock_guard<std::mutex> lock(state_mutex_);
    // 1. 更新全局状态
    global_stats_ = sys_monitor_->get_stats();

    // 2. 更新每个应用的状态 (未来会更复杂)
    // for (auto& [pkg, app] : managed_apps_) {
    //     // TODO: 读取每个应用的 /proc/[pid]/stat 来获取CPU和内存
    // }
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
    // 填充真实全局状态
    payload["global_stats"] = {
        {"total_cpu_usage_percent", global_stats_.total_cpu_usage_percent},
        {"total_mem_kb", global_stats_.total_mem_kb},
        {"avail_mem_kb", global_stats_.avail_mem_kb},
        {"net_down_speed_bps", 0L}, // 暂未实现
        {"net_up_speed_bps", 0L},   // 暂未实现
        {"active_profile_name", "⚡️ 省电模式"} // 暂为硬编码
    };
    
    json apps_state = json::array();
    for (const auto& [pkg, app] : managed_apps_) {
        json app_json;
        app_json["package_name"] = app.package_name;
        app_json["app_name"] = app.app_name;
        app_json["display_status"] = status_to_string(app.current_status);
        app_json["active_freeze_mode"] = nullptr; // 暂未实现
        app_json["mem_usage_kb"] = app.mem_usage_kb;
        app_json["cpu_usage_percent"] = app.cpu_usage_percent;
        app_json["is_whitelisted"] = (app.config.policy == AppPolicy::EXEMPTED);
        app_json["is_foreground"] = (app.current_status == AppRuntimeState::Status::FOREGROUND);
        apps_state.push_back(app_json);
    }
    payload["apps_runtime_state"] = apps_state;
    return payload;
}