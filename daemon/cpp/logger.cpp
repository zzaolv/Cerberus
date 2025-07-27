// daemon/cpp/logger.cpp
#include "logger.h"
#include "main.h" // For broadcast_message
#include "uds_server.h" // For server instance access
#include <fstream>
#include <filesystem>
#include <chrono>
#include <iomanip>
#include <ctime>
#include <algorithm>

namespace fs = std::filesystem;

// [新增] 全局UdsServer引用，用于日志广播
extern std::unique_ptr<UdsServer> g_server;

std::shared_ptr<Logger> Logger::instance_ = nullptr;
std::mutex Logger::instance_mutex_;

// [新增] LogEntry 序列化为 JSON
json LogEntry::to_json() const {
    return json{
        {"timestamp", timestamp_ms},
        {"level", static_cast<int>(level)},
        {"category", category},
        {"message", message},
        {"package_name", package_name},
        {"user_id", user_id}
    };
}


std::shared_ptr<Logger> Logger::get_instance(const std::string& log_dir_path) {
    std::lock_guard<std::mutex> lock(instance_mutex_);
    if (!instance_) {
        // 使用 make_shared 和私有构造函数
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

std::vector<LogEntry> Logger::get_history(int limit) const {
    std::vector<LogEntry> history;
    std::lock_guard<std::mutex> lock(queue_mutex_);
    
    // 优先从内存队列中获取最新的日志
    int count = 0;
    for (auto it = log_queue_.rbegin(); it != log_queue_.rend() && count < limit; ++it, ++count) {
        history.push_back(*it);
    }
    
    // 如果内存中的日志不够，从文件中读取
    if (count < limit && fs::exists(current_log_file_path_)) {
        std::ifstream log_file(current_log_file_path_);
        std::string line;
        std::vector<std::string> lines;
        while (std::getline(log_file, line)) {
            lines.push_back(line);
        }

        for (auto it = lines.rbegin(); it != lines.rend() && count < limit; ++it, ++count) {
            try {
                json j = json::parse(*it);
                LogEntry entry{
                    .timestamp_ms = j.value("ts", 0LL),
                    .level = static_cast<LogLevel>(j.value("lvl", 0)),
                    .category = j.value("cat", ""),
                    .message = j.value("msg", ""),
                    .package_name = j.value("pkg", ""),
                    .user_id = j.value("uid", -1)
                };
                history.push_back(entry);
            } catch (...) {
                // Ignore parse errors
            }
        }
    }
    std::reverse(history.begin(), history.end());
    return history;
}

void Logger::ensure_log_file() {
    time_t now = time(nullptr);
    tm ltm;
    localtime_r(&now, &ltm);  // 修正：<m -> &ltm

    if (ltm.tm_yday != current_day_) {
        current_day_ = ltm.tm_yday;
        char buf[32];
        strftime(buf, sizeof(buf), "%Y-%m-%d", &ltm);  // 修正：<m -> &ltm
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
        if (!log_file.is_open()) continue;

        for (const auto& entry : temp_queue) {
            // 写入文件的JSON格式可以精简，以节省空间
            json file_json = {
                {"ts", entry.timestamp_ms},
                {"lvl", static_cast<int>(entry.level)},
                {"cat", entry.category},
                {"msg", entry.message}
            };
            if (!entry.package_name.empty()) file_json["pkg"] = entry.package_name;
            if (entry.user_id != -1) file_json["uid"] = entry.user_id;
            
            log_file << file_json.dump() << std::endl;

            // 广播给UI客户端的JSON需要完整信息
            if (g_server) {
                json broadcast_payload = entry.to_json();
                g_server->broadcast_message(json{
                    {"type", "stream.new_log_entry"},
                    {"payload", broadcast_payload}
                }.dump());
            }
        }
    }
}