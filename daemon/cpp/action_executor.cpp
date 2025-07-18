// daemon/cpp/action_executor.cpp
#include "action_executor.h"
#include <android/log.h>
#include <sys/stat.h>
#include <fstream>
#include <filesystem>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <sstream>
#include <csignal>
#include <algorithm> // For std::replace

#define LOG_TAG "cerberusd_action"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;

// 构造函数
ActionExecutor::ActionExecutor() {
    if (fs::exists("/sys/fs/cgroup/cgroup.controllers")) {
        cgroup_version_ = CgroupVersion::V2;
        cgroup_root_path_ = "/sys/fs/cgroup/";
        LOGI("Detected cgroup v2. Root: %s", cgroup_root_path_.c_str());
        // 确保 freezer controller 在根cgroup可用
        write_to_file(cgroup_root_path_ + "cgroup.subtree_control", "+freezer");
    } else if (fs::exists("/sys/fs/cgroup/freezer")) {
        cgroup_version_ = CgroupVersion::V1;
        cgroup_root_path_ = "/sys/fs/cgroup/freezer/";
        LOGI("Detected cgroup v1. Root: %s", cgroup_root_path_.c_str());
    } else {
        cgroup_version_ = CgroupVersion::UNKNOWN;
        LOGE("Could not determine cgroup version. Freezer is disabled.");
    }
}

// 辅助函数，用于日志和路径生成
std::string to_string_key(const AppInstanceKey& key) {
    std::string sanitized_package_name = key.first;
    std::replace(sanitized_package_name.begin(), sanitized_package_name.end(), '.', '_');
    return sanitized_package_name + "_" + std::to_string(key.second);
}

// 获取实例的cgroup路径
std::string ActionExecutor::get_instance_cgroup_path(const AppInstanceKey& key) const {
    return cgroup_root_path_ + "cerberus_" + to_string_key(key);
}

// 冻结操作
bool ActionExecutor::freeze(const AppInstanceKey& key, const std::vector<int>& pids) {
    if (cgroup_version_ == CgroupVersion::UNKNOWN || pids.empty()) return false;

    std::string instance_path = get_instance_cgroup_path(key);
    if (!create_instance_cgroup(instance_path)) return false;

    if (!move_pids_to_cgroup(pids, instance_path)) {
        LOGE("Failed to move pids for '%s' to its cgroup.", to_string_key(key).c_str());
        return false;
    }

    std::string state_file = instance_path + ((cgroup_version_ == CgroupVersion::V2) ? "/cgroup.freeze" : "/freezer.state");
    const std::string freeze_cmd = (cgroup_version_ == CgroupVersion::V2) ? "1" : "FROZEN";
    
    if (!write_to_file(state_file, freeze_cmd)) {
        LOGE("Failed to freeze cgroup for '%s'.", to_string_key(key).c_str());
        return false;
    }
    
    LOGI("Successfully froze instance '%s'.", to_string_key(key).c_str());
    return true;
}

// 可靠的解冻并清理操作
bool ActionExecutor::unfreeze_and_cleanup(const AppInstanceKey& key) {
    if (cgroup_version_ == CgroupVersion::UNKNOWN) return false;

    std::string instance_path = get_instance_cgroup_path(key);
    if (!fs::exists(instance_path)) return true;

    // 步骤 1: 写入解冻命令
    std::string state_file_path = instance_path + ((cgroup_version_ == CgroupVersion::V2) ? "/cgroup.freeze" : "/freezer.state");
    const std::string unfreeze_cmd = (cgroup_version_ == CgroupVersion::V2) ? "0" : "THAWED";
    if (!write_to_file(state_file_path, unfreeze_cmd)) {
        LOGE("Failed to write unfreeze command for '%s'.", to_string_key(key).c_str());
        return false;
    }

    // 步骤 2: 循环回读状态，确认内核已处理，带超时
    const std::string expected_state = (cgroup_version_ == CgroupVersion::V2) ? "0" : "THAWED";
    int retries = 50; // 最多等待 50 * 10ms = 500ms
    bool confirmed = false;
    while(retries-- > 0) {
        std::ifstream state_file(state_file_path);
        if(state_file.is_open()) {
            std::string current_state;
            state_file >> current_state;
            if (current_state.find(expected_state) != std::string::npos) {
                confirmed = true;
                break;
            }
        }
        usleep(10000); // 等待 10ms
    }

    if (!confirmed) {
        LOGW("Unfreeze confirmation for '%s' timed out. Process might be sluggish.", to_string_key(key).c_str());
    } else {
        LOGI("Unfreeze for '%s' confirmed by kernel.", to_string_key(key).c_str());
    }

    // 步骤 3: 将进程移回默认组并清理cgroup目录
    std::string procs_file = instance_path + ((cgroup_version_ == CgroupVersion::V2) ? "/cgroup.procs" : "/tasks");
    std::vector<int> pids_to_move;
    std::ifstream ifs(procs_file);
    int pid;
    while(ifs >> pid) {
        pids_to_move.push_back(pid);
    }
    if (!pids_to_move.empty()) {
        move_pids_to_default_cgroup(pids_to_move);
    }
    remove_instance_cgroup(instance_path);

    LOGI("Successfully unfroze and cleaned up instance '%s'.", to_string_key(key).c_str());
    return true;
}

