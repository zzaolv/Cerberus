// daemon/cpp/database_manager.cpp
#include "database_manager.h"
#include <android/log.h>
#include <chrono>

#define LOG_TAG "cerberusd_db"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
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

        db_.exec(R"(
            CREATE TABLE IF NOT EXISTS app_policies (
                package_name TEXT PRIMARY KEY,
                policy INTEGER NOT NULL DEFAULT 2,
                force_playback_exempt INTEGER NOT NULL DEFAULT 0,
                force_network_exempt INTEGER NOT NULL DEFAULT 0,
                cumulative_runtime_seconds INTEGER NOT NULL DEFAULT 0
            )
        )");
        
        // 为旧数据库升级，添加统计列
        const char* columns_to_add[] = {
            "cumulative_runtime_seconds INTEGER NOT NULL DEFAULT 0",
            "background_wakeups INTEGER NOT NULL DEFAULT 0",
            "background_cpu_seconds INTEGER NOT NULL DEFAULT 0",
            "background_traffic_bytes INTEGER NOT NULL DEFAULT 0"
        };

        for(const auto& col_def : columns_to_add) {
            try {
                db_.exec("ALTER TABLE app_policies ADD COLUMN " + std::string(col_def));
                LOGI("Successfully added column '%s' to 'app_policies' table.", col_def);
            } catch (const SQLite::Exception& e) {
                if (std::string(e.what()).find("duplicate column name") == std::string::npos) {
                    LOGW("Could not add column (might already exist): %s", e.what());
                }
            }
        }
        
        db_.exec(R"(
            CREATE TABLE IF NOT EXISTS logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                event_type INTEGER NOT NULL,
                payload TEXT NOT NULL
            )
        )");
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
        SQLite::Statement query(db_, R"(
            SELECT policy, force_playback_exempt, force_network_exempt, cumulative_runtime_seconds, 
                   background_wakeups, background_cpu_seconds, background_traffic_bytes
            FROM app_policies WHERE package_name = ?
        )");
        query.bind(1, package_name);

        if (query.executeStep()) {
            AppConfig config;
            config.package_name = package_name;
            config.policy = static_cast<AppPolicy>(query.getColumn(0).getInt());
            config.force_playback_exempt = query.getColumn(1).getInt();
            config.force_network_exempt = query.getColumn(2).getInt();
            config.cumulative_runtime_seconds = query.getColumn(3).getInt64();
            config.background_wakeups = query.getColumn(4).getInt64();
            config.background_cpu_seconds = query.getColumn(5).getInt64();
            config.background_traffic_bytes = query.getColumn(6).getInt64();
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

std::vector<AppConfig> DatabaseManager::get_all_app_configs() {
    std::lock_guard<std::mutex> lock(db_mutex_);
    std::vector<AppConfig> configs;
    try {
        SQLite::Statement query(db_, R"(
            SELECT package_name, policy, force_playback_exempt, force_network_exempt, cumulative_runtime_seconds,
                   background_wakeups, background_cpu_seconds, background_traffic_bytes
            FROM app_policies
        )");
        while (query.executeStep()) {
            configs.emplace_back(AppConfig{
                .package_name = query.getColumn(0).getString(),
                .policy = static_cast<AppPolicy>(query.getColumn(1).getInt()),
                .force_playback_exempt = query.getColumn(2).getInt(),
                .force_network_exempt = query.getColumn(3).getInt(),
                .cumulative_runtime_seconds = query.getColumn(4).getInt64(),
                .background_wakeups = query.getColumn(5).getInt64(),
                .background_cpu_seconds = query.getColumn(6).getInt64(),
                .background_traffic_bytes = query.getColumn(7).getInt64(),
            });
        }
    } catch (const std::exception& e) {
        LOGE("Failed to get all app configs: %s", e.what());
    }
    return configs;
}

bool DatabaseManager::update_app_stats(const std::string& package_name, long long wakeups, long long cpu_seconds, long long traffic_bytes) {
    if (wakeups <= 0 && cpu_seconds <= 0 && traffic_bytes <= 0) return true;
    std::lock_guard<std::mutex> lock(db_mutex_);
    try {
        SQLite::Statement query(db_, R"(
            UPDATE app_policies SET
            background_wakeups = background_wakeups + ?,
            background_cpu_seconds = background_cpu_seconds + ?,
            background_traffic_bytes = background_traffic_bytes + ?
            WHERE package_name = ?
        )");
        query.bind(1, wakeups);
        query.bind(2, cpu_seconds);
        query.bind(3, traffic_bytes);
        query.bind(4, package_name);
        query.exec();
        return query.getChanges() > 0;
    } catch (const std::exception& e) {
        LOGE("Failed to update app stats for %s: %s", package_name.c_str(), e.what());
        return false;
    }
}

bool DatabaseManager::clear_all_stats() {
    std::lock_guard<std::mutex> lock(db_mutex_);
    try {
        SQLite::Statement query(db_, R"(
            UPDATE app_policies SET
            background_wakeups = 0,
            background_cpu_seconds = 0,
            background_traffic_bytes = 0
        )");
        query.exec();
        return true;
    } catch (const std::exception& e) {
        LOGE("Failed to clear all stats: %s", e.what());
        return false;
    }
}