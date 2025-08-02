// daemon/cpp/logger.h
#ifndef CERBERUS_LOGGER_H
#define CERBERUS_LOGGER_H

#include <string>
#include <vector>
#include <mutex>
#include <deque>
#include <thread>
#include <atomic>
#include <condition_variable>
#include <nlohmann/json.hpp>
#include <memory>
#include <optional>

using json = nlohmann::json;

enum class LogLevel {
    INFO,
    SUCCESS,
    WARN,
    ERROR,
    EVENT,
    DOZE,
    BATTERY,
    REPORT,
    ACTION_OPEN,
    ACTION_CLOSE,
    ACTION_FREEZE,
    ACTION_UNFREEZE,
    ACTION_DELAY,
    TIMER,
    BATCH_PARENT
};

struct LogEntry {
    long long timestamp_ms;
    LogLevel level;
    std::string category;
    std::string message;
    std::string package_name;
    int user_id;

    json to_json() const;
};

class Logger : public std::enable_shared_from_this<Logger> {
public:
    static std::shared_ptr<Logger> get_instance(const std::string& log_dir_path);
    ~Logger();

    Logger(const Logger&) = delete;
    Logger& operator=(const Logger&) = delete;

    void log(LogLevel level, const std::string& category, const std::string& message,
             const std::string& package_name = "", int user_id = -1);
    
    // [新] 批量日志记录，用于Doze报告
    void log_batch(const std::vector<LogEntry>& entries);

    // [修改] get_logs 现在接受文件名
    std::vector<LogEntry> get_logs_from_file(const std::string& filename, int limit, long long before_timestamp_ms) const;
    
    // [新] 获取日志文件列表
    std::vector<std::string> get_log_files() const;

    void stop();

private:
    explicit Logger(const std::string& log_dir_path);
    void writer_thread_func();
    
    // [新] 文件管理逻辑
    void manage_log_files();
    void rotate_log_file_if_needed(size_t new_entries_count);
    void cleanup_old_files();
    
    static std::shared_ptr<Logger> instance_;
    static std::mutex instance_mutex_;

    std::string log_dir_path_;
    std::string current_log_file_path_;
    int current_log_line_count_ = 0;

    std::deque<LogEntry> log_queue_;
    mutable std::mutex queue_mutex_;
    std::condition_variable cv_;
    std::thread writer_thread_;
    std::atomic<bool> is_running_;
};

#endif // CERBERUS_LOGGER_H