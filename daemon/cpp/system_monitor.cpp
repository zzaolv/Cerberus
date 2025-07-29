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
#include <chrono>
#include <string>
#include <memory>
#include <map>

#define LOG_TAG "cerberusd_monitor_v22_strict_audio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;

// 静态辅助函数
static std::string exec_shell_pipe(const char* cmd) {
    std::array<char, 256> buffer;
    std::string result;
    std::unique_ptr<FILE, decltype(&pclose)> pipe(popen(cmd, "r"), pclose);
    if (!pipe) {
        LOGE("popen() failed for cmd: %s!", cmd);
        return "";
    }
    while (fgets(buffer.data(), buffer.size(), pipe.get()) != nullptr) {
        result += buffer.data();
    }
    return result;
}

static std::optional<long> read_long_from_file(const std::string& path) {
    std::ifstream file(path);
    if (!file.is_open()) return std::nullopt;
    long value;
    file >> value;
    if (file.fail()) return std::nullopt;
    return value;
}

int get_uid_from_pid(int pid) {
    char path_buffer[64];
    snprintf(path_buffer, sizeof(path_buffer), "/proc/%d", pid);
    struct stat st;
    if (stat(path_buffer, &st) != 0) return -1;
    return st.st_uid;
}


// --- SystemMonitor 类实现 ---

SystemMonitor::SystemMonitor() {
    if (fs::exists("/dev/cpuset/top-app/tasks")) {
        top_app_tasks_path_ = "/dev/cpuset/top-app/tasks";
    } else if (fs::exists("/dev/cpuset/top-app/cgroup.procs")) {
        top_app_tasks_path_ = "/dev/cpuset/top-app/cgroup.procs";
    } else {
        LOGE("Could not find top-app tasks file. Active monitoring disabled.");
    }
    float dummy_usage;
    update_cpu_usage(dummy_usage);
}

SystemMonitor::~SystemMonitor() {
    stop_top_app_monitor();
    stop_network_snapshot_thread();
}

// [核心新增] 实现获取 PID 列表总 Jiffies 的函数
long long SystemMonitor::get_total_cpu_jiffies_for_pids(const std::vector<int>& pids) {
    long long total_jiffies = 0;
    for (int pid : pids) {
        std::string stat_path = "/proc/" + std::to_string(pid) + "/stat";
        std::ifstream stat_file(stat_path);
        if (stat_file.is_open()) {
            std::string line;
            std::getline(stat_file, line);
            std::stringstream ss(line);
            std::string value;
            // utime is the 14th field, stime is the 15th
            for(int i = 0; i < 13; ++i) ss >> value;
            long long utime = 0, stime = 0;
            ss >> utime >> stime;
            total_jiffies += (utime + stime);
        }
    }
    return total_jiffies;
}

std::optional<MetricsRecord> SystemMonitor::collect_current_metrics() {
    MetricsRecord record;
    record.timestamp_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    
    update_cpu_usage(record.cpu_usage_percent);
    update_mem_info(record.mem_total_kb, record.mem_available_kb, record.swap_total_kb, record.swap_free_kb);
    
    get_battery_stats(record.battery_level, record.battery_temp_celsius, record.battery_power_watt, record.is_charging);
    record.is_screen_on = get_screen_state();

    {
        std::lock_guard<std::mutex> lock(audio_uids_mutex_);
        record.is_audio_playing = !uids_playing_audio_.empty();
    }
    {
        std::lock_guard<std::mutex> lock(location_uids_mutex_);
        record.is_location_active = !uids_using_location_.empty();
    }

    return record;
}

bool SystemMonitor::get_screen_state() {
    std::string result = exec_shell_pipe("dumpsys power | grep -E 'mWakefulness|mScreenState' | head -n 1");
    return result.find("Awake") != std::string::npos || result.find("ON") != std::string::npos;
}

