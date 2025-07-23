// daemon/cpp/system_monitor.cpp
#include "system_monitor.h"
#include <fstream>
#include <sstream>
#include <android/log.h>
#include <unistd.h>
#include <filesystem>
#include <sys/inotify.h>
#include <sys/select.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <fcntl.h>
#include <climits>
#include <vector>
#include <cstdio>
#include <array>
#include <cctype>
#include <ctime>
#include <algorithm>
#include <iterator>
#include <chrono> // 新增
#include <string> // 包含 <string> 以使用 std::string::npos

// 更新日志标签，便于跟踪
#define LOG_TAG "cerberusd_monitor_v12_final"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;

int get_uid_from_pid(int pid) {
    char path_buffer[64];
    snprintf(path_buffer, sizeof(path_buffer), "/proc/%d", pid);
    struct stat st;
    if (stat(path_buffer, &st) != 0) return -1;
    return st.st_uid;
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

// --- [核心修复] 终极版音频状态检测 ---
void SystemMonitor::update_audio_state() {
    std::set<int> focus_uids;
    std::set<int> started_player_uids;
    std::array<char, 2048> buffer;

    FILE* pipe = popen("dumpsys audio", "r");
    if (!pipe) {
        LOGW("popen for 'dumpsys audio' failed: %s", strerror(errno));
        return;
    }

    enum class ParseSection { NONE, FOCUS, PLAYERS };
    ParseSection current_section = ParseSection::NONE;

    while (fgets(buffer.data(), buffer.size(), pipe) != nullptr) {
        std::string line(buffer.data());

        if (line.find("Audio Focus stack entries") != std::string::npos) {
            current_section = ParseSection::FOCUS;
            continue;
        }
        if (line.find("  players:") != std::string::npos) {
            current_section = ParseSection::PLAYERS;
            continue;
        }
        if (!line.empty() && !isspace(line[0])) {
            current_section = ParseSection::NONE;
        }

        if (current_section == ParseSection::NONE) continue;
        
        try {
            if (current_section == ParseSection::FOCUS) {
                const std::string uid_marker = "-- uid: ";
                size_t marker_pos = line.find(uid_marker);
                if (marker_pos != std::string::npos) {
                    int uid = std::stoi(line.substr(marker_pos + uid_marker.length()));
                    if (uid >= 10000) focus_uids.insert(uid);
                }
            } else if (current_section == ParseSection::PLAYERS) {
                if (line.find("state:started") != std::string::npos) {
                    const std::string uid_marker = "u/pid:";
                    size_t uid_marker_pos = line.find(uid_marker);
                    if (uid_marker_pos != std::string::npos) {
                        int uid = 0;
                        std::stringstream ss(line.substr(uid_marker_pos + uid_marker.length()));
                        ss >> uid;
                        if (uid >= 10000) started_player_uids.insert(uid);
                    }
                }
            }
        } catch (const std::exception& e) { /* ignore parsing errors */ }
    }
    pclose(pipe);

    // [关键逻辑] 取两个集合的交集，得到真正播放音频的UID
    std::set<int> active_uids;
    std::set_intersection(focus_uids.begin(), focus_uids.end(),
                          started_player_uids.begin(), started_player_uids.end(),
                          std::inserter(active_uids, active_uids.begin()));

    {
        std::lock_guard<std::mutex> lock(audio_uids_mutex_);
        if (uids_playing_audio_ != active_uids) {
            std::stringstream ss;
            for(int uid : active_uids) { ss << uid << " "; }
            LOGI("Audio active UIDs changed. Old count: %zu, New count: %zu. Active UIDs: [ %s]", 
                 uids_playing_audio_.size(), active_uids.size(), ss.str().c_str());
            uids_playing_audio_ = active_uids;
        }
    }
}

bool SystemMonitor::is_uid_playing_audio(int uid) {
    std::lock_guard<std::mutex> lock(audio_uids_mutex_);
    return uids_playing_audio_.count(uid) > 0;
}

// [核心新增] 启动和停止网络快照线程
void SystemMonitor::start_network_snapshot_thread() {
    if (network_monitoring_active_) return;
    network_monitoring_active_ = true;
    // 首次运行时，先获取一次基准快照
    {
        std::lock_guard<std::mutex> lock(traffic_mutex_);
        last_traffic_snapshot_ = read_current_traffic();
        last_snapshot_time_ = std::chrono::steady_clock::now();
    }
    network_thread_ = std::thread(&SystemMonitor::network_snapshot_thread_func, this);
    LOGI("Network snapshot thread started.");
}

void SystemMonitor::stop_network_snapshot_thread() {
    network_monitoring_active_ = false;
    if (network_thread_.joinable()) {
        network_thread_.join();
    }
}

void SystemMonitor::network_snapshot_thread_func() {
    while (network_monitoring_active_) {
        std::this_thread::sleep_for(std::chrono::seconds(5));
        if (!network_monitoring_active_) break;

        // 步骤1: 获取新旧快照和时间
        auto current_snapshot = read_current_traffic();
        auto current_time = std::chrono::steady_clock::now();
        std::map<int, TrafficStats> last_snapshot;
        std::chrono::steady_clock::time_point last_time;
        {
            std::lock_guard<std::mutex> lock(traffic_mutex_);
            last_snapshot = last_traffic_snapshot_;
            last_time = last_snapshot_time_;
        }

        // 步骤2: 计算时间差
        double time_delta_sec = std::chrono::duration_cast<std::chrono::duration<double>>(current_time - last_time).count();
        if (time_delta_sec < 0.1) continue; // 时间太短，跳过本次计算

        // 步骤3: 遍历当前快照，为所有应用计算速率
        std::map<int, NetworkSpeed> new_speeds;
        for (const auto& [uid, current_stats] : current_snapshot) {
            auto last_it = last_snapshot.find(uid);
            if (last_it != last_snapshot.end()) {
                long long rx_delta = current_stats.rx_bytes - last_it->second.rx_bytes;
                long long tx_delta = current_stats.tx_bytes - last_it->second.tx_bytes;
                
                NetworkSpeed speed;
                if (rx_delta > 0) {
                    speed.download_kbps = (static_cast<double>(rx_delta) / 1024.0) / time_delta_sec;
                }
                if (tx_delta > 0) {
                    speed.upload_kbps = (static_cast<double>(tx_delta) / 1024.0) / time_delta_sec;
                }
                
                // 只有速率大于0才记录
                if (speed.download_kbps > 0.1 || speed.upload_kbps > 0.1) {
                    new_speeds[uid] = speed;
                }
            }
        }
        
        // 步骤4: 线程安全地更新全局速率缓存和基准快照
        {
            std::lock_guard<std::mutex> lock(speed_mutex_);
            uid_network_speed_ = new_speeds;
        }
        {
            std::lock_guard<std::mutex> lock(traffic_mutex_);
            last_traffic_snapshot_ = current_snapshot;
            last_snapshot_time_ = current_time;
        }
        LOGD("Network speed cache updated for %zu UIDs.", new_speeds.size());
    }
    LOGI("Network snapshot thread stopped.");
}

// 这是唯一的、正确的函数定义
NetworkSpeed SystemMonitor::get_cached_network_speed(int uid) {
    std::lock_guard<std::mutex> lock(speed_mutex_);
    auto it = uid_network_speed_.find(uid);
    if (it != uid_network_speed_.end()) {
        LOGD("NETWORK_CACHE: Hit for UID %d -> DL: %.1f KB/s, UL: %.1f KB/s",
             uid, it->second.download_kbps, it->second.upload_kbps);
        return it->second;
    }
    LOGD("NETWORK_CACHE: Miss for UID %d", uid);
    return NetworkSpeed();
}


// 辅助函数，执行shell命令并返回结果
static std::string exec_shell_pipe(const char* cmd) {
    std::array<char, 128> buffer;
    std::string result;
    std::unique_ptr<FILE, decltype(&pclose)> pipe(popen(cmd, "r"), pclose);
    if (!pipe) {
        LOGE("popen() failed!");
        return "";
    }
    while (fgets(buffer.data(), buffer.size(), pipe.get()) != nullptr) {
        result += buffer.data();
    }
    return result;
}

// [核心新增] 封装数据源读取逻辑，实现优雅降级
std::map<int, TrafficStats> SystemMonitor::read_current_traffic() {
    std::map<int, TrafficStats> snapshot;
    const std::string qtaguid_path = "/proc/net/xt_qtaguid/stats";

    std::ifstream qtaguid_file(qtaguid_path);
    if (qtaguid_file.is_open()) {
        // 方案A: 读取内核文件 (高效)
        std::string line;
        std::getline(qtaguid_file, line); // 跳过表头
        while (std::getline(qtaguid_file, line)) {
            std::stringstream ss(line);
            std::string idx, iface, acct_tag, set;
            int uid, cnt_set, protocol;
            long long rx_bytes, rx_packets, tx_bytes, tx_packets;
            ss >> idx >> iface >> acct_tag >> uid >> cnt_set >> set >> protocol >> rx_bytes >> rx_packets >> tx_bytes >> tx_packets;
            if (uid >= 10000) {
                snapshot[uid].rx_bytes += rx_bytes;
                snapshot[uid].tx_bytes += tx_bytes;
            }
        }
    } else {
        // 方案B: dumpsys netstats (兼容)
        std::string result = exec_shell_pipe("dumpsys netstats");
        std::stringstream ss(result);
        std::string line;
        bool in_map_section = false;

        while (std::getline(ss, line)) {
            if (line.find("mAppUidStatsMap:") != std::string::npos) {
                in_map_section = true;
                std::getline(ss, line); // 跳过表头
                continue;
            }
            if (in_map_section) {
                if(line.empty() || !isspace(line[0])) break;
                
                std::stringstream line_ss(line);
                int uid;
                long long rx_bytes, tx_bytes, rx_packets, tx_packets;
                line_ss >> uid >> rx_bytes >> rx_packets >> tx_bytes >> tx_packets;
                
                if (uid >= 10000) {
                    snapshot[uid].rx_bytes += rx_bytes;
                    snapshot[uid].tx_bytes += tx_bytes;
                }
            }
        }
    }
    return snapshot;
}

// [核心新增] 按需计算指定UID的瞬时网速
NetworkSpeed SystemMonitor::get_cached_network_speed(int uid) {
    // 步骤1：获取当前快照和时间
    auto current_snapshot = read_current_traffic();
    auto current_time = std::chrono::steady_clock::now();

    // 步骤2：获取上一次的快照和时间
    std::map<int, TrafficStats> last_snapshot;
    std::chrono::steady_clock::time_point last_time;

    {
        std::lock_guard<std::mutex> lock(traffic_mutex_);
        last_snapshot = last_traffic_snapshot_;
        last_time = last_snapshot_time_;
    }

    NetworkSpeed speed;
    auto last_it = last_snapshot.find(uid);
    auto current_it = current_snapshot.find(uid);

    // 步骤3：进行计算（只有当新旧快照都存在该UID时）
    if (last_it != last_snapshot.end() && current_it != current_snapshot.end()) {
        double time_delta_sec = std::chrono::duration_cast<std::chrono::duration<double>>(current_time - last_time).count();
        
        // 增加安全检查和日志
        if (time_delta_sec < 0.1) {
            LOGD("NETWORK: Time delta too small (%.2fs) for UID %d, skipping calculation.", time_delta_sec, uid);
            return speed;
        }

        long long rx_delta = current_it->second.rx_bytes - last_it->second.rx_bytes;
        long long tx_delta = current_it->second.tx_bytes - last_it->second.tx_bytes;

        // 只有当流量增加了才计算速率
        if (rx_delta > 0) {
            speed.download_kbps = (static_cast<double>(rx_delta) / 1024.0) / time_delta_sec;
        }
        if (tx_delta > 0) {
            speed.upload_kbps = (static_cast<double>(tx_delta) / 1024.0) / time_delta_sec;
        }
        
        // 增加详细日志，便于调试
        LOGD("NETWORK: UID %d | RX_Delta: %lld B | TX_Delta: %lld B | Time_Delta: %.2fs | DL_Speed: %.1f KB/s | UL_Speed: %.1f KB/s",
             uid, rx_delta, tx_delta, time_delta_sec, speed.download_kbps, speed.upload_kbps);

    } else {
        LOGD("NETWORK: UID %d not found in both snapshots, cannot calculate speed.", uid);
    }
    
    return speed;
}

std::string SystemMonitor::get_current_ime_package() {
    std::lock_guard<std::mutex> lock(ime_mutex_);
    time_t now = time(nullptr);
    
    if (now - last_ime_check_time_ > 60 || current_ime_package_.empty()) {
        std::array<char, 256> buffer;
        std::string result = "";
        FILE* pipe = popen("settings get secure default_input_method", "r");
        if (pipe) {
            if (fgets(buffer.data(), buffer.size(), pipe) != nullptr) {
                result = buffer.data();
            }
            pclose(pipe);
        }

        size_t slash_pos = result.find('/');
        if (slash_pos != std::string::npos) {
            current_ime_package_ = result.substr(0, slash_pos);
        } else {
            result.erase(std::remove(result.begin(), result.end(), '\n'), result.end());
            current_ime_package_ = result;
        }
        
        last_ime_check_time_ = now;
        LOGD("Checked default IME: '%s'", current_ime_package_.c_str());
    }
    
    return current_ime_package_;
}

// [核心新增] 实现定位状态检测
void SystemMonitor::update_location_state() {
    std::set<int> active_uids;
    std::array<char, 2048> buffer;

    FILE* pipe = popen("dumpsys location", "r");
    if (!pipe) {
        LOGW("popen for 'dumpsys location' failed: %s", strerror(errno));
        return;
    }

    enum class ParseSection { NONE, ACTIVE_PROVIDER };
    ParseSection current_section = ParseSection::NONE;

    while (fgets(buffer.data(), buffer.size(), pipe) != nullptr) {
        std::string line(buffer.data());
        
        // 状态机切换
        if (line.find("gps provider:") != std::string::npos || line.find("network provider:") != std::string::npos) {
            current_section = ParseSection::ACTIVE_PROVIDER;
            continue;
        }
        
        // 如果遇到不带缩进的行，意味着 provider 段落结束了
        if (!line.empty() && !isspace(line[0])) {
            current_section = ParseSection::NONE;
        }

        if (current_section != ParseSection::ACTIVE_PROVIDER) {
            continue;
        }
        
        // 解析监听者
        // 格式: "    UID/PACKAGENAME/HASH Request[...]"
        if (line.find("Request[") != std::string::npos) {
            // 过滤掉被动请求
            if (line.find("PASSIVE") != std::string::npos) {
                continue;
            }

            try {
                // 提取 UID
                std::string trimmed_line = line;
                trimmed_line.erase(0, trimmed_line.find_first_not_of(" \t"));
                
                std::stringstream ss(trimmed_line);
                int uid = 0;
                ss >> uid;

                if (uid >= 10000) {
                    active_uids.insert(uid);
                    LOGD("LOCATION: Found active UID %d in request: %s", uid, trimmed_line.c_str());
                }
            } catch (const std::exception& e) {
                LOGW("LOCATION: Parsing error on line: %s", line.c_str());
            }
        }
    }

    pclose(pipe);

    {
        std::lock_guard<std::mutex> lock(location_uids_mutex_);
        if (uids_using_location_ != active_uids) {
            std::stringstream ss;
            for(int uid : active_uids) { ss << uid << " "; }
            LOGI("Active location UIDs changed. Old count: %zu, New count: %zu. Active UIDs: [ %s]", uids_using_location_.size(), active_uids.size(), ss.str().c_str());
            uids_using_location_ = active_uids;
        }
    }
}


bool SystemMonitor::is_uid_using_location(int uid) {
    std::lock_guard<std::mutex> lock(location_uids_mutex_);
    return uids_using_location_.count(uid) > 0;
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
            float cpu_usage = 100.0f * static_cast<float>(delta_total - delta_idle) / static_cast<float>(delta_total);
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