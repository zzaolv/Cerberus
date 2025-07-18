// daemon/cpp/system_monitor.cpp
#include "system_monitor.h"
#include <fstream>
#include <sstream>
#include <android/log.h>
#include <unistd.h>
#include <filesystem>

#define LOG_TAG "cerberusd_monitor"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;

SystemMonitor::SystemMonitor() {
    update_global_stats();
}

void SystemMonitor::update_global_stats() {
    update_cpu_usage();
    update_mem_info();
}

GlobalStatsData SystemMonitor::get_global_stats() const {
    std::lock_guard<std::mutex> lock(data_mutex_);
    return current_stats_;
}

void SystemMonitor::update_app_stats(const std::vector<int>& pids, long& total_mem_kb, long& total_swap_kb, float& total_cpu_percent) {
    total_mem_kb = 0;
    total_swap_kb = 0;
    total_cpu_percent = 0.0f;

    if (pids.empty()) return;

    for (int pid : pids) {
        std::string proc_path = "/proc/" + std::to_string(pid);
        if (!fs::exists(proc_path)) continue;

        // 1. 内存获取 (优先smaps_rollup，更准确)
        std::ifstream rollup_file(proc_path + "/smaps_rollup");
        if (rollup_file.is_open()) {
            std::string line;
            while (std::getline(rollup_file, line)) {
                std::stringstream ss(line);
                std::string key;
                long value;
                ss >> key >> value;
                if (key == "Pss:") total_mem_kb += value;
                else if (key == "Swap:") total_swap_kb += value;
            }
        }

        // 2. CPU使用率获取
        std::ifstream stat_file(proc_path + "/stat");
        if (stat_file.is_open()) {
            std::string line;
            std::getline(stat_file, line);
            std::stringstream ss(line);
            std::string value;
            for(int i = 0; i < 13; ++i) ss >> value; // 跳过前面13个字段
            long long utime, stime;
            ss >> utime >> stime;
            
            long long current_app_jiffies = utime + stime;
            long long current_total_jiffies;
            {
                std::lock_guard<std::mutex> lock(data_mutex_);
                current_total_jiffies = prev_total_cpu_times_.total();
            }

            auto& prev_times = app_cpu_times_[pid];
            if (prev_times.app_jiffies > 0 && prev_times.total_jiffies > 0) {
                long long app_delta = current_app_jiffies - prev_times.app_jiffies;
                long long total_delta = current_total_jiffies - prev_times.total_jiffies;
                if (total_delta > 0 && app_delta >= 0) {
                    total_cpu_percent += 100.0f * static_cast<float>(app_delta) / static_cast<float>(total_delta);
                }
            }
            prev_times.app_jiffies = current_app_jiffies;
            prev_times.total_jiffies = current_total_jiffies;
        }
    }
}

std::string SystemMonitor::get_app_name_from_pid(int pid) {
    std::string status_path = "/proc/" + std::to_string(pid) + "/status";
    std::ifstream status_file(status_path);
    if (status_file.is_open()) {
        std::string line;
        while (std::getline(status_file, line)) {
            if (line.rfind("Name:", 0) == 0) {
                return line.substr(line.find(":") + 1);
            }
        }
    }
    // Fallback to cmdline
    std::string cmdline_path = "/proc/" + std::to_string(pid) + "/cmdline";
    std::ifstream cmdline_file(cmdline_path);
     if (cmdline_file.is_open()) {
        std::string cmdline;
        std::getline(cmdline_file, cmdline, '\0');
        return cmdline;
    }
    return "Unknown";
}


void SystemMonitor::update_cpu_usage() {
    std::ifstream stat_file("/proc/stat");
    if (!stat_file.is_open()) return;

    std::string line;
    std::getline(stat_file, line);
    std::string cpu_label;
    TotalCpuTimes current_times;
    std::stringstream ss(line);
    ss >> cpu_label >> current_times.user >> current_times.nice >> current_times.system >> current_times.idle
       >> current_times.iowait >> current_times.irq >> current_times.softirq >> current_times.steal;
    
    if (cpu_label == "cpu") {
        long long prev_total = prev_total_cpu_times_.total();
        long long current_total = current_times.total();
        long long delta_total = current_total - prev_total;

        if (delta_total > 0) {
            long long delta_idle = current_times.idle_total() - prev_total_cpu_times_.idle_total();
            float cpu_usage = 100.0f * (static_cast<float>(delta_total - delta_idle) / static_cast<float>(delta_total));
            
            std::lock_guard<std::mutex> lock(data_mutex_);
            current_stats_.total_cpu_usage_percent = std::max(0.0f, cpu_usage);
        }
        prev_total_cpu_times_ = current_times;
    }
}

void SystemMonitor::update_mem_info() {
    std::ifstream meminfo_file("/proc/meminfo");
    if (!meminfo_file.is_open()) return;
    std::string line;
    long mem_total = 0, mem_available = 0, swap_total = 0, swap_free = 0;

    while (std::getline(meminfo_file, line)) {
        std::string key;
        long value;
        std::stringstream ss(line);
        ss >> key >> value;
        if (key == "MemTotal:") mem_total = value;
        else if (key == "MemAvailable:") mem_available = value;
        else if (key == "SwapTotal:") swap_total = value;
        else if (key == "SwapFree:") swap_free = value;
    }
    
    std::lock_guard<std::mutex> lock(data_mutex_);
    current_stats_.total_mem_kb = mem_total;
    current_stats_.avail_mem_kb = mem_available;
    current_stats_.swap_total_kb = swap_total;
    current_stats_.swap_free_kb = swap_free;
}