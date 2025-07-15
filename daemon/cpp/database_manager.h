// daemon/cpp/database_manager.h
#ifndef CERBERUS_DATABASE_MANAGER_H
#define CERBERUS_DATABASE_MANAGER_H

#include <string>
#include <vector>
#include <optional>
#include <SQLiteCpp/Database.h>
#include <SQLiteCpp/Statement.h>
#include <SQLiteCpp/Transaction.h>

// 对应文档中的应用策略 (Policy Tiers)
enum class AppPolicy {
    EXEMPTED = 0,   // 自由后台
    IMPORTANT = 1,  // 重要
    STANDARD = 2,   // 智能
    STRICT = 3      // 严格
};

// 应用的持久化配置
struct AppConfig {
    std::string package_name;
    AppPolicy policy = AppPolicy::STANDARD;
    bool force_playback_exempt = false;
    bool force_network_exempt = false;
};

class DatabaseManager {
public:
    explicit DatabaseManager(const std::string& db_path);

    std::optional<AppConfig> get_app_config(const std::string& package_name);
    bool set_app_config(const AppConfig& config);
    std::vector<AppConfig> get_all_app_configs();

private:
    void initialize_database();
    SQLite::Database db_;
};

#endif //CERBERUS_DATABASE_MANAGER_H