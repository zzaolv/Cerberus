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
#include <optional>

#define LOG_TAG "cerberusd_action_v19_oom_protect" // 版本号更新
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;

// [新增] 定义OOM Score常量
constexpr int PROTECTED_OOM_SCORE_ADJ = 201; // 一个受保护但非系统级的值
constexpr int CACHED_OOM_SCORE_ADJ = 900;    // 恢复为典型的缓存应用值

ActionExecutor::ActionExecutor() {
    initialize_binder();
    initialize_cgroup();
}

ActionExecutor::~ActionExecutor() {
    cleanup_binder();
}

int ActionExecutor::freeze(const AppInstanceKey& key, const std::vector<int>& pids) {
    if (pids.empty()) return 0;

    int final_result = -1;

    // --- 阶段 1: Binder 冻结 ---
    int binder_result = handle_binder_freeze(pids, true);
    if (binder_result == -1) {
        LOGE("Binder freeze for %s failed critically. Rolling back...", key.first.c_str());
        handle_binder_freeze(pids, false);
        return -1;
    }
    if (binder_result == 2) {
        LOGW("Binder freeze for %s resisted (EAGAIN). Continuing with Cgroup freeze attempt anyway.", key.first.c_str());
    }

    // --- 阶段 2: Cgroup v2 冻结 ---
    LOGI("Binder phase complete for %s. Attempting Cgroup v2 freeze.", key.first.c_str());
    bool cgroup_ok = freeze_cgroup(key, pids);

    if (cgroup_ok) {
        bool verified = false;
        const int VERIFICATION_ATTEMPTS = 4;
        const useconds_t VERIFICATION_INTERVAL_US = 50000; // 50ms

        for (int i = 0; i < VERIFICATION_ATTEMPTS; ++i) {
            if (is_cgroup_frozen(key)) {
                verified = true;
                break;
            }
            if (i < VERIFICATION_ATTEMPTS - 1) {
                usleep(VERIFICATION_INTERVAL_US);
            }
        }

        if (verified) {
            LOGI("Cgroup freeze for %s succeeded and verified.", key.first.c_str());
            final_result = 0;
        } else {
             LOGW("Cgroup freeze for %s verification failed! Escalating to SIGSTOP.", key.first.c_str());
             unfreeze_cgroup(key);
             freeze_sigstop(pids);
             final_result = 1;
        }
    } else {
        LOGW("Cgroup freeze attempt failed for %s. Falling back to SIGSTOP.", key.first.c_str());
        unfreeze_cgroup(key); 
        freeze_sigstop(pids);
        final_result = 1;
    }

    // --- [新增] 阶段 3: OOM Score 保护 ---
    if (final_result == 0 || final_result == 1) {
        LOGI("CPU freeze for %s successful. Applying memory protection (OOM Score).", key.first.c_str());
        adjust_main_process_oom_score(pids, true);
    }

    return final_result;
}

bool ActionExecutor::unfreeze(const AppInstanceKey& key, const std::vector<int>& pids) {
    // --- [新增] 阶段 1: 恢复 OOM Score ---
    // 必须在任何解冻动作之前执行，以确保即使解冻失败，OOM Score也能恢复
    adjust_main_process_oom_score(pids, false);
    
    // --- 阶段 2: 解冻 CPU 活动 ---
    unfreeze_cgroup(key);
    unfreeze_sigstop(pids);
    handle_binder_freeze(pids, false);
    
    LOGI("Unfroze instance '%s' (user %d).", key.first.c_str(), key.second);
    return true;
}

// [新增] 读取指定PID的oom_score_adj
std::optional<int> ActionExecutor::read_oom_score_adj(int pid) {
    std::string path = "/proc/" + std::to_string(pid) + "/oom_score_adj";
    std::ifstream file(path);
    if (!file.is_open()) {
        return std::nullopt;
    }
    int score;
    file >> score;
    if (file.fail()) {
        return std::nullopt;
    }
    return score;
}

