// daemon/cpp/database_manager.cpp
#include "database_manager.h"
#include <android/log.h>
#include <filesystem>

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
        if (!db_.tableExists("app_policies_v3")) { // 使用新版本表名
            LOGI("Table 'app_policies_v3' does not exist. Creating it.");
            if (db_.tableExists("app_policies_v2")) {
                 LOGI("Old 'app_policies_v2' table found. Dropping it.");
                 db_.exec("DROP TABLE app_policies_v2;");
            }
            // [关键修改] 将 policy 的 DEFAULT 值从 2 (智能) 修改为 0 (豁免)
            db_.exec(R"(
                CREATE TABLE app_policies_v3 (
                    package_name TEXT NOT NULL,
                    user_id INTEGER NOT NULL,
                    policy INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (package_name, user_id)
                )
            )");
        }
    } catch (const std::exception& e) {
        LOGE("Database initialization failed: %s", e.what());
    }
}

std::optional<AppConfig> DatabaseManager::get_app_config(const std::string& package_name, int user_id) {
    try {
        SQLite::Statement query(db_, "SELECT policy FROM app_policies_v3 WHERE package_name = ? AND user_id = ?");
        query.bind(1, package_name);
        query.bind(2, user_id);

        if (query.executeStep()) {
            AppConfig config;
            config.package_name = package_name;
            config.user_id = user_id;
            config.policy = static_cast<AppPolicy>(query.getColumn(0).getInt());
            return config;
        }
    } catch (const std::exception& e) {
        LOGE("Failed to get app config for '%s' (user %d): %s", package_name.c_str(), user_id, e.what());
    }
    return std::nullopt;
}

bool DatabaseManager::set_app_config(const AppConfig& config) {
    try {
        SQLite::Statement query(db_, R"(
            INSERT INTO app_policies_v3 (package_name, user_id, policy)
            VALUES (?, ?, ?)
            ON CONFLICT(package_name, user_id) DO UPDATE SET
                policy = excluded.policy
        )");
        query.bind(1, config.package_name);
        query.bind(2, config.user_id);
        query.bind(3, static_cast<int>(config.policy));
        
        return query.exec() > 0;
    } catch (const std::exception& e) {
        LOGE("Failed to set app config for '%s' (user %d): %s", config.package_name.c_str(), config.user_id, e.what());
        return false;
    }
}

bool DatabaseManager::clear_all_policies() {
    try {
        db_.exec("DELETE FROM app_policies_v3");
        return true;
    } catch (const std::exception& e) {
        LOGE("Failed to clear all policies: %s", e.what());
        return false;
    }
}

std::vector<AppConfig> DatabaseManager::get_all_app_configs() {
    std::vector<AppConfig> configs;
    try {
        SQLite::Statement query(db_, "SELECT package_name, user_id, policy FROM app_policies_v3");
        while (query.executeStep()) {
            AppConfig config;
            config.package_name = query.getColumn(0).getString();
            config.user_id = query.getColumn(1).getInt();
            config.policy = static_cast<AppPolicy>(query.getColumn(2).getInt());
            configs.push_back(config);
        }
    } catch (const std::exception& e) {
        LOGE("Failed to get all app configs: %s", e.what());
    }
    return configs;
}