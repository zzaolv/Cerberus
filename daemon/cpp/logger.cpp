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
#include <iterator>

#define LOG_TAG "cerberusd_logger_v8_robust_sort"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)   // [修正] 补全 LOGI 定义
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)   // [修正] 补全 LOGW 定义
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;

const int MAX_LOG_LINES_PER_FILE = 200;
const int MAX_LOG_FILES_PER_DAY = 3;
const int MAX_LOG_RETENTION_DAYS = 3;

// --- LogEntry (无变化) ---
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

// --- Singleton and Constructor/Destructor (无变化) ---
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
Logger::Logger(const std::string& log_dir_path)
    : log_dir_path_(log_dir_path), is_running_(true) {
    if (!fs::exists(log_dir_path_)) {
        fs::create_directories(log_dir_path_);
    }
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
                // [修正] 使用 C++17 兼容的 rfind 方法替代 ends_with
                if (filename.rfind("fct_", 0) == 0 && (filename.length() >= 4 && filename.rfind(".log") == filename.length() - 4)) {
                    files.push_back(filename);
                }
            }
        }
    } catch (const fs::filesystem_error& e) {
        LOGE("Error listing log files: %s", e.what());
    }
    std::sort(files.rbegin(), files.rend());
    return files;
}

std::vector<LogEntry> Logger::get_logs_from_file(const std::string& filename, int limit,
                                                 std::optional<long long> before_timestamp_ms,
                                                 std::optional<long long> since_timestamp_ms) const {
    std::vector<LogEntry> results;
    fs::path file_path = fs::path(log_dir_path_) / filename;

    if (!fs::exists(file_path)) {
        LOGW("Log file not found: %s", filename.c_str());
    } else {
        std::ifstream log_file(file_path);
        if (log_file.is_open()) {
            std::vector<std::string> lines;
            std::string line;
            while (std::getline(log_file, line)) {
                lines.push_back(line);
            }

            for (auto it = lines.rbegin(); it != lines.rend(); ++it) {
                if (limit > 0 && results.size() >= limit && !since_timestamp_ms) break;
                try {
                    json j = json::parse(*it);
                    long long timestamp = j.value("ts", 0LL);
                    if (before_timestamp_ms.has_value() && timestamp >= before_timestamp_ms.value()) continue;
                    if (since_timestamp_ms.has_value() && timestamp <= since_timestamp_ms.value()) {
                        if (!before_timestamp_ms.has_value()) break;
                        else continue;
                    }
                    results.push_back({
                        .timestamp_ms = timestamp,
                        .level = static_cast<LogLevel>(j.value("lvl", 0)),
                        .category = j.value("cat", ""),
                        .message = j.value("msg", ""),
                        .package_name = j.value("pkg", ""),
                        .user_id = j.value("uid", -1)
                    });
                } catch (...) { /* ignore */ }
            }
        }
    }

    if (since_timestamp_ms.has_value()) {
        {
            std::lock_guard<std::mutex> lock(queue_mutex_);
            for(const auto& entry : log_queue_) {
                if (entry.timestamp_ms > since_timestamp_ms.value()) {
                    results.push_back(entry);
                }
            }
        }
        std::sort(results.begin(), results.end(), [](const auto& a, const auto& b) {
            return a.timestamp_ms < b.timestamp_ms;
        });
    }

    return results;
}

void Logger::manage_log_files() {
    auto files = get_log_files(); // 这一行获取的是一个已排序的列表，但我们下面会重新分组
    std::map<std::string, std::vector<std::string>> files_by_day;
    
    for (const auto& f : files) {
        try {
            // 文件名格式: fct_YYYY-MM-DD_X.log
            std::string date_str = f.substr(4, 10);
            files_by_day[date_str].push_back(f);
        } catch(...) {}
    }
    
    for (auto& pair : files_by_day) {
        // [核心修改] 在清理之前，对当天的文件列表进行降序排序
        // 这样可以确保最新的文件排在前面
        std::sort(pair.second.rbegin(), pair.second.rend());

        if (pair.second.size() > MAX_LOG_FILES_PER_DAY) {
            // 现在 pair.second[0] 是最新的, pair.second[N] 是最旧的
            // 这个循环将从第 MAX_LOG_FILES_PER_DAY 个文件开始删除，即删除所有最旧的文件
            for (size_t i = MAX_LOG_FILES_PER_DAY; i < pair.second.size(); ++i) {
                fs::remove(fs::path(log_dir_path_) / pair.second[i]);
                LOGD("Cleaned up excess log file: %s", pair.second[i].c_str());
            }
        }
    }
    
    if (files_by_day.size() > MAX_LOG_RETENTION_DAYS) {
        auto it = files_by_day.begin();
        size_t to_delete_count = files_by_day.size() - MAX_LOG_RETENTION_DAYS;
        for (size_t i = 0; i < to_delete_count; ++i) {
            for (const auto& f : it->second) {
                fs::remove(fs::path(log_dir_path_) / f);
                LOGD("Cleaned up outdated day log file: %s", f.c_str());
            }
            it = files_by_day.erase(it);
        }
    }

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
        manage_log_files();
        auto files = get_log_files();

        int next_index = 1;
        if (!files.empty() && files[0].find(current_date_str) != std::string::npos) {
            try {
                std::string last_file = files[0];
                size_t underscore_pos = last_file.rfind('_');
                size_t dot_pos = last_file.rfind('.');
                int last_index = std::stoi(last_file.substr(underscore_pos + 1, dot_pos - underscore_pos - 1));
                next_index = last_index + 1;
            } catch (...) {
                next_index = 1;
            }
        }
        std::string new_filename = "fct_" + current_date_str + "_" + std::to_string(next_index) + ".log";
        current_log_file_path_ = fs::path(log_dir_path_) / new_filename;
        current_log_line_count_ = 0;
        LOGI("Rotating to new log file: %s", new_filename.c_str());
    }
}

void Logger::writer_thread_func() {
    manage_log_files();

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
                {"ts", entry.timestamp_ms}, {"lvl", static_cast<int>(entry.level)},
                {"cat", entry.category}, {"msg", entry.message}
            };
            if (!entry.package_name.empty()) file_json["pkg"] = entry.package_name;
            if (entry.user_id != -1) file_json["uid"] = entry.user_id;
            log_file << file_json.dump() << std::endl;
        }
        current_log_line_count_ += temp_queue.size();
    }
}