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

using json = nlohmann::json;

// [新增] 日志级别，用于分类和前端显示
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

// [新增] 日志条目结构体，用于结构化日志数据
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
    // [新增] 获取单例实例
    static std::shared_ptr<Logger> get_instance(const std::string& log_dir_path);
    ~Logger();

    Logger(const Logger&) = delete;
    Logger& operator=(const Logger&) = delete;

    // [新增] 核心日志记录函数
    void log(LogLevel level, const std::string& category, const std::string& message, 
             const std::string& package_name = "", int user_id = -1);

    // [新增] 获取历史日志记录
    std::vector<LogEntry> get_history(int limit = 200) const;

    // [新增] 停止日志记录器
    void stop();

private:
    explicit Logger(const std::string& log_dir_path);
    
    // [新增] 异步写入线程函数
    void writer_thread_func();
    // [新增] 确保日志文件存在并打开
    void ensure_log_file();

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