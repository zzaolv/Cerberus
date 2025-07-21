// daemon/cpp/system_monitor.h
#ifndef CERBERUS_SYSTEM_MONITOR_H
#define CERBERUS_SYSTEM_MONITOR_H

#include <string>
#include <mutex>
#include <map>
#include <vector>
#include <chrono>
#include <set>
#include <functional>
#include <thread>
#include <atomic>

struct GlobalStatsData {
    float total_cpu_usage_percent = 0.0f;
    long total_mem_kb = 0;
    long avail_mem_kb = 0;
    long swap_total_kb = 0;
    long swap_free_kb = 0;
};

struct CpuTimeSlice {
    long long app_jiffies = 0;
    long long total_jiffies = 0;
};

class SystemMonitor {
public:
    SystemMonitor();
    ~SystemMonitor();
    
    void update_global_stats();
    GlobalStatsData get_global_stats() const;

    void update_app_stats(const std::vector<int>& pids, long& mem_kb, long& swap_kb, float& cpu_percent);
    
    std::string get_app_name_from_pid(int pid);

    // [V6 新增] 主动探测 top-app 核心功能
    void start_top_app_monitor(std::function<void(const std::set<int>&)> callback);
    void stop_top_app_monitor();

private:
    void update_cpu_usage();
    void update_mem_info();
    
    // [V6 新增] top-app 监控线程
    void top_app_monitor_thread();
    std::set<int> read_top_app_pids();

    struct TotalCpuTimes {
        long long user = 0, nice = 0, system = 0, idle = 0;
        long long iowait = 0, irq = 0, softirq = 0, steal = 0;
        long long total() const { return user + nice + system + idle + iowait + irq + softirq + steal; }
        long long idle_total() const { return idle + iowait; }
    };
    
    mutable std::mutex data_mutex_;
    GlobalStatsData current_stats_;
    TotalCpuTimes prev_total_cpu_times_;
    
    std::map<int, CpuTimeSlice> app_cpu_times_;

    // [V6 新增] 监控线程相关
    std::thread monitor_thread_;
    std::atomic<bool> monitoring_active_{false};
    std::function<void(const std::set<int>&)> on_top_app_changed_;
    std::string top_app_tasks_path_;
};

#endif //CERBERUS_SYSTEM_MONITOR_H