// daemon/cpp/database_manager.h
#ifndef CERBERUS_DATABASE_MANAGER_H
#define CERBERUS_DATABASE_MANAGER_H

#include <string>
#include <vector>
#include <optional>
#include <mutex>
#include <nlohmann/json.hpp>
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
    long long cumulative_runtime_seconds = 0;
    // [新增] 资源统计字段
    long long background_wakeups = 0;
    long long background_cpu_seconds = 0;
    long long background_traffic_bytes = 0;
};

// 与V1.2版本日志类型同步
enum class LogEventType {
    GENERIC_INFO,
    GENERIC_SUCCESS,
    GENERIC_WARNING,
    GENERIC_ERROR,
    DAEMON_START,
    DAEMON_SHUTDOWN,
    SCREEN_ON,
    SCREEN_OFF,
    APP_START,
    APP_STOP,
    APP_FOREGROUND,
    APP_BACKGROUND,
    APP_FROZEN,
    APP_UNFROZEN,
    POWER_UPDATE,
    POWER_WARNING,
    DOZE_STATE_CHANGE,
    DOZE_RESOURCE_REPORT,
    BATCH_OPERATION_START,
    NETWORK_BLOCKED,
    NETWORK_UNBLOCKED,
    SCHEDULED_TASK_EXEC,
    // [新增] 为健康检查新增一个事件类型
    HEALTH_CHECK_STATUS,
    UNKNOWN
};

struct LogEntry {
    long long timestamp;
    LogEventType event_type;
    nlohmann::json payload;
};

class DatabaseManager {
public:
    explicit DatabaseManager(const std::string& db_path);

    std::optional<AppConfig> get_app_config(const std::string& package_name);
    bool set_app_config(const AppConfig& config);
    std::vector<AppConfig> get_all_app_configs();

    // [新增] 更新统计数据的接口
    bool update_app_stats(const std::string& package_name, long long wakeups, long long cpu_seconds, long long traffic_bytes);
    bool clear_all_stats(); // 用于UI上的清空数据功能

    bool log_event(LogEventType type, const nlohmann::json& payload);
    std::vector<LogEntry> get_logs(int limit, int offset);

private:
    void initialize_database();
    SQLite::Database db_;
    std::mutex db_mutex_;
};

#endif //CERBERUS_DATABASE_MANAGER_H