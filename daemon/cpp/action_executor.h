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

// [新增] 前向声明 SystemMonitor
class SystemMonitor;

class ActionExecutor {
public:
    // [修改] 构造函数接受 SystemMonitor
    explicit ActionExecutor(std::shared_ptr<SystemMonitor> sys_monitor);
    ~ActionExecutor();

    int freeze(const AppInstanceKey& key, const std::vector<int>& pids);
    // [修改] unfreeze现在只负责恢复状态
    bool unfreeze(const std::vector<int>& pids);
    // [新增] 专门用于清理cgroup的函数
    bool cleanup_cgroup(const AppInstanceKey& key);

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
    // [新增] 查找主进程的辅助函数
    int find_main_pid(const std::vector<int>& pids) const;
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
    std::mutex oom_scores_mutex_;

    // [新增] 持有 SystemMonitor 的指针
    std::shared_ptr<SystemMonitor> sys_monitor_;
};

#endif //CERBERUS_ACTION_EXECUTOR_H