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
#include <sys/wait.h>
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
#include <unordered_set>

#define LOG_TAG "cerberusd_monitor_v28_audio_fix" // 版本号更新
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;

SystemMonitor::ProcFileReader::ProcFileReader(std::string path) : path_(std::move(path)) {}

SystemMonitor::ProcFileReader::~ProcFileReader() {
    if (fd_ != -1) {
        close(fd_);
    }
}

bool SystemMonitor::ProcFileReader::open_fd() {
    if (fd_ != -1) return true;
    fd_ = open(path_.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd_ == -1) {
        LOGE("Failed to open persistent fd for %s: %s", path_.c_str(), strerror(errno));
        return false;
    }
    return true;
}

bool SystemMonitor::ProcFileReader::read_contents(std::string& out_contents) {
    if (!open_fd()) return false;

    char buffer[4096];
    if (lseek(fd_, 0, SEEK_SET) != 0) {
        LOGE("lseek failed for %s: %s. Reopening fd.", path_.c_str(), strerror(errno));
        close(fd_);
        fd_ = -1;
        if (!open_fd()) return false;
    }
    
    ssize_t bytes_read = read(fd_, buffer, sizeof(buffer) - 1);
    if (bytes_read > 0) {
        buffer[bytes_read] = '\0';
        out_contents = buffer;
        return true;
    }
    return false;
}

std::string SystemMonitor::read_file_once(const std::string& path, size_t max_size) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd == -1) return "";

    std::string content;
    content.resize(max_size);
    ssize_t bytes_read = read(fd, content.data(), max_size - 1);
    close(fd);

    if (bytes_read > 0) {
        content.resize(bytes_read);
        return content;
    }
    return "";
}

static std::optional<long> read_long_from_file_str(const std::string& content) {
    if (content.empty()) return std::nullopt;
    try {
        return std::stol(content);
    } catch (...) {
        return std::nullopt;
    }
}

std::string SystemMonitor::exec_shell_pipe_efficient(const std::vector<std::string>& args) {
    if (args.empty()) return "";

    int pipe_fd[2];
    if (pipe2(pipe_fd, O_CLOEXEC) == -1) {
        LOGE("pipe2 failed: %s", strerror(errno));
        return "";
    }

    pid_t pid = fork();
    if (pid == -1) {
        LOGE("fork failed: %s", strerror(errno));
        close(pipe_fd[0]);
        close(pipe_fd[1]);
        return "";
    }

    if (pid == 0) { 
        close(pipe_fd[0]); 
        dup2(pipe_fd[1], STDOUT_FILENO);
        close(pipe_fd[1]);
        
        std::vector<char*> c_args;
        for (const auto& arg : args) {
            c_args.push_back(const_cast<char*>(arg.c_str()));
        }
        c_args.push_back(nullptr);

        execvp(c_args[0], c_args.data());
        exit(127);
    }

    close(pipe_fd[1]);
    std::string result;
    result.reserve(65536);
    char buffer[4096];
    ssize_t count;

    while ((count = read(pipe_fd[0], buffer, sizeof(buffer))) > 0) {
        result.append(buffer, count);
    }
    close(pipe_fd[0]);

    int status;
    waitpid(pid, &status, 0);

    return result;
}


int get_uid_from_pid(int pid) {
    char path_buffer[64];
    snprintf(path_buffer, sizeof(path_buffer), "/proc/%d", pid);
    struct stat st;
    if (stat(path_buffer, &st) != 0) return -1;
    return st.st_uid;
}