void SystemMonitor::get_battery_stats(int& level, float& temp, float& power, bool& charging) {
    const std::string battery_path = "/sys/class/power_supply/battery/";
    const std::string bms_path = "/sys/class/power_supply/bms/";

    std::string final_path = fs::exists(battery_path) ? battery_path : (fs::exists(bms_path) ? bms_path : "");
    if (final_path.empty()) {
        level = -1; temp = 0.0f; power = 0.0f; charging = false;
        return;
    }

    level = read_long_from_file(final_path + "capacity").value_or(-1);
    
    auto temp_raw = read_long_from_file(final_path + "temp");
    if (temp_raw.has_value()) {
        temp = static_cast<float>(*temp_raw) / 10.0f;
    } else {
        temp = 0.0f;
    }

    auto current_now_ua = read_long_from_file(final_path + "current_now");
    auto voltage_now_uv = read_long_from_file(final_path + "voltage_now");
    
    if (current_now_ua.has_value() && voltage_now_uv.has_value()) {
        double current_a = static_cast<double>(*current_now_ua) / 1000.0; // uA to A
        double voltage_v = static_cast<double>(*voltage_now_uv) / 1000000.0; // uV to V
        power = static_cast<float>(std::abs(current_a * voltage_v));
    } else {
        power = 0.0f;
    }

    std::ifstream status_file(final_path + "status");
    std::string status;
    if(status_file >> status) {
        charging = (status == "Charging" || status == "Full");
    } else {
        charging = false;
    }
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

// [核心修复] 采用新的、更严格的音频状态检测逻辑
void SystemMonitor::update_audio_state() {
    struct PlayerStates {
        int started_count = 0;
        int total_count = 0;
    };
    std::map<int, PlayerStates> uid_player_states;
    
    std::array<char, 2048> buffer;

    FILE* pipe = popen("dumpsys audio", "r");
    if (!pipe) {
        LOGW("popen for 'dumpsys audio' failed: %s", strerror(errno));
        return;
    }

    bool in_players_section = false;
    while (fgets(buffer.data(), buffer.size(), pipe) != nullptr) {
        std::string line(buffer.data());

        // 定位到 "players:" 部分的开始
        if (line.rfind("  players:", 0) == 0) {
            in_players_section = true;
            continue;
        }
        
        if (in_players_section) {
            // "players:" 部分结束的标志
            if (line.find("  ducked players piids:") == 0 || line.find("  faded out players piids:") == 0) {
                in_players_section = false;
                break; // 已完成players部分的解析，可以提前退出循环
            }
            
            // 解析每一行播放器信息
            if (line.find("AudioPlaybackConfiguration") != std::string::npos) {
                try {
                    int uid = -1;
                    std::string state_str;

                    // 提取 u/pid:
                    size_t upid_pos = line.find("u/pid:");
                    if (upid_pos != std::string::npos) {
                        std::stringstream ss(line.substr(upid_pos + 6));
                        ss >> uid;
                    }
                    if (uid < 10000) continue; // 只关心应用UID

                    // 提取 state:
                    size_t state_pos = line.find(" state:");
                    if (state_pos != std::string::npos) {
                        std::stringstream ss(line.substr(state_pos + 7));
                        ss >> state_str;
                    }

                    // 更新状态统计
                    uid_player_states[uid].total_count++;
                    if (state_str == "started") {
                        uid_player_states[uid].started_count++;
                    }
                } catch (const std::exception& e) {
                    // 忽略解析错误
                }
            }
        }
    }
    pclose(pipe);

    // [核心修复] 根据新的、严格的规则生成最终的活跃UID列表
    std::set<int> active_uids;
    for (const auto& [uid, states] : uid_player_states) {
        // 只有当该UID存在播放器，并且所有播放器都是 'started' 状态时，才认为其活跃
        if (states.total_count > 0 && states.started_count == states.total_count) {
            active_uids.insert(uid);
        }
    }
    
    {
        std::lock_guard<std::mutex> lock(audio_uids_mutex_);
        if (uids_playing_audio_ != active_uids) {
            std::stringstream ss;
            for(int uid : active_uids) { ss << uid << " "; }
            LOGI("Active audio UIDs changed (strict policy). Old count: %zu, New count: %zu. Active UIDs: [ %s]", 
                 uids_playing_audio_.size(), active_uids.size(), ss.str().c_str());
            uids_playing_audio_ = active_uids;
        }
    }
}

bool SystemMonitor::is_uid_playing_audio(int uid) {
    std::lock_guard<std::mutex> lock(audio_uids_mutex_);
    return uids_playing_audio_.count(uid) > 0;
}

void SystemMonitor::start_network_snapshot_thread() {
    if (network_monitoring_active_) return;
    network_monitoring_active_ = true;
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

        auto current_snapshot = read_current_traffic();
        auto current_time = std::chrono::steady_clock::now();
        
        std::map<int, TrafficStats> last_snapshot;
        std::chrono::steady_clock::time_point last_time;
        {
            std::lock_guard<std::mutex> lock(traffic_mutex_);
            last_snapshot = last_traffic_snapshot_;
            last_time = last_snapshot_time_;
        }

        double time_delta_sec = std::chrono::duration_cast<std::chrono::duration<double>>(current_time - last_time).count();
        if (time_delta_sec < 0.1) continue;
        
        {
            std::lock_guard<std::mutex> lock(speed_mutex_);
            const double DECAY_FACTOR = 0.5;
            for (auto& [uid, speed] : uid_network_speed_) {
                speed.download_kbps *= DECAY_FACTOR;
                speed.upload_kbps *= DECAY_FACTOR;
                if (speed.download_kbps < 0.1) speed.download_kbps = 0.0;
                if (speed.upload_kbps < 0.1) speed.upload_kbps = 0.0;
            }

            for (const auto& [uid, current_stats] : current_snapshot) {
                auto last_it = last_snapshot.find(uid);
                if (last_it != last_snapshot.end()) {
                    long long rx_delta = (current_stats.rx_bytes > last_it->second.rx_bytes) ? (current_stats.rx_bytes - last_it->second.rx_bytes) : 0;
                    long long tx_delta = (current_stats.tx_bytes > last_it->second.tx_bytes) ? (current_stats.tx_bytes - last_it->second.tx_bytes) : 0;
                    
                    if (rx_delta > 0 || tx_delta > 0) {
                        uid_network_speed_[uid] = {
                            .download_kbps = (static_cast<double>(rx_delta) / 1024.0) / time_delta_sec,
                            .upload_kbps = (static_cast<double>(tx_delta) / 1024.0) / time_delta_sec
                        };
                    }
                }
            }
        }

        {
            std::lock_guard<std::mutex> lock(traffic_mutex_);
            last_traffic_snapshot_ = current_snapshot;
            last_snapshot_time_ = current_time;
        }
    }
    LOGI("Network snapshot thread stopped.");
}

