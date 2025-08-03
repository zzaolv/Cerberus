// daemon/cpp/action_executor.h
#ifndef CERBERUS_ACTION_EXECUTOR_H
#define CERBERUS_ACTION_EXECUTOR_H

#include <string>
#include <vector>
#include <utility> 
#include <linux/android/binder.h>

using AppInstanceKey = std::pair<std::string, int>;

class ActionExecutor {
public:
    ActionExecutor();
    ~ActionExecutor();

    /**
     * @brief 尝试冻结一个应用实例，采用带物理验证的策略。
     * @return 0: Cgroup冻结成功 | 1: SIGSTOP后备方案成功 | 2: 软失败(可重试) | -1: 彻底失败
     */
    int freeze(const AppInstanceKey& key, const std::vector<int>& pids);
    
    bool unfreeze(const AppInstanceKey& key, const std::vector<int>& pids);

private:
    bool initialize_binder();
    void cleanup_binder();
    
    /**
     * @brief 冻结/解冻Binder
     * @return 0: 成功 | 2: 软失败(EAGAIN) | -1: 致命失败
     */
    int handle_binder_freeze(const std::vector<int>& pids, bool freeze);
    
    /**
     * @brief [核心修正] 通过检查 cgroup.freeze 文件来验证整个 cgroup 是否被冻结。
     * @return true 如果 cgroup.freeze 的值为 '1'，否则 false。
     */
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

    CgroupVersion cgroup_version_ = CgroupVersion::UNKNOWN;
    std::string cgroup_root_path_;
    
    struct BinderState {
        int fd = -1;
        void* mapped = nullptr;
        size_t mapSize = 128 * 1024ULL;
    } binder_state_;
};

#endif //CERBERUS_ACTION_EXECUTOR_H