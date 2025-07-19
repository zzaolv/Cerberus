// daemon/cpp/database_manager.h
#ifndef CERBERUS_DATABASE_MANAGER_H
#define CERBERUS_DATABASE_MANAGER_H

#include <string>
#include <vector>
#include <optional>
#include <SQLiteCpp/Database.h>
#include <SQLiteCpp/Statement.h>
#include <SQLiteCpp/Transaction.h>

// 应用策略等级，严格与文档和UI模型对应
enum class AppPolicy {
    EXEMPTED = 0,   // 自由后台 (豁免)
    IMPORTANT = 1,  // 重要
    STANDARD = 2,   // 智能
    STRICT = 3      // 严格
};

// 应用的持久化配置数据结构
struct AppConfig {
    std::string package_name;
    int user_id = 0; // [FIX] 增加 user_id 以支持分身应用
    AppPolicy policy = AppPolicy::EXEMPTED; // 默认值应为豁免，符合用户自选原则
    bool force_playback_exempt = false;
    bool force_network_exempt = false;
};

class DatabaseManager {
public:
    explicit DatabaseManager(const std::string& db_path);

    // [FIX] 修改函数签名以支持分身
    std::optional<AppConfig> get_app_config(const std::string& package_name, int user_id);
    
    // 设置/更新单个应用的配置
    bool set_app_config(const AppConfig& config);
    
    // 获取所有已配置应用的列表
    std::vector<AppConfig> get_all_app_configs();

private:
    void initialize_database();
    SQLite::Database db_;
};

#endif //CERBERUS_DATABASE_MANAGER_H