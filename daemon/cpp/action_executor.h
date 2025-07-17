// daemon/cpp/action_executor.h
#ifndef CERBERUS_ACTION_EXECUTOR_H
#define CERBERUS_ACTION_EXECUTOR_H

#include <string>
#include <vector>

class ActionExecutor {
public:
    ActionExecutor();

    bool freeze_pids(const std::vector<int>& pids);
    bool unfreeze_pids(const std::vector<int>& pids);

    // [新增] 网络控制接口
    bool block_network(int uid);
    bool unblock_network(int uid);
    void initialize_network_chains();

private:
    enum class CgroupVersion { V1, V2, UNKNOWN };
    
    void create_frozen_cgroup_if_needed();
    bool add_pids_to_frozen_cgroup(const std::vector<int>& pids);

    bool write_to_file(const std::string& path, const std::string& value);
    bool write_pids_to_file(const std::string& path, const std::vector<int>& pids);
    
    // [新增] 执行shell命令的辅助函数
    bool execute_shell_command(const std::string& command);

    CgroupVersion cgroup_version_ = CgroupVersion::UNKNOWN;
    std::string frozen_cgroup_path_;
    std::string frozen_cgroup_procs_path_;
    std::string frozen_cgroup_state_path_;
};

#endif //CERBERUS_ACTION_EXECUTOR_H