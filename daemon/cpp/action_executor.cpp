// daemon/cpp/action_executor.cpp
#include "action_executor.h"
#include <android/log.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <linux/android/binder.h>
#include <fstream>
#include <filesystem>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <sstream>
#include <csignal>
#include <algorithm>
#include <fcntl.h>
#include <vector>

#define LOG_TAG "cerberusd_action_v5_strategic"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;

// 辅助函数：通过PID获取UID
static int get_uid_from_pid(int pid) {
    char path_buffer[64];
    snprintf(path_buffer, sizeof(path_buffer), "/proc/%d", pid);
    struct stat st;
    if (stat(path_buffer, &st) != 0) return -1;
    return st.st_uid;
}

ActionExecutor::ActionExecutor() {
    initialize_binder();
    initialize_cgroup();
}

ActionExecutor::~ActionExecutor() {
    cleanup_binder();
}

// [核心重构] 采用新的“分级冻结”策略
int ActionExecutor::freeze(const AppInstanceKey& key, const std::vector<int>& pids) {
    if (pids.empty()) return 0;

    // 步骤 1: 识别主进程
    // 通常一个应用实例的所有进程UID相同。我们先获取一个基准UID。
    int target_uid = -1;
    if (!pids.empty()) {
        target_uid = get_uid_from_pid(pids[0]);
    }
    if (target_uid == -1) {
        LOGE("Could not get UID for pids of %s. Aborting freeze.", key.first.c_str());
        return -1; // 致命失败
    }
    
    // 找出所有与应用UID完全匹配的进程作为“主进程组”
    std::vector<int> main_pids;
    std::vector<int> secondary_pids;
    for (int pid : pids) {
        if (get_uid_from_pid(pid) == target_uid) {
            main_pids.push_back(pid);
        } else {
            secondary_pids.push_back(pid);
        }
    }
    
    // 步骤 2: 对主进程组执行严格的Binder冻结
    LOGI("Freezing main pids for %s...", key.first.c_str());
    int main_binder_result = handle_binder_freeze_strict(main_pids, true);

    switch (main_binder_result) {
        case 1: // 软失败
            LOGW("Strict binder freeze on main pids of %s failed (EAGAIN). Rolling back and retrying later.", key.first.c_str());
            handle_binder_freeze_lenient(main_pids, false); // 回滚
            return 1; // 向上层报告需要重试
        case -1: // 硬失败或致命失败
            LOGE("Strict binder freeze on main pids of %s failed critically. Aborting.", key.first.c_str());
            handle_binder_freeze_lenient(main_pids, false); // 尽力回滚
            return -1; // 向上层报告彻底失败
    }

    // 步骤 3: 对次要进程组（如果有）执行宽容的Binder冻结
    if (!secondary_pids.empty()) {
        LOGI("Freezing secondary pids for %s...", key.first.c_str());
        handle_binder_freeze_lenient(secondary_pids, true);
    }
    
    // 步骤 4: 执行物理冻结
    LOGI("Binder freeze phase completed for %s. Proceeding with physical freeze.", key.first.c_str());
    bool physical_ok = freeze_cgroup(key, pids);
    if (!physical_ok) {
        LOGW("Cgroup freeze failed for %s, falling back to SIGSTOP.", key.first.c_str());
        freeze_sigstop(pids);
    }

    return 0; // 最终成功
}

bool ActionExecutor::unfreeze(const AppInstanceKey& key, const std::vector<int>& pids) {
    unfreeze_cgroup(key);
    unfreeze_sigstop(pids);
    // 解冻时，对所有进程都执行宽容的解冻即可
    handle_binder_freeze_lenient(pids, false); 
    LOGI("Unfroze instance '%s' (user %d).", key.first.c_str(), key.second);
    return true;
}

// [核心新增] 严格的Binder冻结，用于主进程。任何失败都会中止并返回错误。
int ActionExecutor::handle_binder_freeze_strict(const std::vector<int>& pids, bool freeze) {
    if (binder_state_.fd < 0 || pids.empty()) return 0;
    
    std::vector<int> successfully_processed_pids;
    binder_freeze_info info{ .pid = 0, .enable = (uint32_t)(freeze ? 1 : 0), .timeout_ms = 100 };

    for (int pid : pids) {
        info.pid = pid;
        bool op_success = false;
        for (int retry = 0; retry < 3; ++retry) {
            if (ioctl(binder_state_.fd, BINDER_FREEZE, &info) == 0) {
                op_success = true;
                break;
            }
            if (errno != EAGAIN || retry == 2) {
                // 任何非EAGAIN的错误，或EAGAIN重试耗尽，都视为失败
                LOGW("Strict binder op for pid %d failed: %s", info.pid, strerror(errno));
                return (errno == EAGAIN) ? 1 : -1; // 1 for soft fail, -1 for hard fail
            }
            usleep(50000); 
        }
        if (op_success) {
            successfully_processed_pids.push_back(pid);
        }
    }
    return 0; // 全部成功
}

// [核心新增] 宽容的Binder冻结，用于次要进程和解冻。忽略EAGAIN错误。
void ActionExecutor::handle_binder_freeze_lenient(const std::vector<int>& pids, bool freeze) {
    if (binder_state_.fd < 0 || pids.empty()) return;

    binder_freeze_info info{ .pid = 0, .enable = (uint32_t)(freeze ? 1 : 0), .timeout_ms = 100 };

    for (int pid : pids) {
        info.pid = pid;
        if (ioctl(binder_state_.fd, BINDER_FREEZE, &info) < 0) {
            if (errno == EAGAIN) {
                LOGW("Lenient binder op for pid %d has pending transactions (EAGAIN). Ignoring.", info.pid);
            } else {
                LOGE("Lenient binder op for pid %d failed with error: %s", info.pid, strerror(errno));
            }
        }
    }
}


