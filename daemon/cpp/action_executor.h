// daemon/cpp/action_executor.h
#ifndef CERBERUS_ACTION_EXECUTOR_H
#define CERBERUS_ACTION_EXECUTOR_H

#include <string>
#include <vector>

class ActionExecutor {
public:
    ActionExecutor();

    // 【核心重构】接口变更：直接操作PID列表，实现外科手术式冻结
    bool freeze_pids(const std::vector<int>& pids);
    bool unfreeze_cgroup(); // 解冻整个cgroup即可

private:
    enum class CgroupVersion { V1, V2, UNKNOWN };
    
    // 初始化我们的专属cgroup "cerberus_frozen"
    bool initialize_frozen_cgroup();
    
    // 将PID列表写入cgroup的tasks/procs文件
    bool add_pids_to_frozen_cgroup(const std::vector<int>& pids);

    // 通用的文件写入工具
    bool write_to_file(const std::string& path, const std::string& value);

    CgroupVersion cgroup_version_ = CgroupVersion::UNKNOWN;
    std::string frozen_cgroup_path_;
    std::string cgroup_procs_file_; // cgroup v2的 cgroup.procs 或 v1的 tasks
    std::string cgroup_state_file_; // cgroup v2的 cgroup.freeze 或 v1的 freezer.state
};

#endif //CERBERUS_ACTION_EXECUTOR_H