// [新增] 调整主进程的OOM Score
void ActionExecutor::adjust_main_process_oom_score(const std::vector<int>& pids, bool protect) {
    if (pids.empty()) return;

    int main_pid = -1;
    int min_score = 1001; // 高于最大值1000

    // 1. 找到主进程 (oom_score_adj 最低的那个)
    for (int pid : pids) {
        auto score_opt = read_oom_score_adj(pid);
        if (score_opt) {
            if (*score_opt < min_score) {
                min_score = *score_opt;
                main_pid = pid;
            }
        }
    }

    if (main_pid == -1) {
        LOGW("OOM: Could not find any process with a readable oom_score_adj to adjust.");
        return;
    }

    // 2. 根据操作类型调整 OOM Score
    int target_score = protect ? PROTECTED_OOM_SCORE_ADJ : CACHED_OOM_SCORE_ADJ;
    std::string action_str = protect ? "Protecting" : "Restoring";
    
    // 如果是保护操作，并且当前值已经很低了，就不再调整，避免干扰系统服务
    if (protect && min_score < target_score) {
        LOGI("OOM: Main process %d already has a low score (%d). Skipping protection.", main_pid, min_score);
        return;
    }
    
    // 如果是恢复操作，且当前值不是我们设置的保护值，也跳过，避免错误地修改AMS的正常设置
    if (!protect && min_score != PROTECTED_OOM_SCORE_ADJ) {
        LOGD("OOM: Main process %d score (%d) was not set by us. Skipping restore.", main_pid, min_score);
        return;
    }

    std::string path = "/proc/" + std::to_string(main_pid) + "/oom_score_adj";
    if (!write_to_file(path, std::to_string(target_score))) {
        LOGW("OOM: Failed to write to %s for PID %d.", path.c_str(), main_pid);
    } else {
        LOGI("OOM: %s main process %d. Set oom_score_adj from %d to %d.", action_str.c_str(), main_pid, min_score, target_score);
    }
}


// ... 文件中其余所有方法 (handle_binder_freeze, is_cgroup_frozen, etc.) 保持不变 ...
int ActionExecutor::handle_binder_freeze(const std::vector<int>& pids, bool freeze) {
    if (binder_state_.fd < 0) return 0;

    const int BINDER_FREEZE_MAX_ATTEMPTS = 5;
    const useconds_t BINDER_FREEZE_RETRY_WAIT_US = 70000;

    bool has_soft_failure = false;
    binder_freeze_info info{ .pid = 0, .enable = (uint32_t)(freeze ? 1 : 0), .timeout_ms = 100 };

    for (int pid : pids) {
        info.pid = static_cast<__u32>(pid);
        bool op_success = false;

        for (int attempt = 0; attempt < BINDER_FREEZE_MAX_ATTEMPTS; ++attempt) {
            if (ioctl(binder_state_.fd, BINDER_FREEZE, &info) == 0) {
                op_success = true;
                break;
            }

            if (errno == EAGAIN) {
                if (attempt == BINDER_FREEZE_MAX_ATTEMPTS - 1) {
                    LOGW("Binder op for pid %d still has pending transactions (EAGAIN) after %d attempts. Marking as soft failure.", pid, BINDER_FREEZE_MAX_ATTEMPTS);
                    has_soft_failure = true;
                } else {
                    LOGD("Binder op for pid %d got EAGAIN, retrying in %d ms... (Attempt %d/%d)", pid, BINDER_FREEZE_RETRY_WAIT_US / 1000, attempt + 1, BINDER_FREEZE_MAX_ATTEMPTS);
                }
                usleep(BINDER_FREEZE_RETRY_WAIT_US);
                continue;
            }
            else if (freeze && (errno == EINVAL || errno == EPERM)) {
                LOGW("Cannot freeze pid %d (error: %s), likely a privileged process. Skipping this PID.", pid, strerror(errno));
                op_success = true;
                break;
            }
            else {
                LOGE("Binder op for pid %d failed with unrecoverable error: %s", pid, strerror(errno));
                return -1;
            }
        }

        if (!op_success && !has_soft_failure) {
            return -1;
        }
    }

    return has_soft_failure ? 2 : 0;
}

