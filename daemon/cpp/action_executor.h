// daemon/cpp/action_executor.h
#ifndef CERBERUS_ACTION_EXECUTOR_H
#define CERBERUS_ACTION_EXECUTOR_H

#include <string>
#include <vector>
#include <utility> 

using AppInstanceKey = std::pair<std::string, int>;

// 从 freezeitVS 借鉴的多模式冻结策略
enum class FreezeMethod {
    CGROUP_V2,
    METHOD_SIGSTOP // [修复] 将 SIGSTOP 重命名为 METHOD_SIGSTOP 以避免宏冲突
};

class ActionExecutor {
public:
    ActionExecutor();
    ~ActionExecutor();

    /**
     * @brief 冻结一个应用实例，采用“先Binder冻结，再物理冻结”的安全流程。
     * @param key 应用实例的唯一标识。
     * @param pids 该实例当前的所有PID。
     * @param method 选择的物理冻结方法 (cgroup或SIGSTOP)。
     * @return 操作是否完全成功。
     */
    bool freeze(const AppInstanceKey& key, const std::vector<int>& pids, FreezeMethod method);
    
    /**
     * @brief 解冻一个应用实例，采用“先物理解冻，再Binder解冻”的安全流程。
     * @param key 应用实例的唯一标识。
     * @param pids 该实例当前的所有PID (如果已知)。
     * @return 操作是否成功。
     */
    bool unfreeze(const AppInstanceKey& key, const std::vector<int>& pids);

private:
    // --- Binder Freeze (核心技术，源自 freezeitVS) ---
    bool initialize_binder();
    void cleanup_binder();
    // 返回值: 0成功, <0为操作失败的pid
    int handle_binder_freeze(const std::vector<int>& pids, bool freeze);

    // --- Cgroup v2 Freeze (源自 Cerberus) ---
    enum class CgroupVersion { V2, UNKNOWN };
    bool initialize_cgroup();
    std::string get_instance_cgroup_path(const AppInstanceKey& key) const;
    bool freeze_cgroup(const AppInstanceKey& key, const std::vector<int>& pids);
    bool unfreeze_cgroup(const AppInstanceKey& key);

    // --- SIGSTOP Freeze (源自 freezeitVS) ---
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