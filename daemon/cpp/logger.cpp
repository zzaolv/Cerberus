// daemon/cpp/logger.cpp
#include "logger.h"
#include <fstream>
#include <filesystem>
#include <chrono>
#include <iomanip>
#include <ctime>
#include <algorithm>
#include <android/log.h>
#include <vector>
#include <regex>

#define LOG_TAG "cerberusd_logger_v6_rotation" // 版本号更新
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;

const int MAX_LOG_LINES_PER_FILE = 200;
const int MAX_LOG_FILES_PER_DAY = 3;
const int MAX_LOG_RETENTION_DAYS = 3;

// --- LogEntry ---
json LogEntry::to_json() const {
    json j = {
        {"timestamp", timestamp_ms},
        {"level", static_cast<int>(level)},
        {"category", category},
        {"message", message},
    };
    if (!package_name.empty()) j["package_name"] = package_name;
    if (user_id != -1) j["user_id"] = user_id;
    return j;
}

// --- Logger Singleton ---
std::shared_ptr<Logger> Logger::instance_ = nullptr;
std::mutex Logger::instance_mutex_;

std::shared_ptr<Logger> Logger::get_instance(const std::string& log_dir_path) {
    std::lock_guard<std::mutex> lock(instance_mutex_);
    if (!instance_) {
        struct make_shared_enabler : public Logger {
            make_shared_enabler(const std::string& path) : Logger(path) {}
        };
        instance_ = std::make_shared<make_shared_enabler>(log_dir_path);
    }
    return instance_;
}


// --- Logger Implementation ---
Logger::Logger(const std::string& log_dir_path)
    : log_dir_path_(log_dir_path), is_running_(true) {
    if (!fs::exists(log_dir_path_)) {
        fs::create_directories(log_dir_path_);
    }
    manage_log_files(); // 初始化时就管理一下
    writer_thread_ = std::thread(&Logger::writer_thread_func, this);
}

Logger::~Logger() {
    stop();
}

void Logger::stop() {
    if (!is_running_.exchange(false)) return;
    cv_.notify_one();
    if (writer_thread_.joinable()) {
        writer_thread_.join();
    }
}

void Logger::log(LogLevel level, const std::string& category, const std::string& message, const std::string& package_name, int user_id) {
    long long timestamp = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()
    ).count();
    LogEntry entry{timestamp, level, category, message, package_name, user_id};

    {
        std::lock_guard<std::mutex> lock(queue_mutex_);
        log_queue_.push_back(entry);
    }
    cv_.notify_one();
}

// [新] 批量日志方法
void Logger::log_batch(const std::vector<LogEntry>& entries) {
    if (entries.empty()) return;
    {
        std::lock_guard<std::mutex> lock(queue_mutex_);
        for(const auto& entry : entries) {
            log_queue_.push_back(entry);
        }
    }
    cv_.notify_one();
}

std::vector<std::string> Logger::get_log_files() const {
    std::vector<std::string> files;
    try {
        for (const auto& entry : fs::directory_iterator(log_dir_path_)) {
            if (entry.is_regular_file()) {
                std::string filename = entry.path().filename().string();
                if (filename.rfind("fct_", 0) == 0 && filename.rfind(".log") == filename.length() - 4) {
                    files.push_back(filename);
                }
            }
        }
    } catch (const fs::filesystem_error& e) {
        LOGE("Error listing log files: %s", e.what());
    }
    // 按文件名降序排序 (fct_2024-01-02_1 > fct_2024-01-01_3)
    std::sort(files.rbegin(), files.rend());
    return files;
}

// [修改] 按文件名正向读取日志
std::vector<LogEntry> Logger::get_logs_from_file(const std::string& filename, int limit, long long before_timestamp_ms) const {
    std::vector<LogEntry> results;
    fs::path file_path = fs::path(log_dir_path_) / filename;

    if (!fs::exists(file_path)) return results;

    std::ifstream log_file(file_path);
    if (!log_file.is_open()) return results;

    std::vector<std::string> lines;
    std::string line;
    while (std::getline(log_file, line)) {
        lines.push_back(line);
    }

    // 从后往前遍历行，以获取最新的日志
    for (auto it = lines.rbegin(); it != lines.rend(); ++it) {
        if (results.size() >= limit) break;

        try {
            json j = json::parse(*it);
            long long timestamp = j.value("ts", 0LL);

            if (timestamp >= before_timestamp_ms) {
                continue; // 跳过比 before_timestamp_ms 更新或相等的日志
            }
            
            LogEntry entry{
                .timestamp_ms = timestamp,
                .level = static_cast<LogLevel>(j.value("lvl", 0)),
                .category = j.value("cat", ""),
                .message = j.value("msg", ""),
                .package_name = j.value("pkg", ""),
                .user_id = j.value("uid", -1)
            };
            results.push_back(entry);
        } catch (const json::exception& e) { /* ignore malformed lines */ }
    }
    // 注意：这里返回的日志是时间降序的，前端可以直接使用
    return results;
}

