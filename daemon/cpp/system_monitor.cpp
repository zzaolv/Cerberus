// daemon/cpp/system_monitor.cpp
#include "system_monitor.h"
#include <fstream>
#include <sstream>
#include <android/log.h>

#define LOG_TAG "cerberusd_monitor"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

SystemMonitor::SystemMonitor() {
    // Initial read to populate prev_cpu_times_
    update_cpu_usage();
}

GlobalStatsData SystemMonitor::get_stats() {
    update_cpu_usage();
    update_mem_info();
    std::lock_guard<std::mutex> lock(data_mutex_);
    return current_stats_;
}

void SystemMonitor::update_cpu_usage() {
    std::ifstream stat_file("/proc/stat");
    if (!stat_file.is_open()) {
        LOGW("Failed to open /proc/stat");
        return;
    }

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
            current_stats_.total_cpu_usage_percent = cpu_usage > 0.0f ? cpu_usage : 0.0f;
        }
        prev_cpu_times_ = current_times;
    }
}

void SystemMonitor::update_mem_info() {
    std::ifstream meminfo_file("/proc/meminfo");
    if (!meminfo_file.is_open()) {
        LOGW("Failed to open /proc/meminfo");
        return;
    }

    std::string line;
    long mem_total = 0, mem_available = 0;

    while (std::getline(meminfo_file, line)) {
        if (line.rfind("MemTotal:", 0) == 0) {
            std::stringstream(line) >> line >> mem_total;
        } else if (line.rfind("MemAvailable:", 0) == 0) {
            std::stringstream(line) >> line >> mem_available;
        }
        if (mem_total > 0 && mem_available > 0) {
            break;
        }
    }
    meminfo_file.close();

    std::lock_guard<std::mutex> lock(data_mutex_);
    current_stats_.total_mem_kb = mem_total;
    current_stats_.avail_mem_kb = mem_available;
}