// 将新生进程移动到已冻结的cgroup中
bool ActionExecutor::move_pids_to_instance_cgroup(const AppInstanceKey& key, const std::vector<int>& pids) {
    if (cgroup_version_ == CgroupVersion::UNKNOWN || pids.empty()) return false;
    
    std::string instance_path = get_instance_cgroup_path(key);
    if (!fs::exists(instance_path)) {
        LOGW("Attempted to move pids to non-existent cgroup for '%s'. This may happen if the parent app was just unfrozen.", to_string_key(key).c_str());
        return false;
    }
    
    LOGI("Moving %zu newborn pids to existing frozen cgroup for '%s'.", pids.size(), to_string_key(key).c_str());
    return move_pids_to_cgroup(pids, instance_path);
}

// 辅助函数: 创建cgroup目录
bool ActionExecutor::create_instance_cgroup(const std::string& path) {
    if (fs::exists(path)) return true;
    try {
        fs::create_directory(path);
        return true;
    } catch (const fs::filesystem_error& e) {
        LOGE("Failed to create cgroup '%s': %s", path.c_str(), e.what());
        return false;
    }
}

// 辅助函数: 移除cgroup目录
bool ActionExecutor::remove_instance_cgroup(const std::string& path) {
    if (!fs::exists(path)) return true;
    try {
        std::string procs_file = path + ((cgroup_version_ == CgroupVersion::V2) ? "/cgroup.procs" : "/tasks");
        std::ifstream ifs(procs_file);
        if (ifs.peek() != std::ifstream::traits_type::eof()) {
            LOGW("Cannot remove cgroup '%s' as it is not empty.", path.c_str());
            return false;
        }
        fs::remove(path);
        return true;
    } catch (const fs::filesystem_error& e) {
        LOGE("Failed to remove cgroup '%s': %s", path.c_str(), e.what());
        return false;
    }
}

// 辅助函数: 将PID移动到指定cgroup
bool ActionExecutor::move_pids_to_cgroup(const std::vector<int>& pids, const std::string& cgroup_path) {
    std::string procs_file = cgroup_path + ((cgroup_version_ == CgroupVersion::V2) ? "/cgroup.procs" : "/tasks");
    std::ofstream ofs(procs_file, std::ios_base::app);
    if (!ofs.is_open()) {
        LOGE("Failed to open '%s' to move pids: %s", procs_file.c_str(), strerror(errno));
        return false;
    }
    for (int pid : pids) {
        ofs << pid << std::endl;
    }
    return true;
}

// 辅助函数: 将PID移动到默认根cgroup
bool ActionExecutor::move_pids_to_default_cgroup(const std::vector<int>& pids) {
    return move_pids_to_cgroup(pids, cgroup_root_path_);
}

// 辅助函数: 写文件
bool ActionExecutor::write_to_file(const std::string& path, const std::string& value) {
    std::ofstream ofs(path); // 使用覆盖写而不是追加
    if (!ofs.is_open()) {
        LOGE("Failed to open file '%s' for writing: %s", path.c_str(), strerror(errno));
        return false;
    }
    ofs << value;
    if (ofs.fail()) {
        LOGE("Failed to write '%s' to '%s': %s", value.c_str(), path.c_str(), strerror(errno));
        return false;
    }
    return true;
}