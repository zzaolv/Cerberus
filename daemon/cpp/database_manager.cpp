// daemon/cpp/database_manager.cpp
#include "database_manager.h"
#include <android/log.h>
#include <filesystem>

#define LOG_TAG "cerberusd_db_v9"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

DatabaseManager::DatabaseManager(const std::string& db_path)
    : db_(db_path, SQLite::OPEN_READWRITE | SQLite::OPEN_CREATE) {
    LOGI("Database opened at %s", db_path.c_str());
    initialize_database();
}

void DatabaseManager::initialize_database() {
    try {
        if (!db_.tableExists("app_policies_v3")) {
            LOGI("Table 'app_policies_v3' does not exist. Creating it.");
            db_.exec(R"(
                CREATE TABLE app_policies_v3 (
                    package_name TEXT NOT NULL,
                    user_id INTEGER NOT NULL,
                    policy INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (package_name, user_id)
                )
            )");
        }

        // [核心修改] 更新 master_config 表结构和默认值
        if (!db_.tableExists("master_config_v2")) {
            LOGI("Table 'master_config_v2' does not exist. Creating it.");
            if (db_.tableExists("master_config_v1")) {
                 LOGI("Old 'master_config_v1' table found. Dropping it.");
                 db_.exec("DROP TABLE master_config_v1;");
            }
            db_.exec(R"(
                CREATE TABLE master_config_v2 (
                    key TEXT PRIMARY KEY,
                    value INTEGER NOT NULL
                )
            )");
            db_.exec("INSERT OR IGNORE INTO master_config_v2 (key, value) VALUES ('standard_timeout_sec', 90)");
            db_.exec("INSERT OR IGNORE INTO master_config_v2 (key, value) VALUES ('is_timed_unfreeze_enabled', 1)");
            db_.exec("INSERT OR IGNORE INTO master_config_v2 (key, value) VALUES ('timed_unfreeze_interval_sec', 1800)");
        }
    } catch (const std::exception& e) {
        LOGE("Database initialization failed: %s", e.what());
    }
}

std::optional<MasterConfig> DatabaseManager::get_master_config() {
    try {
        MasterConfig config;
        SQLite::Statement query(db_, "SELECT key, value FROM master_config_v2");
        while (query.executeStep()) {
            std::string key = query.getColumn(0).getString();
            int value = query.getColumn(1).getInt();
            if (key == "standard_timeout_sec") {
                config.standard_timeout_sec = value;
            } else if (key == "is_timed_unfreeze_enabled") {
                config.is_timed_unfreeze_enabled = (value != 0);
            } else if (key == "timed_unfreeze_interval_sec") {
                config.timed_unfreeze_interval_sec = value;
            }
        }
        return config;
    } catch (const std::exception& e) {
        LOGE("Failed to get master config: %s", e.what());
    }
    return std::nullopt;
}

bool DatabaseManager::set_master_config(const MasterConfig& config) {
    try {
        SQLite::Transaction transaction(db_);
        
        db_.exec("INSERT OR REPLACE INTO master_config_v2 (key, value) VALUES ('standard_timeout_sec', " + std::to_string(config.standard_timeout_sec) + ")");
        db_.exec("INSERT OR REPLACE INTO master_config_v2 (key, value) VALUES ('is_timed_unfreeze_enabled', " + std::to_string(config.is_timed_unfreeze_enabled ? 1 : 0) + ")");
        db_.exec("INSERT OR REPLACE INTO master_config_v2 (key, value) VALUES ('timed_unfreeze_interval_sec', " + std::to_string(config.timed_unfreeze_interval_sec) + ")");

        transaction.commit();
        return true;
    } catch (const std::exception& e) {
        LOGE("Failed to set master config: %s", e.what());
        return false;
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