// daemon/cpp/system_monitor.h
#ifndef CERBERUS_SYSTEM_MONITOR_H
#define CERBERUS_SYSTEM_MONITOR_H

#include <string>
#include <mutex>

struct GlobalStatsData {
    float total_cpu_usage_percent = 0.0f;
    long total_mem_kb = 0;
    long avail_mem_kb = 0;
    // ... 其他全局状态
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
    GlobalStatsData get_stats();

private:
    void update_cpu_usage();
    void update_mem_info();

    std::mutex data_mutex_;
    GlobalStatsData current_stats_;

    // For CPU usage calculation
    CpuTimes prev_cpu_times_;
};

#endif //CERBERUS_SYSTEM_MONITOR_H