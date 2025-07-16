// daemon/cpp/system_monitor.h
#ifndef CERBERUS_SYSTEM_MONITOR_H
#define CERBERUS_SYSTEM_MONITOR_H

#include <string>
#include <mutex>
#include <map>
#include <chrono>

struct GlobalStatsData {
    float total_cpu_usage_percent = 0.0f;
    long total_mem_kb = 0;
    long avail_mem_kb = 0;
    long swap_total_kb = 0;
    long swap_free_kb = 0;
};

struct AppStatsData {
    float cpu_usage_percent = 0.0f;
    long mem_usage_kb = 0;
    long swap_usage_kb = 0;
};

struct AppNetworkStats {
    long long rx_bytes = 0;
    long long tx_bytes = 0;
};

struct CpuTimes {
    long long user = 0, nice = 0, system = 0, idle = 0;
    long long iowait = 0, irq = 0, softirq = 0, steal = 0;
    long long total() const { return user + nice + system + idle + iowait + irq + softirq + steal; }
    long long idle_total() const { return idle + iowait; }
};

class SystemMonitor {
public:
    SystemMonitor();
    
    void update_global_stats();
    GlobalStatsData get_global_stats() const;

    AppStatsData get_app_stats(int pid, const std::string& package_name, int user_id);
    
    AppNetworkStats get_app_network_stats(int uid);

private:
    void update_cpu_usage();
    void update_mem_info();
    void update_network_stats_cache();

    enum class CgroupVersion { V1, V2, UNKNOWN };
    CgroupVersion cgroup_version_ = CgroupVersion::UNKNOWN;
    
    mutable std::mutex data_mutex_;
    GlobalStatsData current_stats_;
    CpuTimes prev_cpu_times_;
    
    struct AppCpuState {
        long long prev_app_jiffies = 0;
        long long prev_total_jiffies = 0;
    };
    std::map<int, AppCpuState> app_cpu_states_;
    
    std::map<int, AppNetworkStats> network_stats_cache_;
};

#endif //CERBERUS_SYSTEM_MONITOR_H