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

#define LOG_TAG "cerberusd_action_v2"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;

// --- Constructor & Destructor ---
ActionExecutor::ActionExecutor() {
    initialize_binder();
    initialize_cgroup();
}

ActionExecutor::~ActionExecutor() {
    cleanup_binder();
}

// --- Public Methods ---
bool ActionExecutor::freeze(const AppInstanceKey& key, const std::vector<int>& pids, FreezeMethod method) {
    if (pids.empty()) return true;

    // 步骤 1: Binder 冻结
    if (handle_binder_freeze(pids, true) < 0) {
        LOGE("Binder freeze failed for %s. Aborting physical freeze.", key.first.c_str());
        // 尝试回滚已经冻结的binder
        handle_binder_freeze(pids, false);
        return false;
    }

    // 步骤 2: 物理冻结
    bool physical_freeze_ok = false;
    switch (method) {
        case FreezeMethod::CGROUP_V2:
            physical_freeze_ok = freeze_cgroup(key, pids);
            break;
        case FreezeMethod::SIGSTOP:
            freeze_sigstop(pids);
            physical_freeze_ok = true; // SIGSTOP 总是“成功”
            break;
    }

    if (!physical_freeze_ok) {
        LOGE("Physical freeze failed for %s. Rolling back binder freeze.", key.first.c_str());
        handle_binder_freeze(pids, false);
        return false;
    }
    
    LOGI("Successfully froze instance '%s' (user %d) with %zu pids using %s.", 
         key.first.c_str(), key.second, pids.size(), method == FreezeMethod::CGROUP_V2 ? "cgroup" : "SIGSTOP");
    return true;
}

bool ActionExecutor::unfreeze(const AppInstanceKey& key, const std::vector<int>& pids) {
    // 步骤 1: 物理解冻 (必须先做，否则进程无法处理binder消息)
    bool cgroup_unfrozen = unfreeze_cgroup(key);
    // SIGSTOP解冻总是执行，即使没有pids，以防万一
    unfreeze_sigstop(pids);

    if (!cgroup_unfrozen) {
        LOGW("Cgroup unfreeze seems to have failed for %s, but proceeding with binder unfreeze.", key.first.c_str());
    }

    // 步骤 2: Binder 解冻
    if (handle_binder_freeze(pids, false) < 0) {
        LOGE("Binder unfreeze failed for %s.", key.first.c_str());
        // 即使binder解冻失败，物理层也已解冻，所以不返回false
    }
    
    LOGI("Unfroze instance '%s' (user %d).", key.first.c_str(), key.second);
    return true;
}

// --- Binder Freeze Implementation ---
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

    binder_frozen_status_info info = { (uint32_t)getpid(), 0, 0 };
    if (ioctl(binder_state_.fd, BINDER_GET_FROZEN_INFO, &info) < 0) {
        LOGW("Kernel does not support BINDER_FREEZE feature. Binder freezing disabled.");
        cleanup_binder();
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

int ActionExecutor::handle_binder_freeze(const std::vector<int>& pids, bool freeze) {
    if (binder_state_.fd < 0 || pids.empty()) return 0;

    binder_freeze_info info{ .pid = 0, .enable = (uint32_t)(freeze ? 1 : 0), .timeout_ms = 100 };

    for (size_t i = 0; i < pids.size(); ++i) {
        info.pid = pids[i];
        if (ioctl(binder_state_.fd, BINDER_FREEZE, &info) < 0) {
            if (errno == EAGAIN) { // 事务未处理完，这是可接受的失败
                 LOGW("Binder freeze for pid %d has pending transactions (EAGAIN).", info.pid);
            } else {
                LOGE("Failed to %s binder for pid %d: %s", freeze ? "freeze" : "unfreeze", info.pid, strerror(errno));
                if(freeze) return -info.pid; // 如果是冻结失败，返回失败的pid
            }
        }
    }
    return 0; // 成功或非致命错误
}

// --- Cgroup v2 Implementation ---
bool ActionExecutor::initialize_cgroup() {
    if (fs::exists("/sys/fs/cgroup/cgroup.controllers")) {
        cgroup_version_ = CgroupVersion::V2;
        cgroup_root_path_ = "/sys/fs/cgroup/";
        LOGI("Detected cgroup v2. Root: %s", cgroup_root_path_.c_str());
        // 确保 freezer controller 在根cgroup可用
        if(!write_to_file(cgroup_root_path_ + "cgroup.subtree_control", "+freezer")) {
             LOGE("Failed to enable freezer controller in root cgroup.");
             cgroup_version_ = CgroupVersion::UNKNOWN;
             return false;
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
    if (cgroup_version_ != CgroupVersion::V2) return true; // 如果不支持cgroup，解冻操作自然是“成功”的

    std::string instance_path = get_instance_cgroup_path(key);
    if (!fs::exists(instance_path)) return true;

    if (!write_to_file(instance_path + "/cgroup.freeze", "0")) {
        LOGE("Failed to write '0' to cgroup.freeze for '%s'.", key.first.c_str());
        // Don't return false, try to cleanup anyway
    }

    std::string procs_file = instance_path + "/cgroup.procs";
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
    return true;
}

// --- SIGSTOP Implementation ---
void ActionExecutor::freeze_sigstop(const std::vector<int>& pids) {
    for (int pid : pids) {
        if (kill(pid, SIGSTOP) < 0) {
            LOGW("Failed to send SIGSTOP to pid %d: %s", pid, strerror(errno));
        }
    }
}

void ActionExecutor::unfreeze_sigstop(const std::vector<int>& pids) {
    for (int pid : pids) {
        if (kill(pid, SIGCONT) < 0) {
            // This can happen if the process died, so it's not a critical error.
        }
    }
}

// --- Utility Functions ---
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
        if (rmdir(path.c_str()) != 0) {
            // rmdir only works on empty directories
            LOGW("Cannot remove cgroup '%s': %s. It might not be empty.", path.c_str(), strerror(errno));
            return false;
        }
        return true;
    } catch (const fs::filesystem_error& e) {
        LOGE("Failed to remove cgroup '%s': %s", path.c_str(), e.what());
        return false;
    }
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
        // Log silently for subtree_control, as it can fail legitimately if already set
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