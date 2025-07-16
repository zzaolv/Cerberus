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

constexpr int PER_USER_RANGE = 100000;

ActionExecutor::ActionExecutor() {
    // 检测系统 cgroup 版本
    if (fs::exists("/sys/fs/cgroup/cgroup.controllers")) {
        cgroup_version_ = CgroupVersion::V2;
        LOGI("Detected cgroup v2.");
    } else if (fs::exists("/sys/fs/cgroup/freezer")) {
        cgroup_version_ = CgroupVersion::V1;
        LOGI("Detected cgroup v1.");
    } else {
        cgroup_version_ = CgroupVersion::UNKNOWN;
        LOGE("Could not determine cgroup version.");
    }
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
    ofs.close();
    return true;
}

bool ActionExecutor::freeze_app(const std::string& package_name, int user_id) {
    if (cgroup_version_ == CgroupVersion::V2) {
        return freeze_app_v2(package_name, user_id);
    } else if (cgroup_version_ == CgroupVersion::V1) {
        // 在 v1 中，我们仍然基于 UID 操作
        int app_id_guess = 10000 + (std::hash<std::string>{}(package_name) % 10000);
        int uid = user_id * PER_USER_RANGE + app_id_guess;
        LOGW("Freezing on cgroup v1 using guessed UID %d for %s", uid, package_name.c_str());
        return freeze_uid_v1(uid);
    }
    return false;
}

bool ActionExecutor::unfreeze_app(const std::string& package_name, int user_id) {
    if (cgroup_version_ == CgroupVersion::V2) {
        return unfreeze_app_v2(package_name, user_id);
    } else if (cgroup_version_ == CgroupVersion::V1) {
        int app_id_guess = 10000 + (std::hash<std::string>{}(package_name) % 10000);
        int uid = user_id * PER_USER_RANGE + app_id_guess;
        LOGW("Unfreezing on cgroup v1 using guessed UID %d for %s", uid, package_name.c_str());
        return unfreeze_uid_v1(uid);
    }
    return false;
}


// --- cgroup v2 implementation ---
std::string get_app_cgroup_path_v2(const std::string& package_name, int user_id) {
    return "/sys/fs/cgroup/uid_" + std::to_string(user_id * PER_USER_RANGE) + "/pid_0/uid_" + std::to_string(user_id * PER_USER_RANGE + 10000) + "/" + package_name;
    // Note: This path is a common pattern but might vary. A more robust solution
    // would read a process's cgroup file to find the exact path.
}

bool ActionExecutor::freeze_app_v2(const std::string& package_name, int user_id) {
    LOGI("Freezing (v2) app %s (user %d)", package_name.c_str(), user_id);
    // 在cgroup v2中，路径通常是/sys/fs/cgroup/uid_XXXX/....
    // 这是一个简化的假设，更健壮的方法是从一个存活的进程读取
    std::string path = "/sys/fs/cgroup/user.slice/user-" + std::to_string(user_id) + ".slice/apps.slice/" + package_name + "/cgroup.freeze";
    if (!fs::exists(path)) {
        // Fallback path for some OEM roms
        path = "/sys/fs/cgroup/uid_" + std::to_string(user_id * PER_USER_RANGE) + "/cgroup.freeze";
    }
     if (!fs::exists(path)) {
        LOGE("cgroup v2 freeze path not found for %s", package_name.c_str());
        return false;
    }
    return write_to_file(path, "1");
}

bool ActionExecutor::unfreeze_app_v2(const std::string& package_name, int user_id) {
    LOGI("Unfreezing (v2) app %s (user %d)", package_name.c_str(), user_id);
    std::string path = "/sys/fs/cgroup/user.slice/user-" + std::to_string(user_id) + ".slice/apps.slice/" + package_name + "/cgroup.freeze";
    if (!fs::exists(path)) {
       path = "/sys/fs/cgroup/uid_" + std::to_string(user_id * PER_USER_RANGE) + "/cgroup.freeze";
    }
    if (!fs::exists(path)) {
        LOGE("cgroup v2 unfreeze path not found for %s", package_name.c_str());
        return false;
    }
    return write_to_file(path, "0");
}


// --- cgroup v1 implementation ---
bool ActionExecutor::freeze_uid_v1(int uid) {
    LOGI("Freezing (v1) uid %d", uid);
    std::string freezer_path = "/sys/fs/cgroup/freezer/uid_" + std::to_string(uid) + "/freezer.state";
    if (!fs::exists(freezer_path)) {
        LOGE("cgroup v1 freeze path not found: %s", freezer_path.c_str());
        return false;
    }
    return write_to_file(freezer_path, "FROZEN");
}

bool ActionExecutor::unfreeze_uid_v1(int uid) {
    LOGI("Unfreezing (v1) uid %d", uid);
    std::string freezer_path = "/sys/fs/cgroup/freezer/uid_" + std::to_string(uid) + "/freezer.state";
    if (!fs::exists(freezer_path)) {
        LOGE("cgroup v1 unfreeze path not found: %s", freezer_path.c_str());
        return false;
    }
    return write_to_file(freezer_path, "THAWED");
}