void Logger::manage_log_files() {
    auto files = get_log_files(); // 已经是降序排序
    std::map<std::string, std::vector<std::string>> files_by_day;
    
    // 分组
    for (const auto& f : files) {
        try {
            std::string date_str = f.substr(4, 10);
            files_by_day[date_str].push_back(f);
        } catch(...) {}
    }
    
    // 清理每天多余的文件
    for (auto& [day, day_files] : files_by_day) {
        // 已经是降序的了，所以 0, 1, 2 是最新的
        if (day_files.size() > MAX_LOG_FILES_PER_DAY) {
            for (size_t i = MAX_LOG_FILES_PER_DAY; i < day_files.size(); ++i) {
                fs::remove(fs::path(log_dir_path_) / day_files[i]);
                 LOGD("Cleaned up excess log file: %s", day_files[i].c_str());
            }
        }
    }
    
    // 清理过期的天数
    if (files_by_day.size() > MAX_LOG_RETENTION_DAYS) {
        auto it = files_by_day.begin();
        std::advance(it, files_by_day.size() - MAX_LOG_RETENTION_DAYS);
        for(auto temp_it = files_by_day.begin(); temp_it != it; ++temp_it) {
            for (const auto& f : temp_it->second) {
                fs::remove(fs::path(log_dir_path_) / f);
                 LOGD("Cleaned up outdated log file: %s", f.c_str());
            }
        }
    }

    // 更新当前日志文件
    auto latest_files = get_log_files();
    if (latest_files.empty()) {
        current_log_file_path_ = "";
        current_log_line_count_ = 0;
    } else {
        current_log_file_path_ = fs::path(log_dir_path_) / latest_files[0];
        std::ifstream ifs(current_log_file_path_);
        current_log_line_count_ = std::count(std::istreambuf_iterator<char>(ifs), std::istreambuf_iterator<char>(), '\n');
    }
}


void Logger::rotate_log_file_if_needed(size_t new_entries_count) {
    time_t now = time(nullptr);
    tm ltm = {};
    localtime_r(&now, &ltm);
    char date_buf[16];
    strftime(date_buf, sizeof(date_buf), "%Y-%m-%d", &ltm);
    std::string current_date_str(date_buf);

    bool needs_new_file = false;
    if (current_log_file_path_.empty() || 
        current_log_file_path_.find(current_date_str) == std::string::npos ||
        (current_log_line_count_ + new_entries_count > MAX_LOG_LINES_PER_FILE)) {
        needs_new_file = true;
    }

    if (needs_new_file) {
        int next_index = 1;
        auto files = get_log_files();
        if (!files.empty() && files[0].find(current_date_str) != std::string::npos) {
            try {
                // fct_2024-01-01_1.log -> 1
                std::string last_file = files[0];
                size_t underscore_pos = last_file.rfind('_');
                size_t dot_pos = last_file.rfind('.');
                int last_index = std::stoi(last_file.substr(underscore_pos + 1, dot_pos - underscore_pos - 1));
                next_index = last_index + 1;
            } catch (...) {}
        }
        std::string new_filename = "fct_" + current_date_str + "_" + std::to_string(next_index) + ".log";
        current_log_file_path_ = fs::path(log_dir_path_) / new_filename;
        current_log_line_count_ = 0;
        cleanup_old_files();
    }
}

void Logger::cleanup_old_files() {
     // 这部分逻辑现在移到 manage_log_files 中，定期执行
}


void Logger::writer_thread_func() {
    while (is_running_) {
        std::unique_lock<std::mutex> lock(queue_mutex_);
        cv_.wait(lock, [this]{ return !log_queue_.empty() || !is_running_; });

        if (!is_running_ && log_queue_.empty()) break;

        std::deque<LogEntry> temp_queue;
        temp_queue.swap(log_queue_);
        lock.unlock();

        if (temp_queue.empty()) continue;
        
        rotate_log_file_if_needed(temp_queue.size());

        std::ofstream log_file(current_log_file_path_, std::ios_base::app);
        if (!log_file.is_open()) {
            LOGE("Failed to open log file for writing: %s", current_log_file_path_.c_str());
            continue;
        }

        for (const auto& entry : temp_queue) {
            json file_json = {
                {"ts", entry.timestamp_ms},
                {"lvl", static_cast<int>(entry.level)},
                {"cat", entry.category},
                {"msg", entry.message}
            };
            if (!entry.package_name.empty()) file_json["pkg"] = entry.package_name;
            if (entry.user_id != -1) file_json["uid"] = entry.user_id;
            log_file << file_json.dump() << std::endl;
        }
        current_log_line_count_ += temp_queue.size();
    }
}