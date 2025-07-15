// daemon/cpp/action_executor.cpp
#include "action_executor.h"
#include <android/log.h>
#include <sys/stat.h>
#include <fstream>
#include <filesystem> // C++17
#include <unistd.h>

#define LOG_TAG "cerberusd_action"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;

std::string ActionExecutor::get_freezer_path() {
    // Android P and later use /acct
    if (access("/acct/uid_0/cgroup.procs", F_OK) == 0) {
        return "/acct";
    }
    // Older versions might use /dev/cpuctl
    if (access("/dev/cpuctl/tasks", F_OK) == 0) {
        return "/dev/cpuctl";
    }
    return ""; // Not found
}

bool ActionExecutor::write_to_cgroup(const std::string& path, const std::string& value) {
    std::ofstream ofs(path);
    if (!ofs.is_open()) {
        LOGE("Failed to open cgroup file: %s. Error: %s", path.c_str(), strerror(errno));
        return false;
    }
    ofs << value;
    if (ofs.fail()) {
        LOGE("Failed to write '%s' to %s. Error: %s", value.c_str(), path.c_str(), strerror(errno));
        return false;
    }
    ofs.close();
    return true;
}

std::vector<int> ActionExecutor::get_pids_for_uid(int uid) {
    std::vector<int> pids;
    std::string uid_str = std::to_string(uid);
    for (const auto& entry : fs::directory_iterator("/proc")) {
        if (!entry.is_directory()) continue;
        
        struct stat st;
        if (stat(entry.path().c_str(), &st) == 0 && st.st_uid == uid) {
            try {
                pids.push_back(std::stoi(entry.path().filename().string()));
            } catch (const std::invalid_argument&) {
                // Ignore non-numeric directory names like 'self', 'thread-self'
            }
        }
    }
    return pids;
}


bool ActionExecutor::freeze_uid(int uid) {
    LOGI("Attempting to FREEZE uid %d", uid);
    std::string freezer_base_path = get_freezer_path();
    if (freezer_base_path.empty()) {
        LOGE("cgroup freezer path not found, cannot freeze.");
        return false;
    }

    std::string freezer_path = freezer_base_path + "/uid_" + std::to_string(uid) + "/freezer.state";
    if (access(freezer_path.c_str(), F_OK) != 0) {
        LOGW("Freezer path %s not found. Freezing via PIDs.", freezer_path.c_str());
        // Fallback: If UID-based cgroup doesn't exist, try freezing individual PIDs
        auto pids = get_pids_for_uid(uid);
        if (pids.empty()) {
            LOGW("No PIDs found for UID %d, freeze operation skipped.", uid);
            return true; // No processes to freeze
        }
        for (int pid : pids) {
            std::string pid_cgroup_tasks = freezer_base_path + "/tasks";
             if(!write_to_cgroup(pid_cgroup_tasks, std::to_string(pid))){
                 // Logged inside write_to_cgroup
             }
        }
        return write_to_cgroup(freezer_base_path + "/freezer.state", "FROZEN");
    }

    return write_to_cgroup(freezer_path, "FROZEN");
}

bool ActionExecutor::unfreeze_uid(int uid) {
    LOGI("Attempting to UNFREEZE uid %d", uid);
    std::string freezer_base_path = get_freezer_path();
    if (freezer_base_path.empty()) {
        LOGE("cgroup freezer path not found, cannot unfreeze.");
        return false;
    }

    std::string freezer_path = freezer_base_path + "/uid_" + std::to_string(uid) + "/freezer.state";
    if (access(freezer_path.c_str(), F_OK) != 0) {
         LOGW("Freezer path %s not found. Unfreezing root cgroup.", freezer_path.c_str());
         return write_to_cgroup(freezer_base_path + "/freezer.state", "THAWED");
    }
    
    return write_to_cgroup(freezer_path, "THAWED");
}