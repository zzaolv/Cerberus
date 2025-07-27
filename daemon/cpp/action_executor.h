// daemon/cpp/action_executor.h
#ifndef CERBERUS_ACTION_EXECUTOR_H
#define CERBERUS_ACTION_EXECUTOR_H

#include <string>
#include <vector>
#include <utility> 

using AppInstanceKey = std::pair<std::string, int>;

enum class FreezeMethod {
    CGROUP_V2,
    METHOD_SIGSTOP
};

class ActionExecutor {
public:
    ActionExecutor();
    ~ActionExecutor();

    /**
     * @brief 尝试冻结一个应用实例，采用新的分级策略。
     * @return 0: 成功 | 1: 需要重试 (主进程软失败) | -1: 彻底失败
     */
    int freeze(const AppInstanceKey& key, const std::vector<int>& pids);
    
    bool unfreeze(const AppInstanceKey& key, const std::vector<int>& pids);

private:
    bool initialize_binder();
    void cleanup_binder();
    
    /**
     * @brief 严格模式的Binder冻结，用于主进程。
     * @return 0: 成功 | 1: 软失败(EAGAIN) | -1: 硬失败或致命失败
     */
    int handle_binder_freeze_strict(const std::vector<int>& pids, bool freeze);

    /**
     * @brief 宽容模式的Binder冻结/解冻，用于次要进程和所有解冻操作。
     */
    void handle_binder_freeze_lenient(const std::vector<int>& pids, bool freeze);

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

    CgroupVersion cgroup_version_ = CgroupVersion::UNKNOWN;
    std::string cgroup_root_path_;
    
    struct BinderState {
        int fd = -1;
        void* mapped = nullptr;
        size_t mapSize = 128 * 1024ULL;
    } binder_state_;
};

#endif //CERBERUS_ACTION_EXECUTOR_H