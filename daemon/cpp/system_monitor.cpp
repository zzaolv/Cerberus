// daemon/cpp/system_monitor.cpp
#include "system_monitor.h"
#include <fstream>
#include <sstream>
#include <android/log.h>
#include <unistd.h>
#include <filesystem>
#include <sys/inotify.h>
#include <sys/select.h>
#include <sys/time.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <climits>
#include <vector>

#define LOG_TAG "cerberusd_monitor_v8_hotfix"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;

// [V8-Hotfix] 新的辅助函数，通过PID获取UID
int get_uid_from_pid(int pid) {
    char path_buffer[64];
    snprintf(path_buffer, sizeof(path_buffer), "/proc/%d", pid);
    struct stat st;
    if (stat(path_buffer, &st) != 0) return -1;
    return st.st_uid;
}

// [V8-Hotfix] 重写 lsof 逻辑，返回所有使用设备的PID
std::vector<int> get_pids_from_snd_device(const std::string& device_name) {
    std::vector<int> pids;
    std::string full_device_path = "/dev/snd/" + device_name;
    try {
        for (const auto& dir_entry : fs::directory_iterator("/proc")) {
            if (!dir_entry.is_directory()) continue;
            
            int pid = 0;
            try {
                pid = std::stoi(dir_entry.path().filename().string());
            } catch(...) {
                continue;
            }

            if (pid == 0) continue;

            std::string fd_path = dir_entry.path().string() + "/fd";
            if (!fs::exists(fd_path)) continue;

            for (const auto& fd_entry : fs::directory_iterator(fd_path)) {
                if (fs::is_symlink(fd_entry.symlink_status())) {
                    try {
                        std::string link_target = fs::read_symlink(fd_entry.path()).string();
                        if (link_target == full_device_path) {
                            pids.push_back(pid); // 找到后加入列表，而不是立即返回
                        }
                    } catch (...) { continue; }
                }
            }
        }
    } catch(...) {
        LOGW("Error iterating /proc in get_pids_from_snd_device");
    }
    return pids;
}

// Helper function to find PID using a sound device (kept for compatibility)
int get_pid_from_snd_device(const std::string& device_name) {
    auto pids = get_pids_from_snd_device(device_name);
    return pids.empty() ? -1 : pids[0];
}

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
    stop_audio_monitor();
}

void SystemMonitor::start_top_app_monitor() {
    if (top_app_tasks_path_.empty()) return;
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
    if (top_app_tasks_path_.empty()) return pids;
    std::ifstream file(top_app_tasks_path_);
    int pid;
    while (file >> pid) {
        pids.insert(pid);
    }
    return pids;
}

void SystemMonitor::top_app_monitor_thread() {
    int fd = inotify_init1(IN_CLOEXEC);
    if (fd < 0) { LOGE("inotify_init1 failed: %s", strerror(errno)); return; }
    
    int wd = inotify_add_watch(fd, top_app_tasks_path_.c_str(), IN_CLOSE_WRITE | IN_OPEN | IN_MODIFY);
    if (wd < 0) { LOGE("inotify_add_watch failed for %s: %s", top_app_tasks_path_.c_str(), strerror(errno)); close(fd); return; }

    char buf[sizeof(struct inotify_event) + NAME_MAX + 1];

    while (monitoring_active_) {
        ssize_t len = read(fd, buf, sizeof(buf));
        if (!monitoring_active_) break;
        if (len < 0) {
            if (errno == EINTR) continue;
            break;
        }
        g_top_app_refresh_tickets = 2; 
    }
    inotify_rm_watch(fd, wd);
    close(fd);
    LOGI("Top-app monitor stopped.");
}


// --- [修复] 音频监控实现 ---

void SystemMonitor::start_audio_monitor() {
    if (!fs::exists("/dev/snd")) {
        LOGW("Audio device path /dev/snd not found. Audio monitoring disabled.");
        return;
    }
    audio_monitoring_active_ = true;
    audio_thread_ = std::thread(&SystemMonitor::audio_monitor_thread, this);
    LOGI("Audio monitor started.");
}

void SystemMonitor::stop_audio_monitor() {
    audio_monitoring_active_ = false;
    if (audio_thread_.joinable()) {
        audio_thread_.join();
    }
}

bool SystemMonitor::is_uid_playing_audio(int uid) {
    std::lock_guard<std::mutex> lock(audio_uids_mutex_);
    return uids_playing_audio_.count(uid) > 0;
}


void SystemMonitor::audio_monitor_thread() {
    int fd = inotify_init1(IN_CLOEXEC);
    if (fd < 0) { LOGE("Audio inotify_init1 failed: %s", strerror(errno)); return; }
    
    int wd = inotify_add_watch(fd, "/dev/snd", IN_OPEN | IN_CLOSE_NOWRITE | IN_CLOSE_WRITE);
    if (wd < 0) { LOGE("Audio inotify_add_watch failed: %s", strerror(errno)); close(fd); return; }

    char buf[1024] __attribute__ ((aligned(__alignof__(struct inotify_event))));

    while (audio_monitoring_active_) {
        fd_set read_fds;
        FD_ZERO(&read_fds);
        FD_SET(fd, &read_fds);
        struct timeval tv { .tv_sec = 5, .tv_usec = 0 };

        int ret = select(fd + 1, &read_fds, nullptr, nullptr, &tv);
        if (!audio_monitoring_active_) break;
        if (ret <= 0) continue;

        ssize_t len = read(fd, buf, sizeof(buf));
        if (len < 0) continue;

        const struct inotify_event *event;
        for (char *ptr = buf; ptr < buf + len; ptr += sizeof(struct inotify_event) + event->len) {
            event = (const struct inotify_event *) ptr;
            if (event->len > 0 && std::string(event->name).rfind("pcm", 0) == 0) {
                if (event->mask & IN_OPEN) {
                    auto pids = get_pids_from_snd_device(event->name);
                    if (!pids.empty()) {
                        std::lock_guard<std::mutex> lock(audio_uids_mutex_);
                        for (int pid : pids) {
                            int uid = get_uid_from_pid(pid);
                            if (uid >= 10000) {
                                uids_playing_audio_.insert(uid);
                                LOGI("Audio started by UID: %d (from PID: %d)", uid, pid);
                            }
                        }
                    }
                } else if (event->mask & (IN_CLOSE_WRITE | IN_CLOSE_NOWRITE)) {
                    std::lock_guard<std::mutex> lock(audio_uids_mutex_);
                    if (!uids_playing_audio_.empty()) {
                        LOGI("An audio stream closed. Clearing audio UID list for re-evaluation.");
                        uids_playing_audio_.clear();
                    }
                }
            }
        }
    }
    inotify_rm_watch(fd, wd);
    close(fd);
    LOGI("Audio monitor stopped.");
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