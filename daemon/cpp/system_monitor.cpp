// daemon/cpp/system_monitor.cpp
#include "system_monitor.h"
#include <fstream>
#include <sstream>
#include <android/log.h>
#include <unistd.h>
#include <filesystem>
#include <sys/stat.h>

#define LOG_TAG "cerberusd_monitor"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;
constexpr int PER_USER_RANGE = 100000;

SystemMonitor::SystemMonitor() {
    if (fs::exists("/sys/fs/cgroup/cgroup.controllers")) {
        cgroup_version_ = CgroupVersion::V2;
    } else if (fs::exists("/sys/fs/cgroup/freezer")) {
        cgroup_version_ = CgroupVersion::V1;
    } else {
        cgroup_version_ = CgroupVersion::UNKNOWN;
        LOGW("Could not determine cgroup version.");
    }
    update_global_stats();
    update_network_stats_cache();
}

void SystemMonitor::update_global_stats() {
    update_cpu_usage();
    update_mem_info();
    update_network_stats_cache();
}

GlobalStatsData SystemMonitor::get_global_stats() const {
    std::lock_guard<std::mutex> lock(data_mutex_);
    return current_stats_;
}

AppStatsData SystemMonitor::get_app_stats(int pid, const std::string& package_name, int user_id) {
    AppStatsData stats;
    if (pid <= 0) return stats;

    std::string proc_stat_path = "/proc/" + std::to_string(pid) + "/stat";
    if (!fs::exists(proc_stat_path)) return stats;

    std::string smaps_rollup_path = "/proc/" + std::to_string(pid) + "/smaps_rollup";
    std::ifstream rollup_file(smaps_rollup_path);
    if (rollup_file.is_open()) {
        std::string line;
        while (std::getline(rollup_file, line)) {
            if (line.rfind("Pss:", 0) == 0) {
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
    }

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

AppNetworkStats SystemMonitor::get_app_network_stats(int uid) {
    std::lock_guard<std::mutex> lock(data_mutex_);
    auto it = network_stats_cache_.find(uid);
    if (it != network_stats_cache_.end()) {
        return it->second;
    }
    return AppNetworkStats();
}

void SystemMonitor::update_network_stats_cache() {
    std::lock_guard<std::mutex> lock(data_mutex_);
    network_stats_cache_.clear();

    std::ifstream stats_file("/proc/net/xt_qtaguid/stats");
    if (!stats_file.is_open()) {
        LOGW("Cannot open /proc/net/xt_qtaguid/stats. Network monitoring disabled.");
        return;
    }

    std::string line;
    std::getline(stats_file, line);

    while (std::getline(stats_file, line)) {
        std::stringstream ss(line);
        std::string iface, acct_tag;
        int uid, set, tag_hex;
        long long rx_bytes, rx_packets, tx_bytes, tx_packets;

        ss >> line >> iface >> acct_tag >> uid >> set >> rx_bytes >> rx_packets >> tx_bytes >> tx_packets;
        
        if (ss.fail() || iface == "lo") {
            continue;
        }

        network_stats_cache_[uid].rx_bytes += rx_bytes;
        network_stats_cache_[uid].tx_bytes += tx_bytes;
    }
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