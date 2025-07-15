// daemon/cpp/system_monitor.cpp
#include "system_monitor.hh"
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


std::string exec_shell(const char* cmd) {
    std::array<char, 256> buffer;
    std::string result;
    std::unique_ptr<FILE, decltype(&pclose)> pipe(popen(cmd, "r"), pclose);
    if (!pipe) {
        LOGE("popen() failed for command: %s", cmd);
        return "";
    }
    while (fgets(buffer.data(), buffer.size(), pipe.get()) != nullptr) {
        result += buffer.data();
    }
    return result;
}

int get_pid_for_package(const std::string& package_name) {
    std::string cmd = "pidof -s " + package_name;
    std::string pid_str = exec_shell(cmd.c_str());
    if (pid_str.empty()) {
        return -1;
    }
    try {
        return std::stoi(pid_str);
    } catch (const std::exception&) {
        return -1;
    }
}


SystemMonitor::SystemMonitor() : prev_net_time_(std::chrono::steady_clock::now()), is_first_net_read_(true) { // 【新增】初始化标志位
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

AppStatsData SystemMonitor::get_app_stats(int uid, const std::string& package_name) {
    AppStatsData stats;
    int pid = get_pid_for_package(package_name);
    if (pid <= 0) {
        return stats;
    }

    std::string statm_path = "/proc/" + std::to_string(pid) + "/statm";
    std::ifstream statm_file(statm_path);
    if (statm_file.is_open()) {
        long size, resident;
        statm_file >> size >> resident;
        stats.mem_usage_kb = resident * sysconf(_SC_PAGESIZE) / 1024;
    }

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

        auto& cpu_state = app_cpu_states_[uid];

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
            current_stats_.total_cpu_usage_percent = cpu_usage >= 0.0f ? cpu_usage : 0.0f;
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
        std::string key;
        long value;
        std::stringstream ss(line);
        ss >> key >> value;
        if (key == "MemTotal:") {
            mem_total = value;
        } else if (key == "MemAvailable:") {
            mem_available = value;
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

void SystemMonitor::update_network_stats() {
    std::ifstream net_stats_file("/proc/net/xt_qtaguid/stats");
    if (!net_stats_file.is_open()) {
        return;
    }

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

    // 【核心修复】使用 is_first_net_read_ 标志位来处理第一次读取
    if (is_first_net_read_) {
        is_first_net_read_ = false;
        // 第一次只记录数据，不计算速度
    } else if (duration_ms > 100) { // 确保有足够的时间间隔
        long long delta_rx = current_total_rx - prev_total_rx_;
        long long delta_tx = current_total_tx - prev_total_tx_;

        // 只有当流量增量为正时才计算
        long long down_speed = delta_rx >= 0 ? (delta_rx * 8 * 1000 / duration_ms) : 0;
        long long up_speed = delta_tx >= 0 ? (delta_tx * 8 * 1000 / duration_ms) : 0;

        std::lock_guard<std::mutex> lock(data_mutex_);
        current_stats_.net_down_speed_bps = down_speed;
        current_stats_.net_up_speed_bps = up_speed;
    }

    prev_total_rx_ = current_total_rx;
    prev_total_tx_ = current_total_tx;
    prev_net_time_ = now;
}