// daemon/cpp/database_manager.cpp
#include "database_manager.h"
#include <android/log.h>
#include <chrono>

#define LOG_TAG "cerberusd_db"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

DatabaseManager::DatabaseManager(const std::string& db_path)
    : db_(db_path, SQLite::OPEN_READWRITE | SQLite::OPEN_CREATE) {
    LOGI("Database opened at %s", db_path.c_str());
    initialize_database();
}

void DatabaseManager::initialize_database() {
    std::lock_guard<std::mutex> lock(db_mutex_);
    try {
        db_.exec("PRAGMA journal_mode=WAL;");
        // 【重构】更新 app_policies 表，增加累计运行时长列
        if (!db_.tableExists("app_policies")) {
            LOGI("Table 'app_policies' does not exist. Creating it.");
            db_.exec(R"(
                CREATE TABLE app_policies (
                    package_name TEXT PRIMARY KEY,
                    policy INTEGER NOT NULL DEFAULT 2,
                    force_playback_exempt INTEGER NOT NULL DEFAULT 0,
                    force_network_exempt INTEGER NOT NULL DEFAULT 0,
                    cumulative_runtime_seconds INTEGER NOT NULL DEFAULT 0
                )
            )");
        } else {
            // 如果表已存在，检查是否需要添加新列（用于升级）
            if (!db_.columnExists("app_policies", "cumulative_runtime_seconds")) {
                 LOGI("Adding 'cumulative_runtime_seconds' to 'app_policies' table.");
                 db_.exec("ALTER TABLE app_policies ADD COLUMN cumulative_runtime_seconds INTEGER NOT NULL DEFAULT 0;");
            }
        }

        // 【重构】更新 logs 表，存储事件类型和JSON payload
        if (!db_.tableExists("logs")) {
            LOGI("Table 'logs' does not exist. Creating it.");
            db_.exec(R"(
                CREATE TABLE logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp INTEGER NOT NULL,
                    event_type INTEGER NOT NULL,
                    payload TEXT NOT NULL
                )
            )");
             db_.exec("CREATE INDEX idx_logs_timestamp ON logs(timestamp DESC);");
        }

    } catch (const std::exception& e) {
        LOGE("Database initialization failed: %s", e.what());
    }
}

// 【重构】实现新的日志记录函数
bool DatabaseManager::log_event(LogEventType type, const nlohmann::json& payload) {
    std::lock_guard<std::mutex> lock(db_mutex_);
    try {
        SQLite::Statement query(db_, "INSERT INTO logs (timestamp, event_type, payload) VALUES (?, ?, ?)");
        
        auto timestamp_ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
        query.bind(1, static_cast<int64_t>(timestamp_ms));
        query.bind(2, static_cast<int>(type));
        query.bind(3, payload.dump());

        query.exec();
        return true;
    } catch (const std::exception& e) {
        LOGE("Failed to log event: %s", e.what());
        return false;
    }
}

// 【重构】实现新的日志查询函数
std::vector<LogEntry> DatabaseManager::get_logs(int limit, int offset) {
    std::lock_guard<std::mutex> lock(db_mutex_);
    std::vector<LogEntry> entries;
    try {
        SQLite::Statement query(db_, "SELECT timestamp, event_type, payload FROM logs ORDER BY timestamp DESC LIMIT ? OFFSET ?");
        query.bind(1, limit);
        query.bind(2, offset);
        while(query.executeStep()){
            try {
                entries.push_back({
                    query.getColumn(0).getInt64(),
                    static_cast<LogEventType>(query.getColumn(1).getInt()),
                    nlohmann::json::parse(query.getColumn(2).getString())
                });
            } catch (const nlohmann::json::parse_error& e) {
                LOGE("Failed to parse log payload from DB: %s", e.what());
            }
        }
    } catch (const std::exception& e) {
        LOGE("Failed to get logs: %s", e.what());
    }
    return entries;
}

// 【重构】更新 get_app_config 以包含新字段
std::optional<AppConfig> DatabaseManager::get_app_config(const std::string& package_name) {
    std::lock_guard<std::mutex> lock(db_mutex_);
    try {
        SQLite::Statement query(db_, "SELECT policy, force_playback_exempt, force_network_exempt, cumulative_runtime_seconds FROM app_policies WHERE package_name = ?");
        query.bind(1, package_name);

        if (query.executeStep()) {
            AppConfig config;
            config.package_name = package_name;
            config.policy = static_cast<AppPolicy>(query.getColumn(0).getInt());
            config.force_playback_exempt = query.getColumn(1).getInt();
            config.force_network_exempt = query.getColumn(2).getInt();
            config.cumulative_runtime_seconds = query.getColumn(3).getInt64();
            return config;
        }
    } catch (const std::exception& e) {
        LOGE("Failed to get app config for %s: %s", package_name.c_str(), e.what());
    }
    return std::nullopt;
}

// 【重构】更新 set_app_config 以包含新字段，但只在INSERT时设置默认值
bool DatabaseManager::set_app_config(const AppConfig& config) {
    std::lock_guard<std::mutex> lock(db_mutex_);
    try {
        // cumulative_runtime_seconds 不应被UI直接重置，所以它不在UPDATE部分
        SQLite::Statement query(db_, R"(
            INSERT INTO app_policies (package_name, policy, force_playback_exempt, force_network_exempt)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(package_name) DO UPDATE SET
            policy = excluded.policy,
            force_playback_exempt = excluded.force_playback_exempt,
            force_network_exempt = excluded.force_network_exempt
        )");
        query.bind(1, config.package_name);
        query.bind(2, static_cast<int>(config.policy));
        query.bind(3, static_cast<int>(config.force_playback_exempt));
        query.bind(4, static_cast<int>(config.force_network_exempt));
        query.exec();
        return true;
    } catch (const std::exception& e) {
        LOGE("Failed to update app config for %s: %s", config.package_name.c_str(), e.what());
        return false;
    }
}

// 【新增】实现更新累计运行时长的函数
bool DatabaseManager::update_app_runtime(const std::string& package_name, long long session_seconds) {
    if (session_seconds <= 0) return true;
    std::lock_guard<std::mutex> lock(db_mutex_);
    try {
        SQLite::Statement query(db_, "UPDATE app_policies SET cumulative_runtime_seconds = cumulative_runtime_seconds + ? WHERE package_name = ?");
        query.bind(1, session_seconds);
        query.bind(2, package_name);
        query.exec();
        return query.getChanges() > 0;
    } catch (const std::exception& e) {
        LOGE("Failed to update cumulative runtime for %s: %s", package_name.c_str(), e.what());
        return false;
    }
}

// 【重构】更新 get_all_app_configs 以包含新字段
std::vector<AppConfig> DatabaseManager::get_all_app_configs() {
    std::lock_guard<std::mutex> lock(db_mutex_);
    std::vector<AppConfig> configs;
    try {
        SQLite::Statement query(db_, "SELECT package_name, policy, force_playback_exempt, force_network_exempt, cumulative_runtime_seconds FROM app_policies");
        while (query.executeStep()) {
            AppConfig config;
            config.package_name = query.getColumn(0).getString();
            config.policy = static_cast<AppPolicy>(query.getColumn(1).getInt());
            config.force_playback_exempt = query.getColumn(2).getInt();
            config.force_network_exempt = query.getColumn(3).getInt();
            config.cumulative_runtime_seconds = query.getColumn(4).getInt64();
            configs.push_back(config);
        }
    } catch (const std::exception& e) {
        LOGE("Failed to get all app configs: %s", e.what());
    }
    return configs;
}