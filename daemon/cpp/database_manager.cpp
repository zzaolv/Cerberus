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
        // [核心修复] 数据库表结构升级，支持分身应用独立配置
        // 使用新表名 app_policies_v2，并使用 (package_name, user_id)作为复合主键
        if (!db_.tableExists("app_policies_v2")) {
            LOGI("Table 'app_policies_v2' does not exist. Creating it.");
            // 如果旧表存在，可以考虑数据迁移，这里为简化直接删除
            if (db_.tableExists("app_policies")) {
                 LOGI("Old 'app_policies' table found. Dropping it.");
                 db_.exec("DROP TABLE app_policies;");
            }
            // 新表结构与 AppConfig 结构体完全对应
            db_.exec(R"(
                CREATE TABLE app_policies_v2 (
                    package_name TEXT NOT NULL,
                    user_id INTEGER NOT NULL,
                    policy INTEGER NOT NULL DEFAULT 0,
                    force_playback_exempt INTEGER NOT NULL DEFAULT 0,
                    force_network_exempt INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (package_name, user_id)
                )
            )");
        }
    } catch (const std::exception& e) {
        LOGE("Database initialization failed: %s", e.what());
    }
}

// [核心修复] 修改函数实现以支持分身，查询时同时匹配 package_name 和 user_id
std::optional<AppConfig> DatabaseManager::get_app_config(const std::string& package_name, int user_id) {
    try {
        SQLite::Statement query(db_, "SELECT policy, force_playback_exempt, force_network_exempt FROM app_policies_v2 WHERE package_name = ? AND user_id = ?");
        query.bind(1, package_name);
        query.bind(2, user_id);

        if (query.executeStep()) {
            AppConfig config;
            config.package_name = package_name;
            config.user_id = user_id;
            config.policy = static_cast<AppPolicy>(query.getColumn(0).getInt());
            config.force_playback_exempt = query.getColumn(1).getInt() != 0;
            config.force_network_exempt = query.getColumn(2).getInt() != 0;
            return config;
        }
    } catch (const std::exception& e) {
        LOGE("Failed to get app config for '%s' (user %d): %s", package_name.c_str(), user_id, e.what());
    }
    return std::nullopt; // 未找到或发生错误
}

// [核心修复] 修改函数实现以支持分身，插入或更新时使用 user_id
bool DatabaseManager::set_app_config(const AppConfig& config) {
    try {
        SQLite::Statement query(db_, R"(
            INSERT INTO app_policies_v2 (package_name, user_id, policy, force_playback_exempt, force_network_exempt)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(package_name, user_id) DO UPDATE SET
                policy = excluded.policy,
                force_playback_exempt = excluded.force_playback_exempt,
                force_network_exempt = excluded.force_network_exempt
        )");
        query.bind(1, config.package_name);
        query.bind(2, config.user_id);
        query.bind(3, static_cast<int>(config.policy));
        query.bind(4, config.force_playback_exempt);
        query.bind(5, config.force_network_exempt);
        
        return query.exec() > 0;
    } catch (const std::exception& e) {
        LOGE("Failed to set app config for '%s' (user %d): %s", config.package_name.c_str(), config.user_id, e.what());
        return false;
    }
}

// [核心修复] 修改函数实现以支持分身，查询所有配置时，需要把 user_id 也一并查出
std::vector<AppConfig> DatabaseManager::get_all_app_configs() {
    std::vector<AppConfig> configs;
    try {
        SQLite::Statement query(db_, "SELECT package_name, user_id, policy, force_playback_exempt, force_network_exempt FROM app_policies_v2");
        while (query.executeStep()) {
            AppConfig config;
            config.package_name = query.getColumn(0).getString();
            config.user_id = query.getColumn(1).getInt();
            config.policy = static_cast<AppPolicy>(query.getColumn(2).getInt());
            config.force_playback_exempt = query.getColumn(3).getInt() != 0;
            config.force_network_exempt = query.getColumn(4).getInt() != 0;
            configs.push_back(config);
        }
    } catch (const std::exception& e) {
        LOGE("Failed to get all app configs: %s", e.what());
    }
    return configs;
}