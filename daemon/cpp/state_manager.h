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
    long long net_down_speed_bps = 0;
    long long net_up_speed_bps = 0;
};

struct AppStatsData {
    float cpu_usage_percent = 0.0f;
    long mem_usage_kb = 0;
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
    
    void update_all_stats();
    GlobalStatsData get_stats() const;
    AppStatsData get_app_stats(int uid, const std::string& package_name);

private:
    void update_cpu_usage();
    void update_mem_info();
    void update_network_stats();
    void update_app_stats(int uid, const std::string& package_name);

    mutable std::mutex data_mutex_;
    GlobalStatsData current_stats_;
    CpuTimes prev_cpu_times_;

    long long prev_total_rx_ = 0;
    long long prev_total_tx_ = 0;
    std::chrono::steady_clock::time_point prev_net_time_;
    bool is_first_net_read_; // 【新增】标志位

    struct AppCpuState {
        long long prev_app_jiffies = 0;
        long long prev_total_jiffies = 0;
    };
    std::map<int, AppCpuState> app_cpu_states_;
};

#endif //CERBERUS_SYSTEM_MONITOR_H