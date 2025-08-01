// daemon/cpp/logger.cpp
#include "logger.h"
#include <fstream>
#include <filesystem>
#include <chrono>
#include <iomanip>
#include <ctime>
#include <algorithm>
#include <android/log.h>

#define LOG_TAG "cerberusd_logger_v4_pagination" // 版本号更新
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace fs = std::filesystem;

json LogEntry::to_json() const {
    json j = {
        {"timestamp", timestamp_ms},
        {"level", static_cast<int>(level)},
        {"category", category},
        {"message", message},
        {"package_name", package_name},
        {"user_id", user_id}
    };
    if (!details.is_null()) {
        j["details"] = details;
    }
    return j;
}

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
    ensure_log_file();
    writer_thread_ = std::thread(&Logger::writer_thread_func, this);
}

Logger::~Logger() {
    stop();
}

void Logger::stop() {
    if (!is_running_.exchange(false)) {
        return;
    }
    cv_.notify_one();
    if (writer_thread_.joinable()) {
        writer_thread_.join();
    }
}

void Logger::log(LogLevel level, const std::string& category, const std::string& message, const std::string& package_name, int user_id, const json& details) {
    long long timestamp = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()
    ).count();

    LogEntry entry{timestamp, level, category, message, package_name, user_id, details};

    {
        std::lock_guard<std::mutex> lock(queue_mutex_);
        log_queue_.push_back(entry);
    }
    cv_.notify_one();
}

// [分页加载] 修改函数实现以支持分页
std::vector<LogEntry> Logger::get_logs(std::optional<long long> since_timestamp_ms,
                                     std::optional<long long> before_timestamp_ms,
                                     int limit) const {
    std::vector<LogEntry> results;

    // 分页加载（before_timestamp_ms）和轮询新日志（since_timestamp_ms）是互斥的
    if (before_timestamp_ms.has_value()) {
        // 场景：加载历史记录的“上一页”
        read_logs_from_file(results, std::nullopt, before_timestamp_ms, limit);
    } else {
        // 场景：加载初始页面或轮询新日志
        read_logs_from_file(results, since_timestamp_ms, std::nullopt,
                            since_timestamp_ms.has_value() ? -1 : limit);

        // 如果是轮询新日志，也需要检查内存中的队列
        if (since_timestamp_ms.has_value()) {
            std::lock_guard<std::mutex> lock(queue_mutex_);
            for (const auto& entry : log_queue_) {
                if (entry.timestamp_ms > since_timestamp_ms.value()) {
                    results.push_back(entry);
                }
            }
        }
    }

    // 排序和去重逻辑保持不变，但现在处理的数据量大大减少了
    std::sort(results.begin(), results.end(), [](const auto& a, const auto& b) {
        return a.timestamp_ms > b.timestamp_ms;
    });

    results.erase(std::unique(results.begin(), results.end(), [](const auto& a, const auto& b) {
        return a.timestamp_ms == b.timestamp_ms && a.message == b.message;
    }), results.end());

    // 对初始加载和分页加载应用limit，但不限制轮询
    if (results.size() > limit && !since_timestamp_ms.has_value()) {
        results.resize(limit);
    }

    return results;
}

// [分页加载] 扩展函数以处理before_timestamp_ms
void Logger::read_logs_from_file(std::vector<LogEntry>& out_logs,
                                 std::optional<long long> since_timestamp_ms,
                                 std::optional<long long> before_timestamp_ms,
                                 int limit) const {
    std::string path_to_read = current_log_file_path_;
    if (!fs::exists(path_to_read)) return;

    std::ifstream log_file(path_to_read);
    if (!log_file.is_open()) return;

    std::vector<std::string> lines;
    std::string line;
    while (std::getline(log_file, line)) {
        lines.push_back(line);
    }

    for (auto it = lines.rbegin(); it != lines.rend(); ++it) {
        // 如果已达到限制，则停止（limit为-1表示不限制）
        if (limit > 0 && out_logs.size() >= limit) break;

        try {
            json j = json::parse(*it);
            long long timestamp = j.value("ts", 0LL);

            // 轮询新日志的逻辑
            if (since_timestamp_ms.has_value() && timestamp <= since_timestamp_ms.value()) {
                break; // 已找到比“最新”日志更旧的日志，停止
            }

            // 分页加载的逻辑
            if (before_timestamp_ms.has_value() && timestamp >= before_timestamp_ms.value()) {
                continue; // 跳过比“上一页最后一条”更新或相等的日志
            }

            LogEntry entry{
                .timestamp_ms = timestamp,
                .level = static_cast<LogLevel>(j.value("lvl", 0)),
                .category = j.value("cat", ""),
                .message = j.value("msg", ""),
                .package_name = j.value("pkg", ""),
                .user_id = j.value("uid", -1),
                .details = j.value("details", nullptr)
            };
            out_logs.push_back(entry);
        } catch (const json::exception& e) {
            // ignore malformed lines
        }
    }
}

void Logger::ensure_log_file() {
    time_t now = time(nullptr);
    tm ltm = {};
    localtime_r(&now,&ltm);

    if (ltm.tm_yday != current_day_) {
        current_day_ = ltm.tm_yday;
        char buf[32];
        strftime(buf, sizeof(buf), "%Y-%m-%d",&ltm);
        current_log_file_path_ = fs::path(log_dir_path_) / (std::string("events-") + buf + ".log");
    }
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

        ensure_log_file();
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
            if (!entry.details.is_null()) file_json["details"] = entry.details;

            log_file << file_json.dump() << std::endl;
        }
    }
}