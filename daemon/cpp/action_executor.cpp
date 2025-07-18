// daemon/cpp/action_executor.cpp
#include "action_executor.h"
#include <android/log.h>
#include <sys/stat.h>
#include <fstream>
#include <filesystem>
#include <unistd.h>
#include <cerrno>
#include <cstring>

#define LOG_TAG "cerberusd_action"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;

ActionExecutor::ActionExecutor() {
    initialize_frozen_cgroup();
}

bool ActionExecutor::initialize_frozen_cgroup() {
    // 确定cgroup版本
    if (fs::exists("/sys/fs/cgroup/cgroup.controllers")) {
        cgroup_version_ = CgroupVersion::V2;
        LOGI("Detected cgroup v2.");
        // v2模式下，需要在根cgroup下先启用freezer控制器
        frozen_cgroup_path_ = "/sys/fs/cgroup/cerberus_frozen";
        cgroup_procs_file_ = frozen_cgroup_path_ + "/cgroup.procs";
        cgroup_state_file_ = frozen_cgroup_path_ + "/cgroup.freeze";
    } else if (fs::exists("/sys/fs/cgroup/freezer")) {
        cgroup_version_ = CgroupVersion::V1;
        LOGI("Detected cgroup v1.");
        frozen_cgroup_path_ = "/sys/fs/cgroup/freezer/cerberus_frozen";
        cgroup_procs_file_ = frozen_cgroup_path_ + "/tasks";
        cgroup_state_file_ = frozen_cgroup_path_ + "/freezer.state";
    } else {
        cgroup_version_ = CgroupVersion::UNKNOWN;
        LOGE("Could not determine cgroup version. Freezer will be disabled.");
        return false;
    }

    // 创建专属cgroup目录
    if (!fs::exists(frozen_cgroup_path_)) {
        LOGI("Creating dedicated frozen cgroup at: %s", frozen_cgroup_path_.c_str());
        try {
            fs::create_directory(frozen_cgroup_path_);
        } catch (const fs::filesystem_error& e) {
            LOGE("Failed to create frozen cgroup directory: %s. Freezer disabled.", e.what());
            cgroup_version_ = CgroupVersion::UNKNOWN; // 创建失败则禁用
            return false;
        }
    }
    
    // 【针对cgroup v2】确保freezer控制器在我们的子group中可用
    if (cgroup_version_ == CgroupVersion::V2) {
        if (!write_to_file("/sys/fs/cgroup/cgroup.subtree_control", "+freezer")) {
            // 这可能因为已经启用或权限问题，只记录警告
            LOGW("Could not enable freezer controller on root cgroup. It might already be enabled.");
        }
    }
    
    LOGI("Dedicated frozen cgroup is ready.");
    return true;
}

bool ActionExecutor::write_to_file(const std::string& path, const std::string& value) {
    std::ofstream ofs(path);
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

bool ActionExecutor::add_pids_to_frozen_cgroup(const std::vector<int>& pids) {
    if (cgroup_version_ == CgroupVersion::UNKNOWN) return false;

    // 使用追加模式打开，避免覆盖已有的PID
    std::ofstream ofs(cgroup_procs_file_, std::ios_base::app);
    if (!ofs.is_open()) {
        LOGE("Failed to open cgroup procs file for appending PIDs: %s. Error: %s", cgroup_procs_file_.c_str(), strerror(errno));
        return false;
    }
    for (int pid : pids) {
        ofs << pid << std::endl;
        if (ofs.fail()) {
            // EPERM 或 ESRCH 是常见错误，表示进程已死或权限不足，可以容忍
            if (errno != EPERM && errno != ESRCH) {
                LOGW("Failed to write PID %d to %s: %s", pid, cgroup_procs_file_.c_str(), strerror(errno));
            }
        }
    }
    return true;
}


bool ActionExecutor::freeze_pids(const std::vector<int>& pids) {
    if (cgroup_version_ == CgroupVersion::UNKNOWN) return false;
    if (pids.empty()) {
        LOGW("Attempted to freeze with an empty PID list.");
        return true; // 空操作视为成功
    }

    if (!add_pids_to_frozen_cgroup(pids)) {
        LOGE("Failed to move PIDs to frozen cgroup, aborting freeze.");
        return false;
    }

    LOGI("Executing FREEZE command on cgroup: %s", frozen_cgroup_path_.c_str());
    const std::string freeze_cmd = (cgroup_version_ == CgroupVersion::V2) ? "1" : "FROZEN";
    if (!write_to_file(cgroup_state_file_, freeze_cmd)) {
        LOGE("Failed to set cgroup state to frozen.");
        return false;
    }
    
    return true;
}

bool ActionExecutor::unfreeze_cgroup() {
    if (cgroup_version_ == CgroupVersion::UNKNOWN) return false;
    
    LOGI("Executing UNFREEZE command on cgroup: %s", frozen_cgroup_path_.c_str());
    const std::string unfreeze_cmd = (cgroup_version_ == CgroupVersion::V2) ? "0" : "THAWED";
    
    if (!write_to_file(cgroup_state_file_, unfreeze_cmd)) {
        LOGE("Failed to set cgroup state to unfrozen.");
        return false;
    }

    // 解冻后，理论上可以将进程移回默认cgroup，但通常没必要。
    // 进程退出时会自动从cgroup中移除。保持简单。
    return true;
}