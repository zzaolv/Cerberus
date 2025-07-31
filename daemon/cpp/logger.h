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
#include <optional> // [新增] For optional since_timestamp

using json = nlohmann::json;

enum class LogLevel {
    INFO,    // ℹ️
    SUCCESS, // ✅
    WARN,    // ⚠️
    ERROR,   // ❌
    EVENT,   // ⚡️
    DOZE,    // 🌙
    BATTERY, // 🔋
    REPORT,  // 📊
    ACTION_OPEN,     // ▶️
    ACTION_CLOSE,    // ⏹️
    ACTION_FREEZE,   // ❄️
    ACTION_UNFREEZE, // ☀️
    ACTION_DELAY,    // 🤣
    TIMER,           // ⏰
    BATCH_PARENT     // 📦 (用于批量处理的父条目)
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

    // [日志重构] 修改函数以支持增量更新
    std::vector<LogEntry> get_logs(std::optional<long long> since_timestamp_ms, int limit) const;

    void stop();

private:
    explicit Logger(const std::string& log_dir_path);
    
    void writer_thread_func();
    void ensure_log_file();
    // [日志重构] 新增的日志文件读取辅助函数
    void read_logs_from_file(std::vector<LogEntry>& out_logs, std::optional<long long> since_timestamp_ms, int limit) const;


    static std::shared_ptr<Logger> instance_;
    static std::mutex instance_mutex_;

    std::string log_dir_path_;
    std::string current_log_file_path_;
    
    std::deque<LogEntry> log_queue_;
    mutable std::mutex queue_mutex_;
    std::condition_variable cv_;
    std::thread writer_thread_;
    std::atomic<bool> is_running_;
    int current_day_ = -1;
};

#endif // CERBERUS_LOGGER_H