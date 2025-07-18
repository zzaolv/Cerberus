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

#define LOG_TAG "cerberusd_action"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;

ActionExecutor::ActionExecutor() {
    if (fs::exists("/sys/fs/cgroup/cgroup.controllers")) {
        cgroup_version_ = CgroupVersion::V2;
        cgroup_root_path_ = "/sys/fs/cgroup/";
        LOGI("Detected cgroup v2. Root: %s", cgroup_root_path_.c_str());
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

std::string to_string_key(const AppInstanceKey& key) {
    std::string sanitized_package_name = key.first;
    std::replace(sanitized_package_name.begin(), sanitized_package_name.end(), '.', '_');
    return sanitized_package_name + "_" + std::to_string(key.second);
}

std::string ActionExecutor::get_instance_cgroup_path(const AppInstanceKey& key) const {
    return cgroup_root_path_ + "cerberus_" + to_string_key(key);
}

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

bool ActionExecutor::write_to_file(const std::string& path, const std::string& value) {
    std::ofstream ofs(path, std::ios_base::app);
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

bool ActionExecutor::move_pids_to_default_cgroup(const std::vector<int>& pids) {
    return move_pids_to_cgroup(pids, cgroup_root_path_);
}

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

bool ActionExecutor::unfreeze_and_cleanup(const AppInstanceKey& key) {
    if (cgroup_version_ == CgroupVersion::UNKNOWN) return false;

    std::string instance_path = get_instance_cgroup_path(key);
    if (!fs::exists(instance_path)) return true;

    std::string state_file = instance_path + ((cgroup_version_ == CgroupVersion::V2) ? "/cgroup.freeze" : "/freezer.state");
    const std::string unfreeze_cmd = (cgroup_version_ == CgroupVersion::V2) ? "0" : "THAWED";
    if (!write_to_file(state_file, unfreeze_cmd)) {
        LOGW("Failed to unfreeze cgroup for '%s'. Proceeding with cleanup.", to_string_key(key).c_str());
    }

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
    
    if (!remove_instance_cgroup(instance_path)) {
        LOGW("Failed to remove cgroup for '%s'. It may be removed on next reboot.", to_string_key(key).c_str());
    }

    LOGI("Successfully unfroze and cleaned up instance '%s'.", to_string_key(key).c_str());
    return true;
}

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