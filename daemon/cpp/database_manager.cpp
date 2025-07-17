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

        // 【修复】创建 app_policies 表，如果已存在则会静默失败，这没关系
        db_.exec(R"(
            CREATE TABLE IF NOT EXISTS app_policies (
                package_name TEXT PRIMARY KEY,
                policy INTEGER NOT NULL DEFAULT 2,
                force_playback_exempt INTEGER NOT NULL DEFAULT 0,
                force_network_exempt INTEGER NOT NULL DEFAULT 0,
                cumulative_runtime_seconds INTEGER NOT NULL DEFAULT 0
            )
        )");

        // 【修复】为旧版本数据库添加新列。如果失败（例如列已存在），则捕获异常并忽略
        try {
            db_.exec("ALTER TABLE app_policies ADD COLUMN cumulative_runtime_seconds INTEGER NOT NULL DEFAULT 0;");
            LOGI("Successfully added 'cumulative_runtime_seconds' to 'app_policies' table for upgrade.");
        } catch (const SQLite::Exception& e) {
            // 忽略 "duplicate column name" 错误，这是预期的行为
            if (std::string(e.what()).find("duplicate column name") == std::string::npos) {
                // 如果是其他错误，则打印出来
                LOGW("Could not add column (might already exist): %s", e.what());
            }
        }

        // 创建 logs 表
        db_.exec(R"(
            CREATE TABLE IF NOT EXISTS logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                event_type INTEGER NOT NULL,
                payload TEXT NOT NULL
            )
        )");
        // 创建索引
        db_.exec("CREATE INDEX IF NOT EXISTS idx_logs_timestamp ON logs(timestamp DESC);");

    } catch (const std::exception& e) {
        LOGE("Database initialization failed: %s", e.what());
    }
}

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

bool DatabaseManager::set_app_config(const AppConfig& config) {
    std::lock_guard<std::mutex> lock(db_mutex_);
    try {
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

bool DatabaseManager::update_app_runtime(const std::string& package_name, long long session_seconds) {
    if (session_seconds <= 0) return true;
    std::lock_guard<std::mutex> lock(db_mutex_);
    try {
        SQLite::Statement query(db_, "UPDATE app_policies SET cumulative_runtime_seconds = cumulative_runtime_seconds + ? WHERE package_name = ?");
        // 【编译修复】显式将 long long 转换为 int64_t 来消除歧义
        query.bind(1, static_cast<int64_t>(session_seconds));
        query.bind(2, package_name);
        query.exec();
        return query.getChanges() > 0;
    } catch (const std::exception& e) {
        LOGE("Failed to update cumulative runtime for %s: %s", package_name.c_str(), e.what());
        return false;
    }
}

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