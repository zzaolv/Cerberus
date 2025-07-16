// daemon/cpp/database_manager.h
#ifndef CERBERUS_DATABASE_MANAGER_H
#define CERBERUS_DATABASE_MANAGER_H

#include <string>
#include <vector>
#include <optional>
#include <mutex>
#include <SQLiteCpp/Database.h>
#include <SQLiteCpp/Statement.h>
#include <SQLiteCpp/Transaction.h>

enum class AppPolicy {
    EXEMPTED = 0, IMPORTANT = 1, STANDARD = 2, STRICT = 3
};

struct AppConfig {
    std::string package_name;
    AppPolicy policy = AppPolicy::STANDARD;
    bool force_playback_exempt = false;
    bool force_network_exempt = false;
};

enum class LogLevel { INFO, SUCCESS, WARNING, ERROR, EVENT };

struct LogEntry {
    long long timestamp;
    LogLevel level;
    std::string message;
    std::string app_name;
};

class DatabaseManager {
public:
    explicit DatabaseManager(const std::string& db_path);

    std::optional<AppConfig> get_app_config(const std::string& package_name);
    bool set_app_config(const AppConfig& config);
    std::vector<AppConfig> get_all_app_configs();

    bool log_event(LogLevel level, const std::string& message, const std::string& app_name = "");
    std::vector<LogEntry> get_logs(int limit, int offset);

private:
    void initialize_database();
    SQLite::Database db_;
    std::mutex db_mutex_;
};

#endif //CERBERUS_DATABASE_MANAGER_H