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
    // [修改] 删除此函数，其逻辑将被新函数替代
    // bool clear_all_policies(); 
    std::vector<AppConfig> get_all_app_configs();

    // [新增] 用于原子化更新所有策略的事务函数
    bool update_all_app_policies(const std::vector<AppConfig>& configs);

private:
    void initialize_database();
    SQLite::Database db_;
};

#endif //CERBERUS_DATABASE_MANAGER_H