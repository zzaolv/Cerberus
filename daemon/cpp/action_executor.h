// daemon/cpp/action_executor.h
#ifndef CERBERUS_ACTION_EXECUTOR_H
#define CERBERUS_ACTION_EXECUTOR_H

#include <string>
#include <vector>

// Freezer 类型枚举
enum class FreezerType {
    AUTO, CGROUP_V2, CGROUP_V1, SIGSTOP
};

class ActionExecutor {
public:
    ActionExecutor();

    // 冻结/解冻方法现在接受 FreezerType
    bool freeze_pids(const std::vector<int>& pids, FreezerType type);
    bool unfreeze_pids(const std::vector<int>& pids, FreezerType type);

    bool block_network(int uid);
    bool unblock_network(int uid);
    void initialize_network_chains();

    // 获取自动检测到的 Cgroup 版本
    FreezerType get_auto_detected_freezer_type() const;

private:
    enum class CgroupVersion { V1, V2, UNKNOWN };
    
    void create_frozen_cgroup_if_needed();
    bool add_pids_to_frozen_cgroup(const std::vector<int>& pids);

    bool write_to_file(const std::string& path, const std::string& value);
    bool write_pids_to_file(const std::string& path, const std::vector<int>& pids);
    bool execute_shell_command(const std::string& command);

    CgroupVersion cgroup_version_ = CgroupVersion::UNKNOWN;
    std::string frozen_cgroup_path_;
    std::string frozen_cgroup_procs_path_;
    std::string frozen_cgroup_state_path_;
};

#endif //CERBERUS_ACTION_EXECUTOR_H