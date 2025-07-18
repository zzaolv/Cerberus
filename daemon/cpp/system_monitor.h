// daemon/cpp/system_monitor.h
#ifndef CERBERUS_SYSTEM_MONITOR_H
#define CERBERUS_SYSTEM_MONITOR_H

#include <string>
#include <mutex>
#include <map>
#include <vector>
#include <chrono>

struct GlobalStatsData {
    float total_cpu_usage_percent = 0.0f;
    long total_mem_kb = 0;
    long avail_mem_kb = 0;
    long swap_total_kb = 0;
    long swap_free_kb = 0;
};

// 记录单个进程的CPU时间片，用于计算使用率
struct CpuTimeSlice {
    long long app_jiffies = 0;
    long long total_jiffies = 0;
};

class SystemMonitor {
public:
    SystemMonitor();
    
    // 更新全局统计数据 (CPU, Mem)
    void update_global_stats();
    GlobalStatsData get_global_stats() const;

    // 【核心增强】更新一个应用（可能包含多个PID）的资源统计数据
    void update_app_stats(const std::vector<int>& pids, long& mem_kb, long& swap_kb, float& cpu_percent);
    
    // 【新增】通过PID获取应用的可读名称
    std::string get_app_name_from_pid(int pid);

private:
    void update_cpu_usage();
    void update_mem_info();

    struct TotalCpuTimes {
        long long user = 0, nice = 0, system = 0, idle = 0;
        long long iowait = 0, irq = 0, softirq = 0, steal = 0;
        long long total() const { return user + nice + system + idle + iowait + irq + softirq + steal; }
        long long idle_total() const { return idle + iowait; }
    };
    
    mutable std::mutex data_mutex_;
    GlobalStatsData current_stats_;
    TotalCpuTimes prev_total_cpu_times_;
    
    // 缓存每个PID的CPU时间片，用于计算增量
    std::map<int, CpuTimeSlice> app_cpu_times_;
};

#endif //CERBERUS_SYSTEM_MONITOR_H