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
#include <numeric>

#define LOG_TAG "cerberusd_monitor_v32_multicore" // 版本号更新
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;

constexpr long long CACHE_DURATION_MS = 2000;

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
        // This can happen, not always an error worth logging loudly.
        return false;
    }
    return true;
}

bool SystemMonitor::ProcFileReader::read_contents(std::string& out_contents) {
    if (!open_fd()) return false;

    char buffer[4096];
    if (lseek(fd_, 0, SEEK_SET) != 0) {
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

// [核心新增] 实现 get_data_app_packages 函数
std::vector<std::string> SystemMonitor::get_data_app_packages() {
    std::set<std::string> packages; // 使用set自动去重
    const std::string data_app_path = "/data/app";

    if (!fs::exists(data_app_path) || !fs::is_directory(data_app_path)) {
        LOGW("Path /data/app does not exist or is not a directory.");
        return {};
    }

    try {
        for (const auto& top_level_entry : fs::directory_iterator(data_app_path)) {
            if (!top_level_entry.is_directory()) continue;
            
            // top_level_entry.path() 是 /data/app/~~...
            for (const auto& pkg_level_entry : fs::directory_iterator(top_level_entry.path())) {
                if (!pkg_level_entry.is_directory()) continue;
                
                // pkg_level_entry.path().filename() 是 com.package.name-XXXX==
                std::string dirname = pkg_level_entry.path().filename().string();
                
                // 从目录名中提取包名 (从开头到第一个'-')
                size_t dash_pos = dirname.find('-');
                if (dash_pos != std::string::npos) {
                    std::string pkg_name = dirname.substr(0, dash_pos);
                    // 基础校验，包名必须包含'.'
                    if (pkg_name.find('.') != std::string::npos) {
                        packages.insert(pkg_name);
                    }
                }
            }
        }
    } catch (const fs::filesystem_error& e) {
        LOGE("Error iterating /data/app: %s", e.what());
    }

    LOGI("Scanned /data/app and found %zu unique packages.", packages.size());
    return {packages.begin(), packages.end()};
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

    MetricsRecord dummy_record;
    update_cpu_usage(dummy_record); // Initial call to populate prev times
    
    last_screen_state_check_time_ = {};
    last_visible_apps_check_time_ = {};
}

SystemMonitor::~SystemMonitor() {
    stop_top_app_monitor();
    stop_network_snapshot_thread();
}

std::optional<MetricsRecord> SystemMonitor::collect_current_metrics() {
    MetricsRecord record;
    record.timestamp_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    
    update_cpu_usage(record);
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

// [核心修改] update_cpu_usage现在计算总的和每个核心的使用率
void SystemMonitor::update_cpu_usage(MetricsRecord& record) {
    std::string stat_content;
    if (!proc_stat_reader_.read_contents(stat_content) || stat_content.empty()) return;

    std::stringstream ss(stat_content);
    std::string line;
    std::vector<TotalCpuTimes> current_per_core_times;

    // Process total CPU (first line)
    std::getline(ss, line);
    std::string cpu_label;
    TotalCpuTimes current_total_times;
    std::stringstream total_ss(line);
    total_ss >> cpu_label >> current_total_times.user >> current_total_times.nice >> current_total_times.system >> current_total_times.idle
             >> current_total_times.iowait >> current_total_times.irq >> current_total_times.softirq >> current_total_times.steal;
    
    if (cpu_label == "cpu") {
        long long prev_total = prev_total_cpu_times_.total();
        long long current_total = current_total_times.total();
        long long delta_total = current_total - prev_total;
        if (delta_total > 0) {
            long long delta_idle = current_total_times.idle_total() - prev_total_cpu_times_.idle_total();
            float cpu_usage = 100.0f * static_cast<float>(delta_total - delta_idle) / static_cast<float>(delta_total);
            record.total_cpu_usage_percent = std::max(0.0f, std::min(100.0f, cpu_usage));
        } else {
            record.total_cpu_usage_percent = 0.0f;
        }
        prev_total_cpu_times_ = current_total_times;
    }

    // Process per-core CPUs
    while (std::getline(ss, line)) {
        if (line.rfind("cpu", 0) != 0) break; // Stop if not a cpu line
        std::stringstream core_ss(line);
        TotalCpuTimes core_times;
        core_ss >> cpu_label >> core_times.user >> core_times.nice >> core_times.system >> core_times.idle
                >> core_times.iowait >> core_times.irq >> core_times.softirq >> core_times.steal;
        current_per_core_times.push_back(core_times);
    }

    if (prev_per_core_cpu_times_.empty()) {
        LOGI("First CPU poll, found %zu cores. Storing initial values.", current_per_core_times.size());
        prev_per_core_cpu_times_ = current_per_core_times;
        record.per_core_cpu_usage.assign(current_per_core_times.size(), 0.0f);
        return;
    }

    size_t num_cores = std::min(prev_per_core_cpu_times_.size(), current_per_core_times.size());
    record.per_core_cpu_usage.resize(num_cores);

    for (size_t i = 0; i < num_cores; ++i) {
        const auto& prev = prev_per_core_cpu_times_[i];
        const auto& curr = current_per_core_times[i];
        long long delta_total = curr.total() - prev.total();
        if (delta_total > 0) {
            long long delta_idle = curr.idle_total() - prev.idle_total();
            float usage = 100.0f * static_cast<float>(delta_total - delta_idle) / static_cast<float>(delta_total);
            record.per_core_cpu_usage[i] = std::max(0.0f, std::min(100.0f, usage));
        } else {
            record.per_core_cpu_usage[i] = 0.0f;
        }
    }

    prev_per_core_cpu_times_ = current_per_core_times;
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
    std::string cmdline = read_file_once("/proc/" + std::to_string(pid) + "/cmdline");
    if (!cmdline.empty()) {
        size_t null_pos = cmdline.find('\0');
        if(null_pos != std::string::npos) cmdline.resize(null_pos);
        if(!cmdline.empty()) return cmdline;
    }
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
    return "Unknown";
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
bool SystemMonitor::get_screen_state() {
    std::lock_guard<std::mutex> lock(screen_state_mutex_);
    auto now = std::chrono::steady_clock::now();
    auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(now - last_screen_state_check_time_).count();
    if (elapsed_ms < CACHE_DURATION_MS) {
        return cached_screen_on_state_;
    }
    LOGD("Screen state cache expired, executing dumpsys power...");
    last_screen_state_check_time_ = now;
    std::string result = exec_shell_pipe_efficient({"dumpsys", "power"});
    size_t pos = result.find("mWakefulness=");
    if(pos == std::string::npos) pos = result.find("mWakefulnessRaw=");
    if(pos != std::string::npos) {
        cached_screen_on_state_ = result.find("Awake", pos) != std::string::npos;
        return cached_screen_on_state_;
    }
    return cached_screen_on_state_;
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
std::set<AppInstanceKey> SystemMonitor::get_visible_app_keys() {
    std::lock_guard<std::mutex> lock(visible_apps_mutex_);
    auto now = std::chrono::steady_clock::now();
    auto elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(now - last_visible_apps_check_time_).count();
    if (elapsed_ms < CACHE_DURATION_MS) {
        return cached_visible_app_keys_;
    }
    LOGD("Visible apps cache expired, executing dumpsys activity activities...");
    last_visible_apps_check_time_ = now;
    std::set<AppInstanceKey> visible_keys;
    std::string dumpsys_output = exec_shell_pipe_efficient({"dumpsys", "activity", "activities"});
    if (dumpsys_output.empty()) {
        cached_visible_app_keys_ = visible_keys;
        return visible_keys;
    }
    std::stringstream ss(dumpsys_output);
    std::string line;
    std::vector<std::string> lines;
    while(std::getline(ss, line)) {
        lines.push_back(line);
    }
    size_t start_index = (lines.size() > 15) ? (lines.size() - 15) : 0;
    for (size_t i = start_index; i < lines.size(); ++i) {
        const auto& current_line = lines[i];
        if (current_line.find("VisibleActivityProcess:") != std::string::npos) {
            std::stringstream line_ss(current_line);
            std::string token;
            while (line_ss >> token) {
                size_t u_pos = token.find("/u");
                if (u_pos != std::string::npos) {
                    try {
                        std::string package_name = token.substr(0, u_pos);
                        std::string user_part = token.substr(u_pos + 2);
                        size_t a_pos = user_part.find('a');
                        if (a_pos != std::string::npos) {
                            user_part = user_part.substr(0, a_pos);
                        }
                        int user_id = std::stoi(user_part);
                        visible_keys.insert({package_name, user_id});
                    } catch (...) { /* ignore parse errors */ }
                }
            }
            break;
        }
    }
    cached_visible_app_keys_ = visible_keys;
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
void SystemMonitor::update_audio_state() {
    std::map<int, std::vector<int>> uid_session_states;
    std::unordered_set<std::string> ignored_usages = {"USAGE_ASSISTANCE_SONIFICATION", "USAGE_TOUCH_INTERACTION_RESPONSE"};
    std::string dumpsys_output = exec_shell_pipe_efficient({"dumpsys", "audio"});
    std::stringstream ss(dumpsys_output);
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
                bool is_ignored = false;
                for (const auto& usage : ignored_usages) {
                    if (line.find(usage) != std::string::npos) {
                        is_ignored = true;
                        break;
                    }
                }
                if (is_ignored) continue;
                int uid = -1;
                size_t upid_pos = line.find("u/pid:");
                if (upid_pos != std::string::npos) {
                    std::stringstream line_ss(line.substr(upid_pos + 6));
                    line_ss >> uid;
                }
                if (uid < 10000) continue;
                size_t state_pos = line.find(" state:");
                if (state_pos != std::string::npos) {
                    std::string state_str = line.substr(state_pos + 7);
                    if (state_str.rfind("started", 0) == 0) {
                        uid_session_states[uid].push_back(1);
                    } else if (state_str.rfind("paused", 0) == 0) {
                        uid_session_states[uid].push_back(0);
                    }
                }
            } catch (...) {}
        }
    }
    std::set<int> active_uids;
    for (const auto& [uid, states] : uid_session_states) {
        if (states.empty()) continue;
        int product = std::accumulate(states.begin(), states.end(), 1, std::multiplies<int>());
        if (product == 1) {
            active_uids.insert(uid);
        }
    }
    {
        std::lock_guard<std::mutex> lock(audio_uids_mutex_);
        if (uids_playing_audio_ != active_uids) {
            LOGI("Active audio UIDs changed. Old count: %zu, New count: %zu.", uids_playing_audio_.size(), active_uids.size());
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
                } catch (const std::exception& e) {}
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
            LOGI("Active location UIDs changed (gps provider policy). Old count: %zu, New count: %zu. Active UIDs: [ %s]", uids_using_location_.size(), active_uids.size(), log_ss.str().c_str());
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