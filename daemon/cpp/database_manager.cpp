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
        if (!db_.tableExists("app_policies")) {
            LOGI("Table 'app_policies' does not exist. Creating it.");
            db_.exec(R"(
                CREATE TABLE app_policies (
                    package_name TEXT PRIMARY KEY,
                    policy INTEGER NOT NULL DEFAULT 2,
                    force_playback_exempt INTEGER NOT NULL DEFAULT 0,
                    force_network_exempt INTEGER NOT NULL DEFAULT 0
                )
            )");
        }
        if (!db_.tableExists("logs")) {
            LOGI("Table 'logs' does not exist. Creating it.");
            db_.exec(R"(
                CREATE TABLE logs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp INTEGER NOT NULL,
                    level INTEGER NOT NULL,
                    message TEXT NOT NULL,
                    app_name TEXT
                )
            )");
             db_.exec("CREATE INDEX idx_logs_timestamp ON logs(timestamp DESC);");
        }

    } catch (const std::exception& e) {
        LOGE("Database initialization failed: %s", e.what());
    }
}

bool DatabaseManager::log_event(LogLevel level, const std::string& message, const std::string& app_name) {
    std::lock_guard<std::mutex> lock(db_mutex_);
    try {
        SQLite::Statement query(db_, "INSERT INTO logs (timestamp, level, message, app_name) VALUES (?, ?, ?, ?)");
        query.bind(1, std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count());
        query.bind(2, static_cast<int>(level));
        query.bind(3, message);
        if (!app_name.empty()) {
            query.bind(4, app_name);
        } else {
            query.bind(4); // Bind NULL
        }
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
        SQLite::Statement query(db_, "SELECT timestamp, level, message, app_name FROM logs ORDER BY timestamp DESC LIMIT ? OFFSET ?");
        query.bind(1, limit);
        query.bind(2, offset);
        while(query.executeStep()){
            entries.push_back({
                query.getColumn(0).getInt64(),
                static_cast<LogLevel>(query.getColumn(1).getInt()),
                query.getColumn(2).getString(),
                query.getColumn(3).isNull() ? "" : query.getColumn(3).getString()
            });
        }
    } catch (const std::exception& e) {
        LOGE("Failed to get logs: %s", e.what());
    }
    return entries;
}

std::optional<AppConfig> DatabaseManager::get_app_config(const std::string& package_name) {
    std::lock_guard<std::mutex> lock(db_mutex_);
    try {
        SQLite::Statement query(db_, "SELECT policy, force_playback_exempt, force_network_exempt FROM app_policies WHERE package_name = ?");
        query.bind(1, package_name);

        if (query.executeStep()) {
            AppConfig config;
            config.package_name = package_name;
            config.policy = static_cast<AppPolicy>(query.getColumn(0).getInt());
            config.force_playback_exempt = query.getColumn(1).getInt();
            config.force_network_exempt = query.getColumn(2).getInt();
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
        SQLite::Statement query(db_, "SELECT package_name, policy, force_playback_exempt, force_network_exempt FROM app_policies");
        while (query.executeStep()) {
            AppConfig config;
            config.package_name = query.getColumn(0).getString();
            config.policy = static_cast<AppPolicy>(query.getColumn(1).getInt());
            config.force_playback_exempt = query.getColumn(2).getInt();
            config.force_network_exempt = query.getColumn(3).getInt();
            configs.push_back(config);
        }
    } catch (const std::exception& e) {
        LOGE("Failed to get all app configs: %s", e.what());
    }
    return configs;
}