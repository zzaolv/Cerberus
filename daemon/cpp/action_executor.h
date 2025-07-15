// daemon/cpp/action_executor.h
#ifndef CERBERUS_ACTION_EXECUTOR_H
#define CERBERUS_ACTION_EXECUTOR_H

#include <string>
#include <vector>

class ActionExecutor {
public:
    // 获取设备上 cgroup v1 freezer 的基路径
    static std::string get_freezer_path();
    
    // 冻结指定UID的所有进程
    bool freeze_uid(int uid);
    // 解冻指定UID的所有进程
    bool unfreeze_uid(int uid);

private:
    bool write_to_cgroup(const std::string& path, const std::string& value);
    std::vector<int> get_pids_for_uid(int uid);
};

#endif //CERBERUS_ACTION_EXECUTOR_H