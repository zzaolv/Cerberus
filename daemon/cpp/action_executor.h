// daemon/cpp/action_executor.h
#ifndef CERBERUS_ACTION_EXECUTOR_H
#define CERBERUS_ACTION_EXECUTOR_H

#include <string>
#include <vector>
#include <utility> // For std::pair

// 使用 <包名, user_id> 作为应用实例的唯一标识符
using AppInstanceKey = std::pair<std::string, int>;

class ActionExecutor {
public:
    ActionExecutor();

    /**
     * @brief 冻结一个应用实例。
     * 这将创建一个专用的cgroup，将PID移入，并冻结该cgroup。
     * @param key 应用实例的唯一标识。
     * @param pids 该实例当前的所有PID。
     * @return 操作是否成功。
     */
    bool freeze(const AppInstanceKey& key, const std::vector<int>& pids);
    
    /**
     * @brief 解冻并彻底清理一个应用实例的cgroup。
     * 这将解冻cgroup，将PID移回默认组，并删除专用cgroup目录。
     * @param key 应用实例的唯一标识。
     * @return 操作是否成功。
     */
    bool unfreeze_and_cleanup(const AppInstanceKey& key);

    /**
     * @brief [新增] 将一组PID移动到一个已存在的实例cgroup中。
     * 主要用于在父进程被冻结后，捕获并冻结其新生的子进程。
     * @param key 目标应用实例的唯一标识。
     * @param pids 要移动的PID列表。
     * @return 操作是否成功。
     */
    bool move_pids_to_instance_cgroup(const AppInstanceKey& key, const std::vector<int>& pids);

private:
    enum class CgroupVersion { V1, V2, UNKNOWN };
    
    // 根据AppInstanceKey生成cgroup的绝对路径
    std::string get_instance_cgroup_path(const AppInstanceKey& key) const;

    // 创建和删除实例专用的cgroup目录
    bool create_instance_cgroup(const std::string& path);
    bool remove_instance_cgroup(const std::string& path);
    
    // 将PID列表写入指定cgroup的tasks/procs文件
    bool move_pids_to_cgroup(const std::vector<int>& pids, const std::string& cgroup_path);
    
    // 将PID列表移回系统默认的应用cgroup
    bool move_pids_to_default_cgroup(const std::vector<int>& pids);

    // 通用的文件写入辅助函数
    bool write_to_file(const std::string& path, const std::string& value);

    CgroupVersion cgroup_version_ = CgroupVersion::UNKNOWN;
    std::string cgroup_root_path_; // cgroup freezer的根路径
};

#endif //CERBERUS_ACTION_EXECUTOR_H