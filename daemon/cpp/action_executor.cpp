// daemon/cpp/action_executor.cpp
#include "action_executor.h"
#include "system_monitor.h" // [新增] 引入头文件
#include "adj_mapper.h"     // [新增] 引入头文件
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
#include <mutex>

#define LOG_TAG "cerberusd_action_v21_robust_oom" // 版本号更新
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;

constexpr int PINNED_MAIN_PROC_OOM_SCORE = 200;

// [修改] 构造函数实现
ActionExecutor::ActionExecutor(std::shared_ptr<SystemMonitor> sys_monitor, std::shared_ptr<AdjMapper> adj_mapper) 
    : sys_monitor_(std::move(sys_monitor)), adj_mapper_(std::move(adj_mapper)) {
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

    LOGI("Binder phase complete for %s. Attempting Cgroup v2 freeze.", key.first.c_str());
    bool cgroup_ok = freeze_cgroup(key, pids);

    if (cgroup_ok) {
        bool verified = false;
        const int VERIFICATION_ATTEMPTS = 4;
        const useconds_t VERIFICATION_INTERVAL_US = 50000;

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

    // --- [修改] 阶段 3: OOM 守护 (动态优先级) ---
    if (final_result == 0 || final_result == 1) {
        LOGI("CPU freeze for %s successful. Applying memory protection (OOM Guardian).", key.first.c_str());
        adjust_oom_scores(pids, true);
    }

    return final_result;
}

// [修改] 识别所有进程的角色
std::map<int, ProcessRole> ActionExecutor::identify_process_roles(const std::vector<int>& pids) const {
    std::map<int, ProcessRole> roles;
    for (int pid : pids) {
        std::string cmdline = sys_monitor_->get_app_name_from_pid(pid);
        if (!cmdline.empty()) {
            if (cmdline.find(':') == std::string::npos) {
                roles[pid] = ProcessRole::MAIN;
            } else if (cmdline.find(":push") != std::string::npos) {
                roles[pid] = ProcessRole::PUSH;
            } else {
                roles[pid] = ProcessRole::CHILD;
            }
        } else {
            // 如果获取不到 cmdline，默认视为子进程
            roles[pid] = ProcessRole::CHILD;
        }
    }
    return roles;
}

// [修改] unfreeze 现在只负责恢复状态
bool ActionExecutor::unfreeze(const std::vector<int>& pids) {
    // 1. 恢复 OOM Score
    adjust_oom_scores(pids, false);

    // 2. 解冻 CPU
    unfreeze_sigstop(pids);
    handle_binder_freeze(pids, false);

    // 注意：cgroup 的解冻与清理分离，由 StateManager 在确认后调用
    return true;
}

// [新增] 专门的cgroup清理函数
bool ActionExecutor::cleanup_cgroup(const AppInstanceKey& key) {
    return unfreeze_cgroup(key);
}

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

// [新增] 真正可靠地查找主进程PID
int ActionExecutor::find_main_pid(const std::vector<int>& pids) const {
    if (pids.empty()) {
        return -1;
    }

    // 优先策略：查找进程名不含':'的进程
    for (int pid : pids) {
        // 使用注入的 sys_monitor_ 来获取进程名 (cmdline)
        std::string cmdline = sys_monitor_->get_app_name_from_pid(pid);
        if (!cmdline.empty() && cmdline.find(':') == std::string::npos) {
            LOGD("OOM: Found main process %d by cmdline '%s'", pid, cmdline.c_str());
            return pid;
        }
    }

    // 回退策略：使用最小PID
    int fallback_pid = *std::min_element(pids.begin(), pids.end());
    LOGW("OOM: Could not find main process by cmdline, falling back to min_pid heuristic: %d", fallback_pid);
    return fallback_pid;
}

// [核心重构] adjust_oom_scores 实现基于角色和策略的动态守护
void ActionExecutor::adjust_oom_scores(const std::vector<int>& pids, bool protect) {
    if (pids.empty() || !adj_mapper_) return;

    std::lock_guard<std::mutex> lock(oom_scores_mutex_);

    if (protect) {
        // --- 保护操作 ---
        auto roles = identify_process_roles(pids);
        std::vector<int> core_pids;
        std::vector<int> child_pids;
        int base_adj_orig = 1001; // 初始化为一个较高的值

        for (const auto& [pid, role] : roles) {
            if (role == ProcessRole::MAIN || role == ProcessRole::PUSH) {
                core_pids.push_back(pid);
            } else {
                child_pids.push_back(pid);
            }
        }

        // 1. 找到所有核心进程中，AMS给的最低（最好）的adj值
        for (int pid : core_pids) {
            if (original_oom_scores_.count(pid)) continue; // 如果已经保护，则跳过
            auto score_opt = read_oom_score_adj(pid);
            if (score_opt) {
                original_oom_scores_[pid] = *score_opt; // 预先存储原始值
                if (*score_opt < base_adj_orig) {
                    base_adj_orig = *score_opt;
                }
            }
        }

        if (core_pids.empty() && !child_pids.empty()) {
            // 如果没有核心进程，就用子进程里最好的adj作为基准
            for (int pid : child_pids) {
                if (original_oom_scores_.count(pid)) continue;
                auto score_opt = read_oom_score_adj(pid);
                 if (score_opt) {
                    original_oom_scores_[pid] = *score_opt;
                    if (*score_opt < base_adj_orig) {
                        base_adj_orig = *score_opt;
                    }
                }
            }
        }

        // 2. 将基准值映射为新的目标基准值
        int base_adj_new = adj_mapper_->map_adj(base_adj_orig);

        // 3. 设置核心进程的adj
        for (int pid : core_pids) {
            int original_score = original_oom_scores_[pid];
            std::string path = "/proc/" + std::to_string(pid) + "/oom_score_adj";
            if (write_to_file(path, std::to_string(base_adj_new))) {
                LOGI("OOM Guardian: Core PID %d (%s) set. Score: %d -> %d.", 
                     pid, (roles[pid] == ProcessRole::MAIN ? "main" : "push"), original_score, base_adj_new);
                protected_oom_scores_[pid] = base_adj_new; // [修改] 记录目标值
            } else {
                LOGW("OOM Guardian: Failed to set core PID %d.", pid);
            }
        }
        
        // 4. 设置子进程的adj
        for (int pid : child_pids) {
            if (original_oom_scores_.find(pid) == original_oom_scores_.end()) {
                auto score_opt = read_oom_score_adj(pid);
                if(score_opt) original_oom_scores_[pid] = *score_opt;
                else continue;
            }
            int child_adj_orig = original_oom_scores_[pid];
            int child_adj_new = adj_mapper_->map_adj(child_adj_orig);

            // 强制校验，确保子进程优先级低于核心进程
            int final_child_adj = std::max(child_adj_new, base_adj_new + 1);

            std::string path = "/proc/" + std::to_string(pid) + "/oom_score_adj";
            if (write_to_file(path, std::to_string(final_child_adj))) {
                LOGI("OOM Guardian: Child PID %d set. Score: %d -> %d (raw mapped: %d, final: %d).",
                     pid, child_adj_orig, final_child_adj, child_adj_new, final_child_adj);
                protected_oom_scores_[pid] = final_child_adj; // [修改] 记录目标值
            } else {
                LOGW("OOM Guardian: Failed to set child PID %d.", pid);
            }
        }
    } else {
        // --- 恢复操作 ---
        std::vector<int> pids_to_restore = pids;
        if (pids_to_restore.empty()) {
            for(const auto& pair : original_oom_scores_) {
                pids_to_restore.push_back(pair.first);
            }
        }

        for (int pid : pids_to_restore) {
            auto it = original_oom_scores_.find(pid);
            if (it != original_oom_scores_.end()) {
                int original_score = it->second;
                std::string path = "/proc/" + std::to_string(pid) + "/oom_score_adj";

                if (fs::exists(path) && write_to_file(path, std::to_string(original_score))) {
                    LOGI("OOM Guardian: Restored PID %d to original score %d.", pid, original_score);
                } else {
                    LOGW("OOM Guardian: Failed to restore PID %d (score %d). Process likely died.", pid, original_score);
                }
                original_oom_scores_.erase(it);
                protected_oom_scores_.erase(pid); // [修改] 清理目标值记录
            }
        }
    }
}

// [新增] 巡检方法的实现
void ActionExecutor::verify_and_reapply_oom_scores(const std::vector<int>& pids) {
    std::lock_guard<std::mutex> lock(oom_scores_mutex_);
    if (protected_oom_scores_.empty()) return;
    
    std::vector<int> dead_pids;

    for (int pid : pids) {
        auto it = protected_oom_scores_.find(pid);
        if (it != protected_oom_scores_.end()) {
            int target_score = it->second;
            auto current_score_opt = read_oom_score_adj(pid);

            if (!current_score_opt.has_value()) {
                // 进程已死亡，加入待清理列表
                dead_pids.push_back(pid);
                continue;
            }

            if (current_score_opt.value() != target_score) {
                LOGW("OOM Guardian [VERIFY]: PID %d score was altered (%d -> %d). Reapplying target %d.",
                     pid, target_score, current_score_opt.value(), target_score);
                
                std::string path = "/proc/" + std::to_string(pid) + "/oom_score_adj";
                write_to_file(path, std::to_string(target_score));
            }
        }
    }

    // 清理已死亡进程的记录
    for (int pid : dead_pids) {
        protected_oom_scores_.erase(pid);
        original_oom_scores_.erase(pid);
        LOGD("OOM Guardian [CLEANUP]: Removed dead PID %d from protection maps.", pid);
    }
}

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