// daemon/cpp/system_monitor.cpp
#include "system_monitor.h"
#include <fstream>
#include <sstream>
#include <android/log.h>
#include <unistd.h>
#include <filesystem>
#include <sys/inotify.h>

#define LOG_TAG "cerberusd_monitor_v6"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;

SystemMonitor::SystemMonitor() {
    if (fs::exists("/dev/cpuset/top-app/tasks")) {
        top_app_tasks_path_ = "/dev/cpuset/top-app/tasks";
    } else if (fs::exists("/dev/cpuset/top-app/cgroup.procs")) {
        top_app_tasks_path_ = "/dev/cpuset/top-app/cgroup.procs";
    } else {
        LOGE("Could not find top-app tasks file. Active monitoring disabled.");
    }
    update_global_stats();
}

SystemMonitor::~SystemMonitor() {
    stop_top_app_monitor();
}

void SystemMonitor::start_top_app_monitor(std::function<void(const std::set<int>&)> callback) {
    if (top_app_tasks_path_.empty()) return;

    on_top_app_changed_ = std::move(callback);
    monitoring_active_ = true;
    monitor_thread_ = std::thread(&SystemMonitor::top_app_monitor_thread, this);
    LOGI("Top-app monitor started for path: %s", top_app_tasks_path_.c_str());
}

void SystemMonitor::stop_top_app_monitor() {
    monitoring_active_ = false;
    if (monitor_thread_.joinable()) {
        monitor_thread_.join();
    }
}

std::set<int> SystemMonitor::read_top_app_pids() {
    std::set<int> pids;
    std::ifstream file(top_app_tasks_path_);
    if (!file.is_open()) return pids;
    int pid;
    while (file >> pid) {
        pids.insert(pid);
    }
    return pids;
}

void SystemMonitor::top_app_monitor_thread() {
    int fd = inotify_init1(IN_CLOEXEC);
    if (fd < 0) {
        LOGE("inotify_init1 failed: %s", strerror(errno));
        return;
    }

    int wd = inotify_add_watch(fd, top_app_tasks_path_.c_str(), IN_MODIFY | IN_OPEN);
    if (wd < 0) {
        LOGE("inotify_add_watch for %s failed: %s", top_app_tasks_path_.c_str(), strerror(errno));
        close(fd);
        return;
    }

    if (on_top_app_changed_) {
        on_top_app_changed_(read_top_app_pids());
    }

    char buf[4096] __attribute__ ((aligned(__alignof__(struct inotify_event))));

    while (monitoring_active_) {
        fd_set read_fds;
        FD_ZERO(&read_fds);
        FD_SET(fd, &read_fds);
        struct timeval tv { .tv_sec = 1, .tv_usec = 0 };

        int ret = select(fd + 1, &read_fds, nullptr, nullptr, &tv);
        if (ret < 0) {
            if (errno == EINTR) continue;
            LOGE("select on inotify fd failed: %s", strerror(errno));
            break;
        }
        if (ret == 0) continue;

        ssize_t len = read(fd, buf, sizeof(buf));
        if (len <= 0) continue;

        if (on_top_app_changed_) {
            on_top_app_changed_(read_top_app_pids());
        }

        // [性能优化] 增加一个短暂的休眠，作为物理节流，防止毫秒级事件风暴
        usleep(50 * 1000); // 50ms
    }

    inotify_rm_watch(fd, wd);
    close(fd);
    LOGI("Top-app monitor stopped.");
}

// --- 其他函数保持不变 ---
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

        std::ifstream stat_file(proc_path + "/stat");
        if (stat_file.is_open()) {
            std::string line;
            std::getline(stat_file, line);
            std::stringstream ss(line);
            std::string value;
            for(int i = 0; i < 13; ++i) ss >> value;
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
                std::string name = line.substr(line.find(":") + 1);
                // Trim leading whitespace
                name.erase(0, name.find_first_not_of(" \t"));
                return name;
            }
        }
    }
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