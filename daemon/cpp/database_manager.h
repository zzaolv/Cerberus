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
};

// [新增] 扩展日志事件类型以支持新功能
enum class LogEventType {
    // 通用事件
    GENERIC_INFO,
    GENERIC_SUCCESS,
    GENERIC_WARNING,
    GENERIC_ERROR,
    // 系统事件
    DAEMON_START,
    DAEMON_SHUTDOWN,
    SCREEN_ON,
    SCREEN_OFF,
    // 应用生命周期事件
    APP_START,
    APP_STOP,
    APP_FOREGROUND,
    APP_BACKGROUND,
    APP_FROZEN,
    APP_UNFROZEN,
    // [新增] 电源与Doze事件
    POWER_UPDATE,
    POWER_WARNING,
    DOZE_STATE_CHANGE,
    DOZE_RESOURCE_REPORT,
    // [新增] 批量操作与网络控制事件
    BATCH_OPERATION_START,
    NETWORK_BLOCKED,
    NETWORK_UNBLOCKED,
    // [新增] 定时任务事件
    SCHEDULED_TASK_EXEC,
    // 其他
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
    bool update_app_runtime(const std::string& package_name, long long session_seconds);
    std::vector<AppConfig> get_all_app_configs();

    bool log_event(LogEventType type, const nlohmann::json& payload);
    std::vector<LogEntry> get_logs(int limit, int offset);

private:
    void initialize_database();
    SQLite::Database db_;
    std::mutex db_mutex_;
};

#endif //CERBERUS_DATABASE_MANAGER_H