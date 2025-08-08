// daemon/cpp/action_executor.h
#ifndef CERBERUS_ACTION_EXECUTOR_H
#define CERBERUS_ACTION_EXECUTOR_H

#include <string>
#include <vector>
#include <utility>
#include <map>
#include <mutex>
#include <memory>
#include <linux/android/binder.h>

using AppInstanceKey = std::pair<std::string, int>;

// 前向声明
class SystemMonitor;
class AdjMapper;

// 定义进程角色
enum class ProcessRole {
    MAIN,  // 主进程
    PUSH,  // Push进程
    CHILD  // 普通子进程
};


class ActionExecutor {
public:
    ActionExecutor(std::shared_ptr<SystemMonitor> sys_monitor, std::shared_ptr<AdjMapper> adj_mapper);
    ~ActionExecutor();

    // 冻结操作保持不变
    int freeze(const AppInstanceKey& key, const std::vector<int>& pids);
    
    // [核心重构] unfreeze 现在是统一的、顺序正确的解冻入口
    bool unfreeze(const AppInstanceKey& key, const std::vector<int>& pids);

    // OOM 巡检方法保持不变
    void verify_and_reapply_oom_scores(const std::vector<int>& pids);
    
    // [新增] 移除指定PID的OOM守护记录，用于进程死亡清理
    void remove_oom_protection_records(int pid);


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

    // OOM Score 调整逻辑
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

    std::shared_ptr<SystemMonitor> sys_monitor_;
    std::shared_ptr<AdjMapper> adj_mapper_;
};

#endif //CERBERUS_ACTION_EXECUTOR_H