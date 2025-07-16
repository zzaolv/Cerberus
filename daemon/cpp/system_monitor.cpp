// daemon/cpp/system_monitor.cpp
#include "system_monitor.h"
#include <fstream>
#include <sstream>
#include <android/log.h>
#include <chrono>
#include <unistd.h>
#include <array>
#include <memory>
#include <string>
#include <vector>

#define LOG_TAG "cerberusd_monitor"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

SystemMonitor::SystemMonitor() : prev_total_rx_(0), prev_total_tx_(0), prev_net_time_(std::chrono::steady_clock::now()) {
    update_all_stats();
}

void SystemMonitor::update_all_stats() {
    update_cpu_usage();
    update_mem_info();
    update_network_stats();
}

GlobalStatsData SystemMonitor::get_stats() const {
    std::lock_guard<std::mutex> lock(data_mutex_);
    return current_stats_;
}

// 【核心修改】读取 PSS 内存并重构
AppStatsData SystemMonitor::get_app_stats(int pid) {
    AppStatsData stats;
    if (pid <= 0) return stats;

    // 1. 获取内存使用 (PSS) from smaps_rollup
    std::string smaps_path = "/proc/" + std::to_string(pid) + "/smaps_rollup";
    std::ifstream smaps_file(smaps_path);
    if (smaps_file.is_open()) {
        std::string line;
        while(std::getline(smaps_file, line)) {
            if (line.rfind("Pss_Total:", 0) == 0) {
                try {
                    stats.mem_usage_kb = std::stol(line.substr(10));
                    break;
                } catch (const std::exception&) {
                    // ignore parse error
                }
            }
        }
    }

    // 2. 获取并计算CPU使用率
    std::string stat_path = "/proc/" + std::to_string(pid) + "/stat";
    std::ifstream stat_file(stat_path);
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
        auto& cpu_state = app_cpu_states_[pid]; // Use PID as key

        if (cpu_state.prev_app_jiffies > 0) {
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
    long mem_total = 0, mem_available = 0;

    while (std::getline(meminfo_file, line)) {
        std::string key;
        long value;
        std::stringstream ss(line);
        ss >> key >> value;
        if (key == "MemTotal:") mem_total = value;
        else if (key == "MemAvailable:") mem_available = value;
        if (mem_total > 0 && mem_available > 0) break;
    }
    meminfo_file.close();

    std::lock_guard<std::mutex> lock(data_mutex_);
    current_stats_.total_mem_kb = mem_total;
    current_stats_.avail_mem_kb = mem_available;
}

// 【核心修改】加固网络速度计算逻辑
void SystemMonitor::update_network_stats() {
    std::ifstream net_stats_file("/proc/net/xt_qtaguid/stats");
    if (!net_stats_file.is_open()) { return; }

    long long current_total_rx = 0;
    long long current_total_tx = 0;
    std::string line;
    std::getline(net_stats_file, line); 
    while (std::getline(net_stats_file, line)) {
        std::string iface, tag_hex;
        int idx, uid, cnt_set;
        long long rx_bytes, tx_bytes;
        std::stringstream ss(line);
        ss >> idx >> iface >> tag_hex >> uid >> cnt_set >> rx_bytes >> line >> tx_bytes;
        current_total_rx += rx_bytes;
        current_total_tx += tx_bytes;
    }
    net_stats_file.close();

    auto now = std::chrono::steady_clock::now();
    auto duration_ms = std::chrono::duration_cast<std::chrono::milliseconds>(now - prev_net_time_).count();

    if (prev_total_rx_ > 0 && duration_ms > 100) { // 只有在有旧数据且时间间隔足够时才计算
        long long delta_rx = current_total_rx - prev_total_rx_;
        long long delta_tx = current_total_tx - prev_total_tx_;

        long long down_speed = delta_rx >= 0 ? (delta_rx * 8000 / duration_ms) : 0; // * 8 * 1000 / ms
        long long up_speed = delta_tx >= 0 ? (delta_tx * 8000 / duration_ms) : 0;
        
        // 添加日志用于调试
        // LOGI("NetStats: delta_rx=%lld, delta_tx=%lld, duration=%lldms, down_speed=%lldbps, up_speed=%lldbps", delta_rx, delta_tx, duration_ms, down_speed, up_speed);

        std::lock_guard<std::mutex> lock(data_mutex_);
        current_stats_.net_down_speed_bps = down_speed;
        current_stats_.net_up_speed_bps = up_speed;
    }

    prev_total_rx_ = current_total_rx;
    prev_total_tx_ = current_total_tx;
    prev_net_time_ = now;
}