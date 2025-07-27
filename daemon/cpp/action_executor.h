// daemon/cpp/action_executor.h
#ifndef CERBERUS_ACTION_EXECUTOR_H
#define CERBERUS_ACTION_EXECUTOR_H

#include <string>
#include <vector>
#include <utility> 
#include <linux/android/binder.h> // [核心修复] 包含系统头文件来获取结构体定义

// [核心修复] 移除我们自己的重复定义
/*
struct binder_frozen_status_info {
    __u32 pid;
    __u32 is_frozen;
    __u32 sync_recv;
    __u32 async_recv;
};
*/

class ActionExecutor {
public:
    ActionExecutor();
    ~ActionExecutor();

    /**
     * @brief 尝试冻结一个应用实例，采用新的分级和自适应协调策略。
     * @return 0: 成功 | 1: 需要重试 | -1: 彻底失败
     */
    int freeze(const AppInstanceKey& key, const std::vector<int>& pids);
    
    bool unfreeze(const AppInstanceKey& key, const std::vector<int>& pids);

private:
    bool initialize_binder();
    void cleanup_binder();
    
    /**
     * @brief 查询指定PID的Binder是否已冻结。
     * @return true 如果已冻结，false 如果未冻结或查询失败。
     */
    bool is_pid_binder_frozen(int pid);

    /**
     * @brief 智能协调版的Binder冻结/解冻操作。
     * @return 0: 成功 | 1: 软失败(EAGAIN) | -1: 硬失败或致命失败
     */
    int handle_binder_op_with_coordination(int pid, bool freeze);
    
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