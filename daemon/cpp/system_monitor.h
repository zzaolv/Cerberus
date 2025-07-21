// daemon/cpp/system_monitor.h
#ifndef CERBERUS_SYSTEM_MONITOR_H
#define CERBERUS_SYSTEM_MONITOR_H

#include <string>
#include <mutex>
#include <map>
#include <vector>
#include <set>
#include <thread>
#include <atomic>
#include <functional>

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

extern std::atomic<int> g_top_app_refresh_tickets;

class SystemMonitor {
public:
    SystemMonitor();
    ~SystemMonitor();
    
    void update_global_stats();
    GlobalStatsData get_global_stats() const;

    void update_app_stats(const std::vector<int>& pids, long& mem_kb, long& swap_kb, float& cpu_percent);
    std::string get_app_name_from_pid(int pid);

    void start_top_app_monitor();
    void stop_top_app_monitor();
    std::set<int> read_top_app_pids();

    // [V8 新增] 音频监控
    void start_audio_monitor();
    void stop_audio_monitor();
    bool is_pid_playing_audio(int uid);

private:
    void update_cpu_usage();
    void update_mem_info();
    void top_app_monitor_thread();
    
    // [V8 新增] 音频监控线程
    void audio_monitor_thread();

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

    std::thread monitor_thread_;
    std::atomic<bool> monitoring_active_{false};
    std::string top_app_tasks_path_;

    // [V8-Hotfix] 成员变量改为存储UID
    std::thread audio_thread_;
    std::atomic<bool> audio_monitoring_active_{false};
    std::mutex audio_uids_mutex_;
    std::set<int> uids_playing_audio_;
};

#endif //CERBERUS_SYSTEM_MONITOR_H