bool ActionExecutor::initialize_binder() {
    binder_state_.fd = open("/dev/binder", O_RDWR | O_CLOEXEC);
    if (binder_state_.fd < 0) {
        LOGE("Failed to open /dev/binder: %s", strerror(errno));
        return false;
    }
    binder_version version;
    if (ioctl(binder_state_.fd, BINDER_VERSION, &version) < 0 || version.protocol_version != BINDER_CURRENT_PROTOCOL_VERSION) {
        LOGE("Binder version mismatch or ioctl failed. Required: %d", BINDER_CURRENT_PROTOCOL_VERSION);
        close(binder_state_.fd);
        binder_state_.fd = -1;
        return false;
    }
    LOGI("Binder driver initialized successfully for freezing.");
    return true;
}

void ActionExecutor::cleanup_binder() {
    if (binder_state_.mapped) {
        munmap(binder_state_.mapped, binder_state_.mapSize);
        binder_state_.mapped = nullptr;
    }
    if (binder_state_.fd >= 0) {
        close(binder_state_.fd);
        binder_state_.fd = -1;
    }
}

bool ActionExecutor::initialize_cgroup() {
    if (fs::exists("/sys/fs/cgroup/cgroup.controllers")) {
        cgroup_version_ = CgroupVersion::V2;
        cgroup_root_path_ = "/sys/fs/cgroup/";
        LOGI("Detected cgroup v2. Root: %s", cgroup_root_path_.c_str());
        if(!write_to_file(cgroup_root_path_ + "cgroup.subtree_control", "+freezer")) {
             LOGW("Failed to enable freezer controller in root cgroup. It might be already enabled.");
        }
        return true;
    }
    LOGW("cgroup v2 not detected. Cgroup freezer disabled.");
    cgroup_version_ = CgroupVersion::UNKNOWN;
    return false;
}

std::string ActionExecutor::get_instance_cgroup_path(const AppInstanceKey& key) const {
    std::string sanitized_package_name = key.first;
    std::replace(sanitized_package_name.begin(), sanitized_package_name.end(), '.', '_');
    return cgroup_root_path_ + "cerberus_" + sanitized_package_name + "_" + std::to_string(key.second);
}

bool ActionExecutor::freeze_cgroup(const AppInstanceKey& key, const std::vector<int>& pids) {
    if (cgroup_version_ != CgroupVersion::V2) return false;
    std::string instance_path = get_instance_cgroup_path(key);
    if (!create_instance_cgroup(instance_path)) return false;
    if (!move_pids_to_cgroup(pids, instance_path)) {
        LOGE("Failed to move pids for '%s' to its cgroup.", key.first.c_str());
        return false;
    }
    if (!write_to_file(instance_path + "/cgroup.freeze", "1")) {
        LOGE("Failed to write '1' to cgroup.freeze for '%s'.", key.first.c_str());
        return false;
    }
    return true;
}

bool ActionExecutor::unfreeze_cgroup(const AppInstanceKey& key) {
    if (cgroup_version_ != CgroupVersion::V2) return true;
    std::string instance_path = get_instance_cgroup_path(key);
    if (!fs::exists(instance_path)) return true;
    write_to_file(instance_path + "/cgroup.freeze", "0");
    std::string procs_file = instance_path + "/cgroup.procs";
    std::vector<int> pids_to_move;
    std::ifstream ifs(procs_file);
    int pid;
    while(ifs >> pid) { pids_to_move.push_back(pid); }
    if (!pids_to_move.empty()) { move_pids_to_default_cgroup(pids_to_move); }
    remove_instance_cgroup(instance_path);
    return true;
}

void ActionExecutor::freeze_sigstop(const std::vector<int>& pids) {
    for (int pid : pids) {
        if (kill(pid, SIGSTOP) < 0) {
            LOGW("Failed to send SIGSTOP to pid %d: %s", pid, strerror(errno));
        }
    }
}

void ActionExecutor::unfreeze_sigstop(const std::vector<int>& pids) {
    for (int pid : pids) {
        if (kill(pid, SIGCONT) < 0) { }
    }
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
    if (rmdir(path.c_str()) != 0) {
        LOGW("Cannot remove cgroup '%s': %s. It might not be empty.", path.c_str(), strerror(errno));
        return false;
    }
    return true;
}

bool ActionExecutor::move_pids_to_cgroup(const std::vector<int>& pids, const std::string& cgroup_path) {
    std::string procs_file = cgroup_path + "/cgroup.procs";
    std::ofstream ofs(procs_file, std::ios_base::app);
    if (!ofs.is_open()) {
        LOGE("Failed to open '%s' to move pids: %s", procs_file.c_str(), strerror(errno));
        return false;
    }
    for (int pid : pids) {
        ofs << pid << std::endl;
        if (ofs.fail()) {
            LOGE("Error writing pid %d to %s", pid, procs_file.c_str());
            return false;
        }
    }
    return true;
}

bool ActionExecutor::move_pids_to_default_cgroup(const std::vector<int>& pids) {
    return move_pids_to_cgroup(pids, cgroup_root_path_);
}

bool ActionExecutor::write_to_file(const std::string& path, const std::string& value) {
    std::ofstream ofs(path);
    if (!ofs.is_open()) {
        if (path.find("subtree_control") == std::string::npos) {
            LOGE("Failed to open file '%s' for writing: %s", path.c_str(), strerror(errno));
        }
        return false;
    }
    ofs << value;
    if (ofs.fail()) {
        LOGE("Failed to write '%s' to '%s': %s", value.c_str(), path.c_str(), strerror(errno));
        return false;
    }
    return true;
}