// daemon/cpp/system_monitor.h
#ifndef CERBERUS_SYSTEM_MONITOR_H
#define CERBERUS_SYSTEM_MONITOR_H

#include <string>
#include <mutex>
#include <map>

struct GlobalStatsData {
    float total_cpu_usage_percent = 0.0f;
    long total_mem_kb = 0;
    long avail_mem_kb = 0;
    // 【新增】网络速度
    long long net_down_speed_bps = 0;
    long long net_up_speed_bps = 0;
};

struct CpuTimes {
    long long user = 0, nice = 0, system = 0, idle = 0;
    long long iowait = 0, irq = 0, softirq = 0, steal = 0;
    long long total() const { return user + nice + system + idle + iowait + irq + softirq + steal; }
    long long idle_total() const { return idle + iowait; }
};

// 【新增】网络流量数据结构
struct NetworkUsage {
    long long rx_bytes = 0;
    long long tx_bytes = 0;
};

class SystemMonitor {
public:
    SystemMonitor();
    // 修改为 const 方法
    GlobalStatsData get_stats() const;
    void update_all_stats();

private:
    void update_cpu_usage();
    void update_mem_info();
    void update_network_stats();

    // 改为 mutable 以便在 const 方法中加锁
    mutable std::mutex data_mutex_;
    GlobalStatsData current_stats_;

    // For CPU usage calculation
    CpuTimes prev_cpu_times_;

    // 【新增】For network usage calculation
    std::map<int, NetworkUsage> prev_network_usage_;
    long long prev_total_rx_ = 0;
    long long prev_total_tx_ = 0;
    std::chrono::steady_clock::time_point prev_net_time_;
};

#endif //CERBERUS_SYSTEM_MONITOR_H