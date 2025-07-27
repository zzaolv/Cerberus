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

#define LOG_TAG "cerberusd_action_v3_robust"
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

// =========================================================================================
// [核心重构] `freeze` 函数现在是战术指挥官
// =========================================================================================
int ActionExecutor::freeze(const AppInstanceKey& key, const std::vector<int>& pids) {
    if (pids.empty()) return 0; // 成功

    // 步骤 1: 调用情报官，获取Binder冻结结果
    int binder_result = handle_binder_freeze(pids, true);

    // 步骤 2: 根据情报执行战术决策
    switch (binder_result) {
        case 0: { // Binder全部成功
            LOGI("Binder freeze successful for %s. Proceeding with physical freeze.", key.first.c_str());
            // 优先cgroup，SIGSTOP兜底
            bool physical_ok = freeze_cgroup(key, pids);
            if (!physical_ok) {
                LOGW("Cgroup freeze failed for %s, falling back to SIGSTOP.", key.first.c_str());
                freeze_sigstop(pids);
            }
            return 0; // 最终成功
        }
        case 1: // 软失败 (EAGAIN)
            LOGW("Binder freeze for %s resulted in soft failure (EAGAIN). Will retry later.", key.first.c_str());
            // 无需做任何事，直接向上层报告需要重试
            return 1; // 需要重试
        
        case -1: // 硬失败 (EINVAL/EPERM)
            LOGW("Binder freeze for %s resulted in hard failure (EINVAL/EPERM). Fallback to SIGSTOP only.", key.first.c_str());
            // 智能降级: 放弃有风险的cgroup冻结，直接使用更强力的SIGSTOP
            freeze_sigstop(pids);
            return 0; // 接受降级后的成功

        case -2: // 致命失败
        default:
            LOGE("Binder freeze for %s resulted in fatal failure. Aborting freeze.", key.first.c_str());
            // 无法继续，报告彻底失败
            return -1; // 彻底失败
    }
}

bool ActionExecutor::unfreeze(const AppInstanceKey& key, const std::vector<int>& pids) {
    unfreeze_cgroup(key);
    unfreeze_sigstop(pids);
    handle_binder_freeze(pids, false); // 尝试解冻Binder，即使失败也要继续
    LOGI("Unfroze instance '%s' (user %d).", key.first.c_str(), key.second);
    return true;
}

// =========================================================================================
// [核心重构] `handle_binder_freeze` 函数现在是情报官
// =========================================================================================
int ActionExecutor::handle_binder_freeze(const std::vector<int>& pids, bool freeze) {
    if (binder_state_.fd < 0 || pids.empty()) return 0;

    bool has_soft_failure = false;
    bool has_hard_failure = false;
    std::vector<int> successfully_frozen_pids;

    binder_freeze_info info{ .pid = 0, .enable = (uint32_t)(freeze ? 1 : 0), .timeout_ms = 100 };

    for (int pid : pids) {
        info.pid = pid;
        bool op_success = false;
        
        // --- 微重试循环 ---
        for (int retry = 0; retry < 5; ++retry) {
            if (ioctl(binder_state_.fd, BINDER_FREEZE, &info) == 0) {
                op_success = true;
                break; // 成功，退出重试循环
            }
            
            // 如果不是EAGAIN，或者这是最后一次重试，则处理错误并跳出
            if (errno != EAGAIN || retry == 2) {
                switch (errno) {
                    case EAGAIN:
                        has_soft_failure = true;
                        LOGW("Binder op for pid %d has pending transactions (EAGAIN) after all retries.", info.pid);
                        break;
                    case EINVAL:
                    case EPERM:
                        has_hard_failure = true;
                        LOGW("Binder op for pid %d failed with hard error: %s", info.pid, strerror(errno));
                        break;
                    default:
                        LOGE("Binder op for pid %d failed with fatal error: %s", info.pid, strerror(errno));
                        if (freeze && !successfully_frozen_pids.empty()) {
                            LOGE("Rolling back fatally failed binder freeze op...");
                            handle_binder_freeze(successfully_frozen_pids, false);
                        }
                        return -2; // 致命失败
                }
                goto next_pid; // 跳到外层循环的下一个pid
            }
            
            // 等待50毫秒再重试
            usleep(50000); 
        }

        if (op_success && freeze) {
            successfully_frozen_pids.push_back(pid);
        }
        
        next_pid:;
    }

    if (freeze && (has_soft_failure || has_hard_failure)) {
        if (!successfully_frozen_pids.empty()) {
            LOGW("Rolling back partially successful binder freeze due to errors...");
            handle_binder_freeze(successfully_frozen_pids, false);
        }
    }

    if (has_hard_failure) return -1;
    if (has_soft_failure) return 1;
    
    return 0;
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