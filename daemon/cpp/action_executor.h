// daemon/cpp/action_executor.h
#ifndef CERBERUS_ACTION_EXECUTOR_H
#define CERBERUS_ACTION_EXECUTOR_H

#include <string>
#include <vector>

class ActionExecutor {
public:
    ActionExecutor();

    // 【根本性重构】接口变更：不再使用包名，而是使用精准的PID列表
    bool freeze_pids(const std::vector<int>& pids);
    bool unfreeze_pids(const std::vector<int>& pids);

private:
    enum class CgroupVersion { V1, V2, UNKNOWN };
    
    // 【新增】初始化我们的专属cgroup
    void create_frozen_cgroup_if_needed();
    bool add_pids_to_frozen_cgroup(const std::vector<int>& pids);

    bool write_to_file(const std::string& path, const std::string& value);
    bool write_pids_to_file(const std::string& path, const std::vector<int>& pids);

    CgroupVersion cgroup_version_ = CgroupVersion::UNKNOWN;
    std::string frozen_cgroup_path_;
    std::string frozen_cgroup_procs_path_;
    std::string frozen_cgroup_state_path_;
};

#endif //CERBERUS_ACTION_EXECUTOR_H