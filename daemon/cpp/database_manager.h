// daemon/cpp/database_manager.h
#ifndef CERBERUS_DATABASE_MANAGER_H
#define CERBERUS_DATABASE_MANAGER_H

#include <string>
#include <vector>
#include <optional>
#include <SQLiteCpp/Database.h>
#include <SQLiteCpp/Statement.h>
#include <SQLiteCpp/Transaction.h>

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
    // [核心新增] 添加新的豁免标志
    bool force_playback_exemption = false;
    bool force_network_exemption = false;
    bool force_location_exemption = false;
    bool allow_timed_unfreeze = true; // 默认允许
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
    void initialize_database();
    SQLite::Database db_;
};

#endif //CERBERUS_DATABASE_MANAGER_H