SystemMonitor::SystemMonitor() : proc_stat_reader_("/proc/stat") {
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

std::set<AppInstanceKey> SystemMonitor::get_visible_app_keys() {
    std::set<AppInstanceKey> visible_keys;
    std::string result = exec_shell_pipe_efficient({"dumpsys", "activity", "activities"});
    std::stringstream ss(result);
    std::string line;

    auto parse_and_insert = [&](const std::string& line_to_parse) {
        std::stringstream line_ss(line_to_parse);
        std::string token;
        int user_id = -1;
        std::string package_name;

        while (line_ss >> token) {
            if (token.length() > 1 && token[0] == 'u' && std::all_of(token.begin() + 1, token.end(), ::isdigit)) {
                try {
                    user_id = std::stoi(token.substr(1));
                    if (line_ss >> token) {
                        size_t slash_pos = token.find('/');
                        if (slash_pos != std::string::npos) {
                            package_name = token.substr(0, slash_pos);
                        }
                        break;
                    }
                } catch (...) {
                    user_id = -1;
                }
            }
        }
        if (user_id != -1 && !package_name.empty()) {
            visible_keys.insert({package_name, user_id});
        }
    };
    
    while (std::getline(ss, line)) {
        if (line.find("ResumedActivity:") != std::string::npos) {
            parse_and_insert(line);
        } else if (line.find("VisibleActivityProcess:") != std::string::npos) {
            std::string process_line = line.substr(line.find('[') + 1);
            process_line = process_line.substr(0, process_line.find(']'));
            std::stringstream pss(process_line);
            std::string proc_record;
            while(std::getline(pss, proc_record, ',')) {
                 parse_and_insert(proc_record);
            }
        }
    }
    return visible_keys;
}

std::map<int, ProcessInfo> SystemMonitor::get_full_process_tree() {
    std::map<int, ProcessInfo> process_map;
    constexpr int PER_USER_RANGE = 100000;

    for (const auto& entry : fs::directory_iterator("/proc")) {
        if (!entry.is_directory()) continue;
        
        int pid = 0;
        try {
            pid = std::stoi(entry.path().filename().string());
        } catch (...) { continue; }

        char path_buffer[256];
        snprintf(path_buffer, sizeof(path_buffer), "/proc/%d", pid);
        struct stat st;
        if (stat(path_buffer, &st) != 0 || st.st_uid < 10000) continue;

        ProcessInfo info;
        info.pid = pid;
        info.uid = st.st_uid;
        info.user_id = info.uid / PER_USER_RANGE;
        
        std::string stat_content = read_file_once(std::string("/proc/") + std::to_string(pid) + "/stat");
        if (!stat_content.empty()) {
            std::stringstream stat_ss(stat_content);
            std::string s;
            stat_ss >> s >> s >> s >> info.ppid;
        }

        info.oom_score_adj = read_long_from_file_str(read_file_once(std::string("/proc/") + std::to_string(pid) + "/oom_score_adj")).value_or(1001);
        
        info.pkg_name = read_file_once(std::string("/proc/") + std::to_string(pid) + "/cmdline");
        size_t null_pos = info.pkg_name.find('\0');
        if(null_pos != std::string::npos) info.pkg_name.resize(null_pos);

        if (!info.pkg_name.empty() && info.pkg_name.find('.') != std::string::npos) {
           size_t colon_pos = info.pkg_name.find(':');
           if (colon_pos != std::string::npos) {
               info.pkg_name = info.pkg_name.substr(0, colon_pos);
           }
           process_map[pid] = info;
        }
    }
    return process_map;
}


long long SystemMonitor::get_total_cpu_jiffies_for_pids(const std::vector<int>& pids) {
    long long total_jiffies = 0;
    for (int pid : pids) {
        std::string stat_content = read_file_once(std::string("/proc/") + std::to_string(pid) + "/stat");
        if (!stat_content.empty()) {
            std::stringstream ss(stat_content);
            std::string value;
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
    std::string result = exec_shell_pipe_efficient({"dumpsys", "power"});
    size_t pos = result.find("mWakefulness=");
    if(pos == std::string::npos) pos = result.find("mWakefulnessRaw=");
    if(pos != std::string::npos) {
        return result.find("Awake", pos) != std::string::npos;
    }
    return false;
}

void SystemMonitor::get_battery_stats(int& level, float& temp, float& power, bool& charging) {
    const std::string battery_path = "/sys/class/power_supply/battery/";
    const std::string bms_path = "/sys/class/power_supply/bms/";

    std::string final_path = fs::exists(battery_path) ? battery_path : (fs::exists(bms_path) ? bms_path : "");
    if (final_path.empty()) {
        level = -1; temp = 0.0f; power = 0.0f; charging = false;
        return;
    }
    
    level = read_long_from_file_str(read_file_once(final_path + "capacity")).value_or(-1);
    
    auto temp_raw = read_long_from_file_str(read_file_once(final_path + "temp"));
    if (temp_raw.has_value()) {
        temp = static_cast<float>(*temp_raw) / 10.0f;
    } else {
        temp = 0.0f;
    }

    auto current_now_ua = read_long_from_file_str(read_file_once(final_path + "current_now"));
    auto voltage_now_uv = read_long_from_file_str(read_file_once(final_path + "voltage_now"));
    
    if (current_now_ua.has_value() && voltage_now_uv.has_value()) {
        double current_a = static_cast<double>(*current_now_ua) / 1000.0;
        double voltage_v = static_cast<double>(*voltage_now_uv) / 1000000.0;
        power = static_cast<float>(std::abs(current_a * voltage_v));
    } else {
        power = 0.0f;
    }

    std::string status = read_file_once(final_path + "status");
    if(!status.empty()) {
        status.erase(status.find_last_not_of(" \n\r\t")+1);
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
    std::string content = read_file_once(top_app_tasks_path_);
    if(content.empty()) return pids;

    std::stringstream ss(content);
    int pid;
    while (ss >> pid) {
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

// [核心修复] 回归到更稳定可靠的、基于 'started' 状态的音频识别逻辑
void SystemMonitor::update_audio_state() {
    std::set<int> active_uids;
    std::unordered_set<std::string> ignored_usages = {"USAGE_ASSISTANCE_SONIFICATION", "USAGE_TOUCH_INTERACTION_RESPONSE"};

    std::string result = exec_shell_pipe_efficient({"dumpsys", "audio"});
    std::stringstream ss(result);
    std::string line;

    bool in_players_section = false;
    while (std::getline(ss, line)) {
        if (!in_players_section) {
            if (line.find("players:") != std::string::npos) {
                in_players_section = true;
            }
            continue;
        }
        
        if (line.find("ducked players piids:") != std::string::npos) {
            break;
        }
        
        if (line.find("AudioPlaybackConfiguration") != std::string::npos) {
            try {
                // 1. 首先检查并过滤掉系统提示音等无关播放器
                bool is_ignored = false;
                for (const auto& usage : ignored_usages) {
                    if (line.find(usage) != std::string::npos) {
                        is_ignored = true;
                        break;
                    }
                }
                if (is_ignored) continue;

                // 2. 检查状态是否为 'started'
                size_t state_pos = line.find(" state:");
                if (state_pos != std::string::npos) {
                    // 提高解析效率，只检查 'started' 关键字
                    if (line.substr(state_pos + 7, 7) != "started") {
                        continue;
                    }
                } else {
                    continue; // 没有 state 字段，忽略
                }

                // 3. 只有当状态确定为 'started' 且非忽略类型时，才解析 UID
                int uid = -1;
                size_t upid_pos = line.find("u/pid:");
                if (upid_pos != std::string::npos) {
                    std::stringstream line_ss(line.substr(upid_pos + 6));
                    line_ss >> uid;
                }
                
                if (uid >= 10000) {
                    active_uids.insert(uid);
                }
            } catch (const std::exception& e) {}
        }
    }
    
    {
        std::lock_guard<std::mutex> lock(audio_uids_mutex_);
        if (uids_playing_audio_ != active_uids) {
            std::stringstream log_ss;
            for(int uid : active_uids) { log_ss << uid << " "; }
            LOGI("Active audio UIDs changed (started policy). Old count: %zu, New count: %zu. Active UIDs: [ %s]", 
                 uids_playing_audio_.size(), active_uids.size(), log_ss.str().c_str());
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
    
    std::string qtaguid_content = read_file_once(qtaguid_path, 256 * 1024);
    if (!qtaguid_content.empty()) {
        std::stringstream ss(qtaguid_content);
        std::string line;
        std::getline(ss, line);
        while (std::getline(ss, line)) {
            std::stringstream line_ss(line);
            std::string idx, iface, acct_tag, set;
            int uid, cnt_set, protocol;
            long long rx_bytes, rx_packets, tx_bytes, tx_packets;
            line_ss >> idx >> iface >> acct_tag >> uid >> cnt_set >> set >> protocol >> rx_bytes >> rx_packets >> tx_bytes >> tx_packets;
            if (uid >= 10000) {
                snapshot[uid].rx_bytes += rx_bytes;
                snapshot[uid].tx_bytes += tx_bytes;
            }
        }
        if (!snapshot.empty()) {
            return snapshot;
        }
    }
    
    std::string result = exec_shell_pipe_efficient({"dumpsys", "netstats"});
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
        std::string result = exec_shell_pipe_efficient({"settings", "get", "secure", "default_input_method"});

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
    std::string result = exec_shell_pipe_efficient({"dumpsys", "location"});
    std::stringstream ss(result);
    std::string line;

    bool in_gps_provider_section = false;
    while (std::getline(ss, line)) {
        if (!in_gps_provider_section) {
            if (line.find("gps provider:") != std::string::npos) {
                in_gps_provider_section = true;
                if (line.find("[OFF]") != std::string::npos) {
                    in_gps_provider_section = false; 
                }
            }
            continue;
        }

        if (line.find("user 0:") != std::string::npos) {
            in_gps_provider_section = false;
            continue;
        }
        
        size_t ws_pos = line.find("WorkSource{");
        if (ws_pos != std::string::npos) {
            try {
                std::string ws_content = line.substr(ws_pos + 11);
                std::stringstream ws_ss(ws_content);
                int uid = -1;
                ws_ss >> uid;

                if (uid >= 10000) {
                    active_uids.insert(uid);
                }
            } catch (const std::exception& e) {
                 LOGW("Failed to parse WorkSource line: %s", line.c_str());
            }
        }
    }

    {
        std::lock_guard<std::mutex> lock(location_uids_mutex_);
        if (uids_using_location_ != active_uids) {
            std::stringstream log_ss;
            for(int uid : active_uids) { log_ss << uid << " "; }
            LOGI("Active location UIDs changed (gps provider policy). Old count: %zu, New count: %zu. Active UIDs: [ %s]", 
                uids_using_location_.size(), active_uids.size(), log_ss.str().c_str());
            uids_using_location_ = active_uids;
        }
    }
}


bool SystemMonitor::is_uid_using_location(int uid) {
    std::lock_guard<std::mutex> lock(location_uids_mutex_);
    return uids_using_location_.count(uid) > 0;
}

int SystemMonitor::get_pid_from_pkg(const std::string& pkg_name) {
    for (const auto& entry : fs::directory_iterator("/proc")) {
        if (!entry.is_directory()) continue;
        try {
            int pid = std::stoi(entry.path().filename().string());
            std::string cmdline = read_file_once(std::string("/proc/") + std::to_string(pid) + "/cmdline");
            if (cmdline.rfind(pkg_name, 0) == 0) {
                return pid;
            }
        } catch (...) { continue; }
    }
    return -1;
}

void SystemMonitor::update_app_stats(const std::vector<int>& pids, long& total_mem_kb, long& total_swap_kb, float& total_cpu_percent) {
    total_mem_kb = 0;
    total_swap_kb = 0;
    total_cpu_percent = 0.0f;
    if (pids.empty()) return;

    for (int pid : pids) {
        std::string proc_path = "/proc/" + std::to_string(pid);
        if (!fs::exists(proc_path)) continue;
        
        std::string rollup_content = read_file_once(proc_path + "/smaps_rollup");
        if (!rollup_content.empty()) {
            std::stringstream ss(rollup_content);
            std::string line;
            while (std::getline(ss, line)) {
                std::stringstream line_ss(line);
                std::string key;
                long value;
                line_ss >> key >> value;
                if (key == "Pss:") total_mem_kb += value;
                else if (key == "Swap:") total_swap_kb += value;
            }
        }
        
        std::string stat_content = read_file_once(proc_path + "/stat");
        if (!stat_content.empty()) {
            std::stringstream ss(stat_content);
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
    std::string status_content = read_file_once("/proc/" + std::to_string(pid) + "/status");
    if (!status_content.empty()) {
        std::stringstream ss(status_content);
        std::string line;
        while (std::getline(ss, line)) {
            if (line.rfind("Name:", 0) == 0) {
                std::string name = line.substr(line.find(":") + 1);
                name.erase(0, name.find_first_not_of(" \t"));
                name.erase(name.find_last_not_of(" \t\n") + 1);
                return name;
            }
        }
    }
    std::string cmdline = read_file_once("/proc/" + std::to_string(pid) + "/cmdline");
    if (!cmdline.empty()) {
        size_t null_pos = cmdline.find('\0');
        if(null_pos != std::string::npos) cmdline.resize(null_pos);
        return cmdline;
    }
    return "Unknown";
}

void SystemMonitor::update_cpu_usage(float& usage) {
    std::string stat_content;
    if(!proc_stat_reader_.read_contents(stat_content) || stat_content.empty()) return;

    std::string line = stat_content.substr(0, stat_content.find('\n'));
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
    std::string meminfo_content = read_file_once("/proc/meminfo");
    if (meminfo_content.empty()) return;

    std::stringstream ss(meminfo_content);
    std::string line;
    while (std::getline(ss, line)) {
        std::string key;
        long value;
        std::stringstream line_ss(line);
        line_ss >> key >> value;
        if (key == "MemTotal:") total = value;
        else if (key == "MemAvailable:") available = value;
        else if (key == "SwapTotal:") swap_total = value;
        else if (key == "SwapFree:") swap_free = value;
    }
}