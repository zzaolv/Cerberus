// daemon/cpp/action_executor.cpp
#include "action_executor.h"
#include <android/log.h>
#include <sys/stat.h>
#include <fstream>
#include <filesystem>
#include <unistd.h>
#include <csignal>
#include <cstring>
#include <cstdlib>

#define LOG_TAG "cerberusd_action"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;

bool ActionExecutor::execute_shell_command(const std::string& command) {
    int ret = system(command.c_str());
    if (ret != 0) {
        LOGE("Command failed with code %d: %s", ret, command.c_str());
        return false;
    }
    LOGI("Command executed successfully: %s", command.c_str());
    return true;
}

void ActionExecutor::initialize_network_chains() {
    LOGI("Initializing network control chains...");
    system("iptables -D OUTPUT -j cerberus_output > /dev/null 2>&1");
    system("iptables -F cerberus_output > /dev/null 2>&1");
    system("iptables -X cerberus_output > /dev/null 2>&1");

    execute_shell_command("iptables -N cerberus_output");
    execute_shell_command("iptables -I OUTPUT -j cerberus_output");
    execute_shell_command("iptables -A cerberus_output -j RETURN");
    LOGI("Network control chains are ready.");
}

bool ActionExecutor::block_network(int uid) {
    if (uid < 10000) {
        LOGW("Attempted to block network for system UID %d. Denied.", uid);
        return false;
    }
    std::string cmd = "iptables -I cerberus_output -m owner --uid-owner " + std::to_string(uid) + " -j DROP";
    return execute_shell_command(cmd);
}

bool ActionExecutor::unblock_network(int uid) {
    if (uid < 10000) return true;
    std::string cmd = "iptables -D cerberus_output -m owner --uid-owner " + std::to_string(uid) + " -j DROP > /dev/null 2>&1";
    system(cmd.c_str());
    return true;
}

ActionExecutor::ActionExecutor() {
    if (fs::exists("/sys/fs/cgroup/cgroup.controllers")) {
        cgroup_version_ = CgroupVersion::V2;
        LOGI("Detected cgroup v2.");
        frozen_cgroup_path_ = "/sys/fs/cgroup/cerberus_frozen";
    } else if (fs::exists("/sys/fs/cgroup/freezer")) {
        cgroup_version_ = CgroupVersion::V1;
        LOGI("Detected cgroup v1.");
        frozen_cgroup_path_ = "/sys/fs/cgroup/freezer/cerberus_frozen";
    } else {
        cgroup_version_ = CgroupVersion::UNKNOWN;
        LOGE("Could not determine cgroup version.");
    }
    if(cgroup_version_ != CgroupVersion::UNKNOWN) {
        create_frozen_cgroup_if_needed();
    }
    initialize_network_chains();
}

void ActionExecutor::create_frozen_cgroup_if_needed() {
    if (cgroup_version_ == CgroupVersion::UNKNOWN) return;

    if (!fs::exists(frozen_cgroup_path_)) {
        LOGI("Creating dedicated frozen cgroup at: %s", frozen_cgroup_path_.c_str());
        try {
            fs::create_directory(frozen_cgroup_path_);
        } catch (const fs::filesystem_error& e) {
            LOGE("Failed to create frozen cgroup: %s", e.what());
            return;
        }
    }
    
    if (cgroup_version_ == CgroupVersion::V2) {
        frozen_cgroup_procs_path_ = frozen_cgroup_path_ + "/cgroup.procs";
        frozen_cgroup_state_path_ = frozen_cgroup_path_ + "/cgroup.freeze";
    } else {
        frozen_cgroup_procs_path_ = frozen_cgroup_path_ + "/tasks";
        frozen_cgroup_state_path_ = frozen_cgroup_path_ + "/freezer.state";
    }
    
    LOGI("Frozen cgroup is ready.");
}

