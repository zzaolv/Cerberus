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
#include <chrono> // For time points

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

struct NetworkSpeed {
    double download_kbps = 0.0;
    double upload_kbps = 0.0;
};

struct TrafficStats {
    long long rx_bytes = 0;
    long long tx_bytes = 0;
};

// [OPT_FIX] 新增一个结构体，用于缓存网络速度和最后活动时间
struct NetworkSpeedInfo {
    NetworkSpeed speed;
    std::chrono::steady_clock::time_point last_active;
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
    
    // [OPT_FIX] 这些接口保持不变，但它们的调用方式会改变
    bool is_uid_playing_audio(int uid);
    bool is_uid_using_location(int uid);
    
    std::string get_current_ime_package();
    
    void start_network_snapshot_thread();
    void stop_network_snapshot_thread();
    NetworkSpeed get_cached_network_speed(int uid);

private:
    void update_cpu_usage();
    void update_mem_info();
    void top_app_monitor_thread();
    
    // [OPT_FIX] 内部实现现在会按需调用
    void update_audio_state();
    void update_location_state();
    
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

    std::mutex audio_uids_mutex_;
    std::set<int> uids_playing_audio_;
    
    mutable std::mutex location_uids_mutex_;
    std::set<int> uids_using_location_;
    
    mutable std::mutex ime_mutex_;
    std::string current_ime_package_;
    time_t last_ime_check_time_ = 0;

    void network_snapshot_thread_func();
    std::map<int, TrafficStats> read_current_traffic();

    std::thread network_thread_;
    std::atomic<bool> network_monitoring_active_{false};
    mutable std::mutex traffic_mutex_;
    std::map<int, TrafficStats> last_traffic_snapshot_;
    std::chrono::steady_clock::time_point last_snapshot_time_;
    
    mutable std::mutex speed_mutex_;
    // [OPT_FIX] 缓存现在存储NetworkSpeedInfo
    std::map<int, NetworkSpeedInfo> uid_network_speed_cache_;
};

#endif //CERBERUS_SYSTEM_MONITOR_H