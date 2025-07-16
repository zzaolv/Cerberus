// daemon/cpp/action_executor.cpp
#include "action_executor.h"
#include <android/log.h>
#include <sys/stat.h>
#include <fstream>
#include <filesystem>
#include <unistd.h>
#include <cstring>

#define LOG_TAG "cerberusd_action"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;

ActionExecutor::ActionExecutor() {
    if (fs::exists("/sys/fs/cgroup/cgroup.controllers")) {
        cgroup_version_ = CgroupVersion::V2;
        LOGI("Detected cgroup v2.");
        frozen_cgroup_path_ = "/sys/fs/cgroup/cerberus_frozen";
    } else if (fs::exists("/sys/fs/cgroup/freezer")) {
        cgroup_version_ = CgroupVersion::V1;
        LOGI("Detected cgroup v1.");
        frozen_cgroup_path_ = "/sys/fs/cgroup/freezer/cerberus_frozen";
    } else {
        cgroup_version_ = CgroupVersion::UNKNOWN;
        LOGE("Could not determine cgroup version.");
        return;
    }
    create_frozen_cgroup_if_needed();
}

void ActionExecutor::create_frozen_cgroup_if_needed() {
    if (cgroup_version_ == CgroupVersion::UNKNOWN) return;

    if (!fs::exists(frozen_cgroup_path_)) {
        LOGI("Creating dedicated frozen cgroup at: %s", frozen_cgroup_path_.c_str());
        try {
            fs::create_directory(frozen_cgroup_path_);
        } catch (const fs::filesystem_error& e) {
            LOGE("Failed to create frozen cgroup: %s", e.what());
            return;
        }
    }
    
    // 设置文件路径
    if (cgroup_version_ == CgroupVersion::V2) {
        frozen_cgroup_procs_path_ = frozen_cgroup_path_ + "/cgroup.procs";
        frozen_cgroup_state_path_ = frozen_cgroup_path_ + "/cgroup.freeze";
    } else { // V1
        frozen_cgroup_procs_path_ = frozen_cgroup_path_ + "/tasks";
        frozen_cgroup_state_path_ = frozen_cgroup_path_ + "/freezer.state";
    }
    
    LOGI("Frozen cgroup is ready.");
}


bool ActionExecutor::write_to_file(const std::string& path, const std::string& value) {
    std::ofstream ofs(path);
    if (!ofs.is_open()) {
        LOGE("Failed to open file: %s. Error: %s", path.c_str(), strerror(errno));
        return false;
    }
    ofs << value;
    if (ofs.fail()) {
        LOGE("Failed to write '%s' to %s. Error: %s", value.c_str(), path.c_str(), strerror(errno));
        return false;
    }
    return true;
}

bool ActionExecutor::write_pids_to_file(const std::string& path, const std::vector<int>& pids) {
    std::ofstream ofs(path, std::ios_base::app); // 使用追加模式
    if (!ofs.is_open()) {
        LOGE("Failed to open file for appending PIDs: %s. Error: %s", path.c_str(), strerror(errno));
        return false;
    }
    for (int pid : pids) {
        ofs << pid << std::endl;
        if (ofs.fail()) {
            LOGE("Failed to write PID %d to %s. Error: %s", pid, path.c_str(), strerror(errno));
            return false;
        }
    }
    return true;
}


bool ActionExecutor::add_pids_to_frozen_cgroup(const std::vector<int>& pids) {
    if (pids.empty()) return true;
    LOGI("Moving %zu PIDs to frozen cgroup...", pids.size());
    return write_pids_to_file(frozen_cgroup_procs_path_, pids);
}


bool ActionExecutor::freeze_pids(const std::vector<int>& pids) {
    if (pids.empty()) {
        LOGW("Attempted to freeze with an empty PID list.");
        return true;
    }
    if (!add_pids_to_frozen_cgroup(pids)) {
        LOGE("Failed to move PIDs to frozen cgroup, aborting freeze.");
        return false;
    }

    LOGI("Executing freeze on cgroup: %s", frozen_cgroup_path_.c_str());
    const std::string freeze_cmd = (cgroup_version_ == CgroupVersion::V2) ? "1" : "FROZEN";
    return write_to_file(frozen_cgroup_state_path_, freeze_cmd);
}

bool ActionExecutor::unfreeze_pids(const std::vector<int>& pids) {
    // 解冻比冻结更简单，我们只需要解冻整个cgroup。
    // 进程可以暂时留在我们的cgroup里，它们只是不再被冻结。
    // 当它们退出时，系统会自动清理。
    LOGI("Executing unfreeze on cgroup: %s", frozen_cgroup_path_.c_str());
    const std::string unfreeze_cmd = (cgroup_version_ == CgroupVersion::V2) ? "0" : "THAWED";
    return write_to_file(frozen_cgroup_state_path_, unfreeze_cmd);
}