// daemon/cpp/system_monitor.h
#ifndef CERBERUS_SYSTEM_MONITOR_H
#define CERBERUS_SYSTEM_MONITOR_H

#include "time_series_database.h"
#include <string>
#include <mutex>
#include <map>
#include <vector>
#include <set>
#include <thread>
#include <atomic>
#include <functional>
#include <optional>

// [移除] GlobalStatsData 结构体已完全被 MetricsRecord 取代
// struct GlobalStatsData { ... };

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


extern std::atomic<int> g_top_app_refresh_tickets;

class SystemMonitor {
public:
    SystemMonitor();
    ~SystemMonitor();
    
    std::optional<MetricsRecord> collect_current_metrics();
    
    // [移除] get_global_stats() 接口，所有数据通过 collect_current_metrics() 提供
    // GlobalStatsData get_global_stats() const;

    void update_app_stats(const std::vector<int>& pids, long& mem_kb, long& swap_kb, float& cpu_percent);
    std::string get_app_name_from_pid(int pid);

    void start_top_app_monitor();
    void stop_top_app_monitor();
    std::set<int> read_top_app_pids();
    
    void update_audio_state();
    bool is_uid_playing_audio(int uid);
    
    void update_location_state();
    bool is_uid_using_location(int uid);
    
    std::string get_current_ime_package();
    
    void start_network_snapshot_thread();
    void stop_network_snapshot_thread();
    NetworkSpeed get_cached_network_speed(int uid);

private:
    void update_cpu_usage(float& usage);
    void update_mem_info(long& total, long& available, long& swap_total, long& swap_free);
    bool get_screen_state();
    void get_battery_stats(int& level, float& temp, float& power, bool& charging);

    int get_pid_from_pkg(const std::string& pkg_name);

    // [新增] 重新添加缺失的函数声明
    void top_app_monitor_thread();

    struct TotalCpuTimes {
        long long user = 0, nice = 0, system = 0, idle = 0;
        long long iowait = 0, irq = 0, softirq = 0, steal = 0;
        long long total() const { return user + nice + system + idle + iowait + irq + softirq + steal; }
        long long idle_total() const { return idle + iowait; }
    };
    
    mutable std::mutex data_mutex_;
    // [移除] current_stats_ 成员变量
    // GlobalStatsData current_stats_;
    TotalCpuTimes prev_total_cpu_times_;
    std::map<int, CpuTimeSlice> app_cpu_times_;

    std::set<int> last_known_top_pids_;
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
    std::map<int, NetworkSpeed> uid_network_speed_;
};

#endif //CERBERUS_SYSTEM_MONITOR_H