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
// [关键] 提升为全局可访问，方便 StateManager 使用
enum class AppPolicy {
    EXEMPTED = 0,   // 豁免 (永不冻结)
    IMPORTANT = 1,  // 重要 (行为同豁免)
    STANDARD = 2,   // 智能 (长延时后冻结)
    STRICT = 3      // 严格 (短延时后冻结)
};

// 应用的持久化配置数据结构
struct AppConfig {
    std::string package_name;
    int user_id = 0; 
    AppPolicy policy = AppPolicy::STANDARD; // 默认改为智能，更符合用户预期
    // [简化] 移除 force_playback_exempt 等选项，这些高级豁免逻辑应由Probe判断
};

// [新] 全局配置结构体
struct MasterConfig {
    int standard_timeout_sec = 90; // 智能模式默认超时90秒
};


class DatabaseManager {
public:
    explicit DatabaseManager(const std::string& db_path);

    // [新] MasterConfig的读写接口
    std::optional<MasterConfig> get_master_config();
    bool set_master_config(const MasterConfig& config);
    
    std::optional<AppConfig> get_app_config(const std::string& package_name, int user_id);
    bool set_app_config(const AppConfig& config);
    bool clear_all_policies();
    std::vector<AppConfig> get_all_app_configs();

private:
    void initialize_database();
    SQLite::Database db_;
};

#endif //CERBERUS_DATABASE_MANAGER_H