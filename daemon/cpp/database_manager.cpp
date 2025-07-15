// daemon/cpp/database_manager.cpp
#include "database_manager.h"
#include <android/log.h>

#define LOG_TAG "cerberusd_db"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

DatabaseManager::DatabaseManager(const std::string& db_path)
    : db_(db_path, SQLite::OPEN_READWRITE | SQLite::OPEN_CREATE) {
    LOGI("Database opened at %s", db_path.c_str());
    initialize_database();
}

void DatabaseManager::initialize_database() {
    try {
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
    } catch (const std::exception& e) {
        LOGE("Database initialization failed: %s", e.what());
    }
}

std::optional<AppConfig> DatabaseManager::get_app_config(const std::string& package_name) {
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