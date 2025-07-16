// daemon/cpp/action_executor.h
#ifndef CERBERUS_ACTION_EXECUTOR_H
#define CERBERUS_ACTION_EXECUTOR_H

#include <string>
#include <vector>

class ActionExecutor {
public:
    ActionExecutor();

    // 冻结指定UID的所有进程
    bool freeze_app(const std::string& package_name, int user_id);
    // 解冻指定UID的所有进程
    bool unfreeze_app(const std::string& package_name, int user_id);

private:
    enum class CgroupVersion { V1, V2, UNKNOWN };
    
    CgroupVersion cgroup_version_ = CgroupVersion::UNKNOWN;
    
    // cgroup v1 helpers
    bool freeze_uid_v1(int uid);
    bool unfreeze_uid_v1(int uid);
    
    // cgroup v2 helpers
    bool freeze_app_v2(const std::string& package_name, int user_id);
    bool unfreeze_app_v2(const std::string& package_name, int user_id);

    bool write_to_file(const std::string& path, const std::string& value);
};

#endif //CERBERUS_ACTION_EXECUTOR_H