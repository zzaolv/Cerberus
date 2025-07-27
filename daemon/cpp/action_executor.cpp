// daemon/cpp/action_executor.cpp
#include "action_executor.h"
#include <android/log.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
// #include <linux/android/binder.h> // 这个包含现在由 action_executor.h 完成
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

#define LOG_TAG "cerberusd_action_v6_coordinator_fix"
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

    std::vector<int> successfully_processed_pids;
    bool needs_retry = false;

    for (int pid : pids) {
        int result = handle_binder_op_with_coordination(pid, true);
        if (result == 0) {
            successfully_processed_pids.push_back(pid);
        } else if (result == 1) {
            needs_retry = true;
        } else { // result == -1
            LOGE("Critical failure during coordinated binder freeze for pid %d of %s. Rolling back.", pid, key.first.c_str());
            for (int processed_pid : successfully_processed_pids) {
                handle_binder_op_with_coordination(processed_pid, false);
            }
            return -1;
        }
    }

    if (needs_retry) {
        LOGW("Soft failure (EAGAIN) detected for %s. Rolling back all pids and retrying later.", key.first.c_str());
        for (int processed_pid : successfully_processed_pids) {
            handle_binder_op_with_coordination(processed_pid, false);
        }
        return 1;
    }
    
    LOGI("Coordinated binder freeze phase completed for %s. Proceeding with physical freeze.", key.first.c_str());
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
    
    for (int pid : pids) {
        handle_binder_op_with_coordination(pid, false);
    }
    
    LOGI("Unfroze instance '%s' (user %d).", key.first.c_str(), key.second);
    return true;
}

int ActionExecutor::handle_binder_op_with_coordination(int pid, bool freeze) {
    if (binder_state_.fd < 0) return 0;

    bool is_already_in_target_state = (is_pid_binder_frozen(pid) == freeze);
    if (is_already_in_target_state) {
        LOGI("Coordination: PID %d is already in target state (frozen=%d). Adopting state.", pid, freeze);
        return 0;
    }

    // [核心修复] 对 pid 进行显式类型转换，解决 narrowing conversion 错误
    binder_freeze_info info{ .pid = static_cast<__u32>(pid), .enable = (uint32_t)(freeze ? 1 : 0), .timeout_ms = 100 };
    for (int retry = 0; retry < 3; ++retry) {
        if (ioctl(binder_state_.fd, BINDER_FREEZE, &info) == 0) {
            return 0;
        }

        if (errno != EAGAIN || retry == 2) {
            LOGW("Coordination: ioctl(BINDER_FREEZE) for pid %d failed with: %s. Verifying actual state...", pid, strerror(errno));
            if (is_pid_binder_frozen(pid) == freeze) {
                LOGW("Coordination: ...Verification shows PID %d is now in the correct state. A competitor likely acted. Adopting state.", pid);
                return 0;
            }

            if (errno == EAGAIN) return 1;
            return -1;
        }
        usleep(50000);
    }
    return 1;
}

bool ActionExecutor::is_pid_binder_frozen(int pid) {
    if (binder_state_.fd < 0) return false;
    
    binder_frozen_status_info status_info = { .pid = static_cast<__u32>(pid) };
    if (ioctl(binder_state_.fd, BINDER_GET_FROZEN_INFO, &status_info) < 0) {
        return false;
    }
    return status_info.is_frozen != 0;
}

bool ActionExecutor::initialize_binder() {
    binder_state_.fd = open("/dev/binder", O_RDWR | O_CLOEXEC);
    if (binder_state_.fd < 0) {
        LOGE("Failed to open /dev/binder: %s", strerror(errno));
        return false;
    }

    binder_frozen_status_info info = { .pid = static_cast<__u32>(getpid()) };
    if (ioctl(binder_state_.fd, BINDER_GET_FROZEN_INFO, &info) < 0) {
        LOGE("Kernel does not support BINDER_GET_FROZEN_INFO. Coordinated strategy disabled.");
        close(binder_state_.fd);
        binder_state_.fd = -1;
        return false;
    }
    LOGI("Binder driver initialized successfully for coordinated freezing.");
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