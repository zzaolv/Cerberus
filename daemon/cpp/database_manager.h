// daemon/cpp/database_manager.h
#ifndef CERBERUS_DATABASE_MANAGER_H
#define CERBERUS_DATABASE_MANAGER_H

#include <string>
#include <vector>
#include <optional>
#include <SQLiteCpp/Database.h>
#include <SQLiteCpp/Statement.h>
#include <SQLiteCpp/Transaction.h>

// --- 核心修改：定义最新的数据库版本号 ---
// 每次您需要修改数据库结构时，请将此版本号 +1
const int DATABASE_VERSION = 4;


enum class AppPolicy {
    EXEMPTED = 0,
    IMPORTANT = 1,
    STANDARD = 2,
    STRICT = 3
};

struct AppConfig {
    std::string package_name;
    int user_id = 0; 
    AppPolicy policy = AppPolicy::STANDARD;
    bool force_playback_exemption = false;
    bool force_network_exemption = false;
    bool force_location_exemption = false;
    bool allow_timed_unfreeze = true;
};

struct MasterConfig {
    int standard_timeout_sec = 90;
    bool is_timed_unfreeze_enabled = true;
    int timed_unfreeze_interval_sec = 1800;
};

class DatabaseManager {
public:
    explicit DatabaseManager(const std::string& db_path);

    std::optional<MasterConfig> get_master_config();
    bool set_master_config(const MasterConfig& config);
    
    std::optional<AppConfig> get_app_config(const std::string& package_name, int user_id);
    bool set_app_config(const AppConfig& config);
    std::vector<AppConfig> get_all_app_configs();

    bool update_all_app_policies(const std::vector<AppConfig>& configs);

private:
    // --- 核心修改：重构初始化/升级逻辑 ---
    void initialize_and_migrate_database();
    int get_db_version();
    void set_db_version(int version);

    SQLite::Database db_;
};

#endif //CERBERUS_DATABASE_MANAGER_H