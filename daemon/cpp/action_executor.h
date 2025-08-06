// daemon/cpp/action_executor.h
#ifndef CERBERUS_ACTION_EXECUTOR_H
#define CERBERUS_ACTION_EXECUTOR_H

#include <string>
#include <vector>
#include <utility>
#include <map>
#include <mutex>
#include <memory> // [新增]
#include <linux/android/binder.h>

using AppInstanceKey = std::pair<std::string, int>;



// [新增] 前向声明 SystemMonitor 和 AdjMapper
class SystemMonitor;
class AdjMapper;

// [新增] 定义进程角色
enum class ProcessRole {
    MAIN,  // 主进程
    PUSH,  // Push进程
    CHILD  // 普通子进程
};


class ActionExecutor {
public:
    // [修改] 构造函数现在接受 SystemMonitor 和 AdjMapper
    ActionExecutor(std::shared_ptr<SystemMonitor> sys_monitor, std::shared_ptr<AdjMapper> adj_mapper);
    ~ActionExecutor();

    int freeze(const AppInstanceKey& key, const std::vector<int>& pids);
    // [修改] unfreeze现在只负责恢复状态
    bool unfreeze(const std::vector<int>& pids);
    // [新增] 专门用于清理cgroup的函数
    bool cleanup_cgroup(const AppInstanceKey& key);
    void verify_and_reapply_oom_scores(const std::vector<int>& pids);

private:
    bool initialize_binder();
    void cleanup_binder();
    int handle_binder_freeze(const std::vector<int>& pids, bool freeze);
    bool is_cgroup_frozen(const AppInstanceKey& key);

    enum class CgroupVersion { V2, UNKNOWN };
    bool initialize_cgroup();
    std::string get_instance_cgroup_path(const AppInstanceKey& key) const;
    bool freeze_cgroup(const AppInstanceKey& key, const std::vector<int>& pids);
    bool unfreeze_cgroup(const AppInstanceKey& key);

    void freeze_sigstop(const std::vector<int>& pids);
    void unfreeze_sigstop(const std::vector<int>& pids);

    bool create_instance_cgroup(const std::string& path);
    bool remove_instance_cgroup(const std::string& path);
    bool move_pids_to_cgroup(const std::vector<int>& pids, const std::string& cgroup_path);
    bool move_pids_to_default_cgroup(const std::vector<int>& pids);
    bool write_to_file(const std::string& path, const std::string& value);

    // [重构] OOM Score 调整逻辑
    // [修改] 识别所有进程的角色
    std::map<int, ProcessRole> identify_process_roles(const std::vector<int>& pids) const;
    void adjust_oom_scores(const std::vector<int>& pids, bool protect);
    std::optional<int> read_oom_score_adj(int pid);

    CgroupVersion cgroup_version_ = CgroupVersion::UNKNOWN;
    std::string cgroup_root_path_;

    struct BinderState {
        int fd = -1;
        void* mapped = nullptr;
        size_t mapSize = 128 * 1024ULL;
    } binder_state_;

    std::map<int, int> original_oom_scores_;
    std::map<int, int> protected_oom_scores_;
    std::mutex oom_scores_mutex_;

    // [新增] 持有 SystemMonitor 的指针
    std::shared_ptr<SystemMonitor> sys_monitor_;
    // [新增] 持有 AdjMapper 的指针
    std::shared_ptr<AdjMapper> adj_mapper_;
};

#endif //CERBERUS_ACTION_EXECUTOR_H