NetworkSpeed SystemMonitor::get_cached_network_speed(int uid) {
    std::lock_guard<std::mutex> lock(speed_mutex_);
    auto it = uid_network_speed_.find(uid);
    if (it != uid_network_speed_.end()) {
        return it->second;
    }
    return NetworkSpeed();
}

std::map<int, TrafficStats> SystemMonitor::read_current_traffic() {
    std::map<int, TrafficStats> snapshot;
    const std::string qtaguid_path = "/proc/net/xt_qtaguid/stats";

    std::ifstream qtaguid_file(qtaguid_path);
    if (qtaguid_file.is_open()) {
        std::string line;
        std::getline(qtaguid_file, line);
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
        if (!snapshot.empty()) {
            return snapshot;
        }
    }
    
    std::string result = exec_shell_pipe("dumpsys netstats");
    std::stringstream ss(result);
    std::string line;
    enum class ParseState { searching, in_mTun, in_mStatsFactory };
    ParseState state = ParseState::searching;

    while (std::getline(ss, line)) {
        if (state == ParseState::searching) {
            if (line.find("mTunAnd464xlatAdjustedStats ") != std::string::npos) {
                state = ParseState::in_mTun;
                continue;
            } else if (line.find("mStatsFactory:") != std::string::npos) {
                state = ParseState::in_mStatsFactory;
                continue;
            }
        }

        if (state == ParseState::in_mStatsFactory || state == ParseState::in_mTun) {
            if (line.find(" uid=") != std::string::npos && line.find(" rxBytes=") != std::string::npos) {
                try {
                    int uid = -1;
                    long long rx = -1, tx = -1;
                    std::string part;
                    std::stringstream line_ss(line);
                    
                    while (line_ss >> part && part.find("uid=") == std::string::npos);

                    if (part.find("uid=") == 0) {
                        uid = std::stoi(part.substr(4));
                        
                        while (line_ss >> part && part.find("rxBytes=") == std::string::npos);
                        if(part.find("rxBytes=") == 0) rx = std::stoll(part.substr(8));

                        while (line_ss >> part && part.find("txBytes=") == std::string::npos);
                        if(part.find("txBytes=") == 0) tx = std::stoll(part.substr(8));

                        if (uid >= 10000 && rx != -1 && tx != -1) {
                            snapshot[uid].rx_bytes += rx;
                            snapshot[uid].tx_bytes += tx;
                        }
                    }
                } catch (const std::exception& e) {
                }
            }
        }
    }
    
    if (snapshot.empty()) {
        LOGW("Both /proc and dumpsys netstats parsing failed to get any traffic data.");
    }

    return snapshot;
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

void SystemMonitor::update_location_state() {
    std::set<int> active_uids;
    std::array<char, 2048> buffer;

    FILE* pipe = popen("dumpsys location", "r");
    if (!pipe) {
        LOGW("popen for 'dumpsys location' failed: %s", strerror(errno));
        return;
    }

    std::string current_provider_line;
    while (fgets(buffer.data(), buffer.size(), pipe) != nullptr) {
        std::string line(buffer.data());
        
        if (line.rfind("LocationProvider[", 0) == 0) {
            current_provider_line = line;
            continue;
        }

        if (current_provider_line.find("PASSIVE") != std::string::npos) {
            continue;
        }
        
        if (line.find("Request[") != std::string::npos) {
            if (line.find("PASSIVE") != std::string::npos) {
                continue;
            }

            try {
                size_t pkg_start = line.find("package=");
                if (pkg_start == std::string::npos) continue;
                pkg_start += 8;
                size_t pkg_end = line.find(" ", pkg_start);
                if (pkg_end == std::string::npos) continue;
                std::string pkg_name = line.substr(pkg_start, pkg_end - pkg_start);
                
                int pid = get_pid_from_pkg(pkg_name);
                if (pid != -1) {
                    int uid = get_uid_from_pid(pid);
                    if (uid >= 10000) {
                        active_uids.insert(uid);
                    }
                }

            } catch (const std::exception& e) {
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

int SystemMonitor::get_pid_from_pkg(const std::string& pkg_name) {
    for (const auto& entry : fs::directory_iterator("/proc")) {
        if (!entry.is_directory()) continue;
        try {
            int pid = std::stoi(entry.path().filename().string());
            char path_buffer[256];
            snprintf(path_buffer, sizeof(path_buffer), "/proc/%d/cmdline", pid);
            std::ifstream cmdline_file(path_buffer);
            if (cmdline_file.is_open()) {
                std::string cmdline;
                std::getline(cmdline_file, cmdline, '\0');
                if (cmdline.rfind(pkg_name, 0) == 0) {
                    return pid;
                }
            }
        } catch (...) { continue; }
    }
    return -1;
}

bool SystemMonitor::is_uid_using_location(int uid) {
    std::lock_guard<std::mutex> lock(location_uids_mutex_);
    return uids_using_location_.count(uid) > 0;
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
                name.erase(name.find_last_not_of(" \t\n") + 1);
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

void SystemMonitor::update_cpu_usage(float& usage) {
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
            usage = std::max(0.0f, std::min(100.0f, cpu_usage));
        } else {
            usage = 0.0f;
        }
        prev_total_cpu_times_ = current_times;
    }
}

void SystemMonitor::update_mem_info(long& total, long& available, long& swap_total, long& swap_free) {
    std::ifstream meminfo_file("/proc/meminfo");
    if (!meminfo_file.is_open()) return;
    std::string line;
    while (std::getline(meminfo_file, line)) {
        std::string key;
        long value;
        std::stringstream ss(line);
        ss >> key >> value;
        if (key == "MemTotal:") total = value;
        else if (key == "MemAvailable:") available = value;
        else if (key == "SwapTotal:") swap_total = value;
        else if (key == "SwapFree:") swap_free = value;
    }
}