bool ActionExecutor::is_cgroup_frozen(const AppInstanceKey& key) {
    if (cgroup_version_ != CgroupVersion::V2) return false;
    std::string freeze_path = get_instance_cgroup_path(key) + "/cgroup.freeze";

    std::ifstream freeze_file(freeze_path);
    if (!freeze_file.is_open()) {
        return false; 
    }
    char state = '0';
    freeze_file >> state;
    return state == '1';
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

    binder_state_.mapped = mmap(NULL, binder_state_.mapSize, PROT_READ, MAP_PRIVATE, binder_state_.fd, 0);
    if (binder_state_.mapped == MAP_FAILED) {
        LOGE("Binder mmap failed: %s", strerror(errno));
        close(binder_state_.fd);
        binder_state_.fd = -1;
        return false;
    }

    struct binder_frozen_status_info info = { .pid = (uint32_t)getpid() };
    if (ioctl(binder_state_.fd, BINDER_GET_FROZEN_INFO, &info) < 0) {
        LOGW("Kernel does not support BINDER_FREEZE feature (ioctl failed: %s). Binder freezing disabled.", strerror(errno));
        cleanup_binder();
        return false;
    }

    LOGI("Binder driver initialized successfully and BINDER_FREEZE feature is supported.");
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

    if (fs::exists(instance_path)) {
        LOGW("Residual cgroup found for %s. Attempting cleanup before freeze.", key.first.c_str());
        unfreeze_cgroup(key);
    }

    if (!create_instance_cgroup(instance_path)) {
        LOGE("Failed to create cgroup '%s' even after cleanup attempt.", instance_path.c_str());
        return false;
    }

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
    if (!pids_to_move.empty()) { 
        move_pids_to_default_cgroup(pids_to_move); 
    }
    
    usleep(50000); 

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
        LOGW("Cannot remove cgroup '%s': %s. It might not be empty yet.", path.c_str(), strerror(errno));
        return false;
    }
    LOGI("Successfully removed cgroup '%s'", path.c_str());
    return true;
}

bool ActionExecutor::move_pids_to_cgroup(const std::vector<int>& pids, const std::string& cgroup_path) {
    if (pids.empty()) {
        return true;
    }

    std::string procs_file = cgroup_path + "/cgroup.procs";
    std::ofstream ofs(procs_file, std::ios_base::app);
    if (!ofs.is_open()) {
        LOGE("Failed to open '%s' to move pids: %s", procs_file.c_str(), strerror(errno));
        return false;
    }
    for (int pid : pids) {
        ofs << pid << std::endl;
        if (ofs.fail()) {
            LOGW("Error writing pid %d to %s. Process might have already died.", pid, procs_file.c_str());
            ofs.clear();
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
            // 对 oom_score_adj 的写入失败需要更详细的日志
            if (path.find("oom_score_adj") != std::string::npos) {
                 LOGE("Failed to open OOM score file '%s': %s", path.c_str(), strerror(errno));
            } else {
                 LOGE("Failed to open file '%s' for writing: %s", path.c_str(), strerror(errno));
            }
        }
        return false;
    }
    ofs << value;
    if (ofs.fail()) {
        if (path.find("oom_score_adj") != std::string::npos) {
             LOGE("Failed to write '%s' to OOM score file '%s': %s", value.c_str(), path.c_str(), strerror(errno));
        } else {
             LOGE("Failed to write '%s' to '%s': %s", value.c_str(), path.c_str(), strerror(errno));
        }
        return false;
    }
    return true;
}