// daemon/cpp/action_executor.h
#ifndef CERBERUS_ACTION_EXECUTOR_H
#define CERBERUS_ACTION_EXECUTOR_H

#include <string>
#include <vector>
#include <utility> 
#include <linux/android/binder.h> // 包含系统头文件

// AppInstanceKey 定义移到这里，因为它首先被这个文件需要
using AppInstanceKey = std::pair<std::string, int>;

class ActionExecutor {
public:
    ActionExecutor();
    ~ActionExecutor();

    /**
     * @brief 尝试冻结一个应用实例，采用新的“乐观命令式重试”策略。
     * @return 0: 成功 (可能部分pid是采纳的现有状态) | 1: 软失败，需要重试 | -1: 彻底失败
     */
    int freeze(const AppInstanceKey& key, const std::vector<int>& pids);
    
    bool unfreeze(const AppInstanceKey& key, const std::vector<int>& pids);

private:
    bool initialize_binder();
    void cleanup_binder();
    
    // 这个函数现在是内部实现细节，不再分为 strict/lenient
    int handle_binder_freeze(const std::vector<int>& pids, bool freeze);
    
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