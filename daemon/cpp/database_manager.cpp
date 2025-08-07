// daemon/cpp/database_manager.cpp
#include "database_manager.h"
#include <android/log.h>
#include <filesystem>

#define LOG_TAG "cerberusd_db_v11_exempt" // 版本更新
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

DatabaseManager::DatabaseManager(const std::string& db_path)
    : db_(db_path, SQLite::OPEN_READWRITE | SQLite::OPEN_CREATE) {
    LOGI("Database opened at %s", db_path.c_str());
    initialize_database();
}

void DatabaseManager::initialize_database() {
    try {
        // [核心修改] 升级表结构，使用新表名 app_policies_v4
        if (!db_.tableExists("app_policies_v4")) {
            LOGI("Table 'app_policies_v4' does not exist. Creating it.");
            if (db_.tableExists("app_policies_v3")) {
                LOGI("Old 'app_policies_v3' table found. Dropping it.");
                db_.exec("DROP TABLE app_policies_v3;");
            }
            db_.exec(R"(
                CREATE TABLE app_policies_v4 (
                    package_name TEXT NOT NULL,
                    user_id INTEGER NOT NULL,
                    policy INTEGER NOT NULL DEFAULT 0,
                    force_playback_exemption INTEGER NOT NULL DEFAULT 0,
                    force_network_exemption INTEGER NOT NULL DEFAULT 0,
                    force_location_exemption INTEGER NOT NULL DEFAULT 0,
                    allow_timed_unfreeze INTEGER NOT NULL DEFAULT 1,
                    PRIMARY KEY (package_name, user_id)
                )
            )");
        }

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
        // [核心修改] 查询新表和新字段
        SQLite::Statement query(db_, "SELECT policy, force_playback_exemption, force_network_exemption, force_location_exemption, allow_timed_unfreeze FROM app_policies_v4 WHERE package_name = ? AND user_id = ?");
        query.bind(1, package_name);
        query.bind(2, user_id);

        if (query.executeStep()) {
            AppConfig config;
            config.package_name = package_name;
            config.user_id = user_id;
            config.policy = static_cast<AppPolicy>(query.getColumn(0).getInt());
            config.force_playback_exemption = query.getColumn(1).getInt() != 0;
            config.force_network_exemption = query.getColumn(2).getInt() != 0;
            config.force_location_exemption = query.getColumn(3).getInt() != 0;
            config.allow_timed_unfreeze = query.getColumn(4).getInt() != 0;
            return config;
        }
    } catch (const std::exception& e) {
        LOGE("Failed to get app config for '%s' (user %d): %s", package_name.c_str(), user_id, e.what());
    }
    return std::nullopt;
}

bool DatabaseManager::set_app_config(const AppConfig& config) {
    try {
        // [核心修改] 更新 INSERT/UPDATE 语句
        SQLite::Statement query(db_, R"(
            INSERT INTO app_policies_v4 (package_name, user_id, policy, force_playback_exemption, force_network_exemption, force_location_exemption, allow_timed_unfreeze)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(package_name, user_id) DO UPDATE SET
                policy = excluded.policy,
                force_playback_exemption = excluded.force_playback_exemption,
                force_network_exemption = excluded.force_network_exemption,
                force_location_exemption = excluded.force_location_exemption,
                allow_timed_unfreeze = excluded.allow_timed_unfreeze
        )");
        query.bind(1, config.package_name);
        query.bind(2, config.user_id);
        query.bind(3, static_cast<int>(config.policy));
        query.bind(4, config.force_playback_exemption ? 1 : 0);
        query.bind(5, config.force_network_exemption ? 1 : 0);
        query.bind(6, config.force_location_exemption ? 1 : 0);
        query.bind(7, config.allow_timed_unfreeze ? 1 : 0);
        
        return query.exec() > 0;
    } catch (const std::exception& e) {
        LOGE("Failed to set app config for '%s' (user %d): %s", config.package_name.c_str(), config.user_id, e.what());
        return false;
    }
}

bool DatabaseManager::update_all_app_policies(const std::vector<AppConfig>& configs) {
    try {
        SQLite::Transaction transaction(db_);

        db_.exec("DELETE FROM app_policies_v4");

        SQLite::Statement insert_query(db_, R"(
            INSERT INTO app_policies_v4 (package_name, user_id, policy, force_playback_exemption, force_network_exemption, force_location_exemption, allow_timed_unfreeze) VALUES (?, ?, ?, ?, ?, ?, ?)
        )");

        for (const auto& config : configs) {
            insert_query.bind(1, config.package_name);
            insert_query.bind(2, config.user_id);
            insert_query.bind(3, static_cast<int>(config.policy));
            insert_query.bind(4, config.force_playback_exemption ? 1 : 0);
            insert_query.bind(5, config.force_network_exemption ? 1 : 0);
            insert_query.bind(6, config.force_location_exemption ? 1 : 0);
            insert_query.bind(7, config.allow_timed_unfreeze ? 1 : 0);
            insert_query.exec();
            insert_query.reset();
        }

        transaction.commit();
        return true;

    } catch (const std::exception& e) {
        LOGE("Failed to update all app policies in transaction: %s", e.what());
        return false;
    }
}

std::vector<AppConfig> DatabaseManager::get_all_app_configs() {
    std::vector<AppConfig> configs;
    try {
        SQLite::Statement query(db_, "SELECT package_name, user_id, policy, force_playback_exemption, force_network_exemption, force_location_exemption, allow_timed_unfreeze FROM app_policies_v4");
        while (query.executeStep()) {
            AppConfig config;
            config.package_name = query.getColumn(0).getString();
            config.user_id = query.getColumn(1).getInt();
            config.policy = static_cast<AppPolicy>(query.getColumn(2).getInt());
            config.force_playback_exemption = query.getColumn(3).getInt() != 0;
            config.force_network_exemption = query.getColumn(4).getInt() != 0;
            config.force_location_exemption = query.getColumn(5).getInt() != 0;
            config.allow_timed_unfreeze = query.getColumn(6).getInt() != 0;
            configs.push_back(config);
        }
    } catch (const std::exception& e) {
        LOGE("Failed to get all app configs: %s", e.what());
    }
    return configs;
}