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
     * @brief [战术指挥官] 尝试冻结一个应用实例。
     * @param key 应用实例的唯一标识。
     * @param pids 该实例当前的所有PID。
     * @return 0: 成功 (完全或降级) | 1: 需要重试 (软失败) | -1: 彻底失败
     */
    int freeze(const AppInstanceKey& key, const std::vector<int>& pids);
    
    bool unfreeze(const AppInstanceKey& key, const std::vector<int>& pids);

private:
    // --- Binder Freeze ---
    bool initialize_binder();
    void cleanup_binder();
    /**
     * @brief [情报官] 执行ioctl操作并报告精确情报。
     * @return 0: 全部成功 | 1: 软失败(EAGAIN) | -1: 硬失败(EINVAL/EPERM) | -2: 致命失败
     */
    int handle_binder_freeze(const std::vector<int>& pids, bool freeze);

    // --- Cgroup v2 Freeze ---
    enum class CgroupVersion { V2, UNKNOWN };
    bool initialize_cgroup();
    std::string get_instance_cgroup_path(const AppInstanceKey& key) const;
    bool freeze_cgroup(const AppInstanceKey& key, const std::vector<int>& pids);
    bool unfreeze_cgroup(const AppInstanceKey& key);

    // --- SIGSTOP Freeze ---
    void freeze_sigstop(const std::vector<int>& pids);
    void unfreeze_sigstop(const std::vector<int>& pids);

    // --- 辅助函数 ---
    bool create_instance_cgroup(const std::string& path);
    bool remove_instance_cgroup(const std::string& path);
    bool move_pids_to_cgroup(const std::vector<int>& pids, const std::string& cgroup_path);
    bool move_pids_to_default_cgroup(const std::vector<int>& pids);
    bool write_to_file(const std::string& path, const std::string& value);

    // --- 状态变量 ---
    CgroupVersion cgroup_version_ = CgroupVersion::UNKNOWN;
    std::string cgroup_root_path_;
    
    struct BinderState {
        int fd = -1;
        void* mapped = nullptr;
        size_t mapSize = 128 * 1024ULL;
    } binder_state_;
};

#endif //CERBERUS_ACTION_EXECUTOR_H