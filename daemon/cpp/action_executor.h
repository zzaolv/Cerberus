// daemon/cpp/action_executor.h
#ifndef CERBERUS_ACTION_EXECUTOR_H
#define CERBERUS_ACTION_EXECUTOR_H

#include <string>
#include <vector>
#include <utility> 
#include <map> // [修改] 用于记录原始OOM scores
#include <linux/android/binder.h>

using AppInstanceKey = std::pair<std::string, int>;

class ActionExecutor {
public:
    ActionExecutor();
    ~ActionExecutor();

    int freeze(const AppInstanceKey& key, const std::vector<int>& pids);
    bool unfreeze(const AppInstanceKey& key, const std::vector<int>& pids);

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

    // [修改] OOM Score 调整逻辑
    void adjust_oom_scores(const std::vector<int>& pids, bool protect);
    std::optional<int> read_oom_score_adj(int pid);

    CgroupVersion cgroup_version_ = CgroupVersion::UNKNOWN;
    std::string cgroup_root_path_;
    
    struct BinderState {
        int fd = -1;
        void* mapped = nullptr;
        size_t mapSize = 128 * 1024ULL;
    } binder_state_;

    // [新增] 用于存储被修改进程的原始OOM Score
    std::map<int, int> original_oom_scores_;
    std::mutex oom_scores_mutex_;
};

#endif //CERBERUS_ACTION_EXECUTOR_H