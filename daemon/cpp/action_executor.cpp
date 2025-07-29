// daemon/cpp/action_executor.cpp
#include "action_executor.h"
#include <android/log.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
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

#define LOG_TAG "cerberusd_action_v11_final"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;

ActionExecutor::ActionExecutor() {
    initialize_binder();
    initialize_cgroup();
}

ActionExecutor::~ActionExecutor() {
    cleanup_binder();
}

int ActionExecutor::freeze(const AppInstanceKey& key, const std::vector<int>& pids) {
    if (pids.empty()) return 0;

    int binder_result = handle_binder_freeze(pids, true);

    if (binder_result < 0) {
        LOGE("Binder freeze for %s failed critically. Rolling back...", key.first.c_str());
        handle_binder_freeze(pids, false);
        return -1;
    }

    if (binder_result > 0) {
        LOGW("Binder freeze for %s resulted in soft failure. Rolling back and retrying later.", key.first.c_str());
        handle_binder_freeze(pids, false);
        return 1;
    }
    
    LOGI("Binder freeze phase for %s completed. Proceeding with physical freeze.", key.first.c_str());
    bool physical_ok = freeze_cgroup(key, pids);
    if (!physical_ok) {
        LOGW("Cgroup freeze failed for %s, falling back to SIGSTOP.", key.first.c_str());
        freeze_sigstop(pids);
    }

    return 0;
}

bool ActionExecutor::unfreeze(const AppInstanceKey& key, const std::vector<int>& pids) {
    unfreeze_cgroup(key);
    unfreeze_sigstop(pids);
    handle_binder_freeze(pids, false); 
    LOGI("Unfroze instance '%s' (user %d).", key.first.c_str(), key.second);
    return true;
}

int ActionExecutor::handle_binder_freeze(const std::vector<int>& pids, bool freeze) {
    if (binder_state_.fd < 0) return 0;
    
    bool has_soft_failure = false;
    binder_freeze_info info{ .pid = 0, .enable = (uint32_t)(freeze ? 1 : 0), .timeout_ms = 100 };

    for (int pid : pids) {
        info.pid = static_cast<__u32>(pid);
        bool op_success = false;
        
        for (int retry = 0; retry < 3; ++retry) {
            if (ioctl(binder_state_.fd, BINDER_FREEZE, &info) == 0) {
                op_success = true;
                break;
            }
            
            if (errno == EAGAIN) {
                if (retry == 2) {
                    LOGW("Binder op for pid %d still has pending transactions (EAGAIN). Marking as soft failure.", pid);
                    has_soft_failure = true;
                }
                usleep(50000);
                continue;
            } 
            else if (freeze && (errno == EINVAL || errno == EPERM)) {
                LOGW("Binder op for pid %d failed (%s). Verifying UID cgroup state...", pid, strerror(errno));
                if (is_pid_frozen_by_uid_cgroup(pid)) {
                    LOGI("...Verification PASSED: UID cgroup for PID %d is frozen. Adopting state as success.", pid);
                    op_success = true;
                } else {
                    LOGE("...Verification FAILED: UID cgroup for PID %d is NOT frozen. This is a critical error.", pid);
                    return -1;
                }
                break;
            }
            else {
                LOGE("Binder op for pid %d failed with unrecoverable error: %s", pid, strerror(errno));
                return -1;
            }
        }
        
        if (!op_success && has_soft_failure) {
            continue;
        } else if (!op_success) {
            return -1;
        }
    }
    
    return has_soft_failure ? 1 : 0;
}

bool ActionExecutor::is_pid_frozen_by_uid_cgroup(int pid) {
    struct stat proc_stat;
    char path_buffer[64];
    snprintf(path_buffer, sizeof(path_buffer), "/proc/%d", pid);
    if (stat(path_buffer, &proc_stat) != 0) {
        return false;
    }
    
    uid_t uid = proc_stat.st_uid;
    
    // 构造最常见的 Android cgroup v2 UID 路径
    std::string freeze_path = "/sys/fs/cgroup/uid_" + std::to_string(uid) + "/cgroup.freeze";

    std::ifstream freeze_file(freeze_path);
    if (freeze_file.is_open()) {
        char state;
        freeze_file >> state;
        if (state == '1') {
            return true;
        }
    }
    
    return false;
}


// [核心修复] 恢复了包含 mmap 和 BINDER_GET_FROZEN_INFO 特性检测的完整初始化流程
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

    binder_state_.mapped = mmap(NULL, binder_state_.mapSize, PROT_READ, MAP_PRIVATE, binder_state_.fd, 0);
    if (binder_state_.mapped == MAP_FAILED) {
        LOGE("Binder mmap failed: %s", strerror(errno));
        close(binder_state_.fd);
        binder_state_.fd = -1;
        return false;
    }
    
    // 使用 BINDER_GET_FROZEN_INFO 作为特性探测器
    struct binder_frozen_status_info info = { .pid = (uint32_t)getpid() };
    if (ioctl(binder_state_.fd, BINDER_GET_FROZEN_INFO, &info) < 0) {
        LOGW("Kernel does not support BINDER_FREEZE feature (ioctl BINDER_GET_FROZEN_INFO failed: %s). Binder freezing disabled.", strerror(errno));
        cleanup_binder(); // 清理已打开的资源
        // binder_state_.fd 会被置为-1，后续所有binder操作都会直接跳过
        return false;
    }

    LOGI("Binder driver initialized successfully and feature is supported.");
    return true;
}

void ActionExecutor::cleanup_binder() {
    if (binder_state_.mapped && binder_state_.mapped != MAP_FAILED) {
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