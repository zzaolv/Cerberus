// daemon/cpp/system_monitor.cpp
#include "system_monitor.h"
#include <fstream>
#include <sstream>
#include <android/log.h>
#include <unistd.h>
#include <filesystem>
#include <sys/stat.h> // 【核心修复】添加缺失的头文件

#define LOG_TAG "cerberusd_monitor"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;
constexpr int PER_USER_RANGE = 100000;

SystemMonitor::SystemMonitor() {
    // 检测 cgroup 版本
    if (fs::exists("/sys/fs/cgroup/cgroup.controllers")) {
        cgroup_version_ = CgroupVersion::V2;
        LOGI("Detected cgroup v2.");
    } else if (fs::exists("/sys/fs/cgroup/freezer")) {
        cgroup_version_ = CgroupVersion::V1;
        LOGI("Detected cgroup v1.");
    } else {
        cgroup_version_ = CgroupVersion::UNKNOWN;
        LOGW("Could not determine cgroup version.");
    }
    update_global_stats();
}

void SystemMonitor::update_global_stats() {
    update_cpu_usage();
    update_mem_info();
}

GlobalStatsData SystemMonitor::get_global_stats() const {
    std::lock_guard<std::mutex> lock(data_mutex_);
    return current_stats_;
}


AppStatsData SystemMonitor::get_app_stats(int pid, const std::string& package_name, int user_id) {
    AppStatsData stats;
    if (pid <= 0) return stats;

    std::string proc_stat_path = "/proc/" + std::to_string(pid) + "/stat";
    if (!fs::exists(proc_stat_path)) return stats; // 进程已不存在

    // --- 1. 内存获取 ---
    long long mem_bytes = -1;
    if (cgroup_version_ == CgroupVersion::V2) {
        // Path 1: 标准用户应用路径
        std::string mem_path_1 = "/sys/fs/cgroup/user.slice/user-" + std::to_string(user_id) + ".slice/apps.slice/" + package_name + "/memory.current";
        // Path 2: 基于UID的路径 (常用于系统服务)
        int uid = user_id * PER_USER_RANGE + 10000; 
        struct stat p_stat;
        if(stat(proc_stat_path.c_str(), &p_stat) == 0) uid = p_stat.st_uid;
        std::string mem_path_2 = "/sys/fs/cgroup/uid_" + std::to_string(uid) + "/memory.current";

        std::ifstream mem_file_1(mem_path_1);
        if (mem_file_1.is_open()) {
            mem_file_1 >> mem_bytes;
        } else {
            std::ifstream mem_file_2(mem_path_2);
            if (mem_file_2.is_open()) {
                 mem_file_2 >> mem_bytes;
            }
        }
    }

    if (mem_bytes != -1) {
        stats.mem_usage_kb = mem_bytes / 1024;
    } else {
        // Fallback for PSS for all cases (v1, or v2 failed)
        std::string smaps_rollup_path = "/proc/" + std::to_string(pid) + "/smaps_rollup";
        std::ifstream rollup_file(smaps_rollup_path);
        if (rollup_file.is_open()) {
            std::string line;
            while (std::getline(rollup_file, line)) {
                if (line.rfind("Pss:", 0) == 0) { // 使用 Pss 而非 Pss_Total 更通用
                    std::stringstream ss(line);
                    std::string key;
                    long value;
                    ss >> key >> value;
                    stats.mem_usage_kb = value;
                } else if (line.rfind("Swap:", 0) == 0) {
                     std::stringstream ss(line);
                     std::string key;
                     long value;
                     ss >> key >> value;
                     stats.swap_usage_kb = value;
                }
            }
        } else {
             LOGW("Cannot read memory for pid %d (%s), cgroup v2 path and smaps_rollup failed.", pid, package_name.c_str());
        }
    }
    
    // --- 2. CPU使用率获取 (逻辑不变) ---
    std::ifstream stat_file(proc_stat_path);
    if (stat_file.is_open()) {
        std::string line;
        std::getline(stat_file, line);
        std::stringstream ss(line);
        std::string value;
        for(int i=0; i<13; ++i) ss >> value; 
        long long utime, stime;
        ss >> utime >> stime;
        long long current_app_jiffies = utime + stime;
        
        long long current_total_jiffies = prev_cpu_times_.total();
        auto& cpu_state = app_cpu_states_[pid];

        if (cpu_state.prev_app_jiffies > 0 && current_total_jiffies > cpu_state.prev_total_jiffies) {
            long long app_delta = current_app_jiffies - cpu_state.prev_app_jiffies;
            long long total_delta = current_total_jiffies - cpu_state.prev_total_jiffies;
            if (total_delta > 0 && app_delta >= 0) {
                stats.cpu_usage_percent = 100.0f * static_cast<float>(app_delta) / static_cast<float>(total_delta);
            }
        }
        
        cpu_state.prev_app_jiffies = current_app_jiffies;
        cpu_state.prev_total_jiffies = current_total_jiffies;
    }

    return stats;
}

void SystemMonitor::update_cpu_usage() {
    std::ifstream stat_file("/proc/stat");
    if (!stat_file.is_open()) return;
    std::string line;
    std::getline(stat_file, line);
    stat_file.close();
    std::string cpu_label;
    CpuTimes current_times;
    std::stringstream ss(line);
    ss >> cpu_label >> current_times.user >> current_times.nice >> current_times.system >> current_times.idle
       >> current_times.iowait >> current_times.irq >> current_times.softirq >> current_times.steal;
    if (cpu_label == "cpu") {
        long long prev_total = prev_cpu_times_.total();
        long long current_total = current_times.total();
        long long delta_total = current_total - prev_total;
        if (delta_total > 0) {
            long long prev_idle = prev_cpu_times_.idle_total();
            long long current_idle = current_times.idle_total();
            long long delta_idle = current_idle - prev_idle;
            float cpu_usage = 100.0f * (1.0f - static_cast<float>(delta_idle) / static_cast<float>(delta_total));
            std::lock_guard<std::mutex> lock(data_mutex_);
            current_stats_.total_cpu_usage_percent = cpu_usage >= 0.0f ? cpu_usage : 0.0f;
        }
        prev_cpu_times_ = current_times;
    }
}

void SystemMonitor::update_mem_info() {
    std::ifstream meminfo_file("/proc/meminfo");
    if (!meminfo_file.is_open()) return;
    std::string line;
    long mem_total = 0, mem_available = 0, swap_total = 0, swap_free = 0;
    int found_count = 0;
    while (std::getline(meminfo_file, line) && found_count < 4) {
        std::string key;
        long value;
        std::stringstream ss(line);
        ss >> key >> value;
        if (key == "MemTotal:") { mem_total = value; found_count++; }
        else if (key == "MemAvailable:") { mem_available = value; found_count++; }
        else if (key == "SwapTotal:") { swap_total = value; found_count++; }
        else if (key == "SwapFree:") { swap_free = value; found_count++; }
    }
    meminfo_file.close();
    std::lock_guard<std::mutex> lock(data_mutex_);
    current_stats_.total_mem_kb = mem_total;
    current_stats_.avail_mem_kb = mem_available;
    current_stats_.swap_total_kb = swap_total;
    current_stats_.swap_free_kb = swap_free;
}