bool ActionExecutor::write_to_file(const std::string& path, const std::string& value) {
    std::ofstream ofs(path);
    if (!ofs.is_open()) {
        LOGE("Failed to open file: %s. Error: %s", path.c_str(), strerror(errno));
        return false;
    }
    ofs << value;
    if (ofs.fail()) {
        LOGE("Failed to write '%s' to %s. Error: %s", value.c_str(), path.c_str(), strerror(errno));
        return false;
    }
    return true;
}

bool ActionExecutor::write_pids_to_file(const std::string& path, const std::vector<int>& pids) {
    std::ofstream ofs(path, std::ios_base::app);
    if (!ofs.is_open()) {
        LOGE("Failed to open file for appending PIDs: %s. Error: %s", path.c_str(), strerror(errno));
        return false;
    }
    for (int pid : pids) {
        ofs << pid << std::endl;
        if (ofs.fail()) {
            LOGE("Failed to write PID %d to %s. Error: %s", pid, path.c_str(), strerror(errno));
            return false;
        }
    }
    return true;
}

bool ActionExecutor::add_pids_to_frozen_cgroup(const std::vector<int>& pids) {
    if (pids.empty()) return true;
    return write_pids_to_file(frozen_cgroup_procs_path_, pids);
}

bool ActionExecutor::freeze_pids(const std::vector<int>& pids, FreezerType type) {
    if (pids.empty()) return true;

    FreezerType final_type = type;
    if (type == FreezerType::AUTO) {
        final_type = get_auto_detected_freezer_type();
    }
    
    LOGI("Freezing %zu PIDs using method: %d", pids.size(), static_cast<int>(final_type));

    if (final_type == FreezerType::CGROUP_V1 || final_type == FreezerType::CGROUP_V2) {
        if (cgroup_version_ == CgroupVersion::UNKNOWN) {
            LOGE("Cgroup freezer requested, but no cgroup support detected.");
            return false;
        }
        if (!add_pids_to_frozen_cgroup(pids)) {
            LOGE("Failed to move PIDs to frozen cgroup, aborting freeze.");
            return false;
        }
        const std::string freeze_cmd = (cgroup_version_ == CgroupVersion::V2) ? "1" : "FROZEN";
        return write_to_file(frozen_cgroup_state_path_, freeze_cmd);
    } else if (final_type == FreezerType::SIGSTOP) {
        for (int pid : pids) {
            if (kill(pid, SIGSTOP) != 0) {
                LOGW("Failed to send SIGSTOP to PID %d: %s", pid, strerror(errno));
            }
        }
        return true;
    }

    LOGE("Unknown freezer type requested: %d", static_cast<int>(type));
    return false;
}

bool ActionExecutor::unfreeze_pids(const std::vector<int>& pids, FreezerType type) {
    if (pids.empty()) return true;

    FreezerType final_type = type;
    if (type == FreezerType::AUTO) {
        final_type = get_auto_detected_freezer_type();
    }
    
    LOGI("Unfreezing PIDs using method: %d", static_cast<int>(final_type));

    if (final_type == FreezerType::CGROUP_V1 || final_type == FreezerType::CGROUP_V2) {
        if (cgroup_version_ == CgroupVersion::UNKNOWN) return false;
        const std::string unfreeze_cmd = (cgroup_version_ == CgroupVersion::V2) ? "0" : "THAWED";
        return write_to_file(frozen_cgroup_state_path_, unfreeze_cmd);
    } else if (final_type == FreezerType::SIGSTOP) {
        for (int pid : pids) {
            if (kill(pid, SIGCONT) != 0) {
                 LOGW("Failed to send SIGCONT to PID %d: %s", pid, strerror(errno));
            }
        }
        return true;
    }

    return false;
}

FreezerType ActionExecutor::get_auto_detected_freezer_type() const {
    if (cgroup_version_ == CgroupVersion::V2) return FreezerType::CGROUP_V2;
    if (cgroup_version_ == CgroupVersion::V1) return FreezerType::CGROUP_V1;
    return FreezerType::SIGSTOP; // Fallback
}