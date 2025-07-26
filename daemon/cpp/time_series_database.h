// daemon/cpp/time_series_database.h
#ifndef CERBERUS_TIME_SERIES_DATABASE_H
#define CERBERUS_TIME_SERIES_DATABASE_H

#include <vector>
#include <deque>
#include <mutex>
#include <chrono>
#include <nlohmann/json.hpp>
#include <memory>

using json = nlohmann::json;

// [新增] 详细的指标记录结构体
struct MetricsRecord {
    long long timestamp_ms;
    float cpu_usage_percent = 0.0f;
    long mem_used_kb = 0;
    int battery_level = -1;
    float battery_temp_celsius = 0.0f; // 已处理为摄氏度
    float battery_power_watt = 0.0f;
    bool is_charging = false;
    bool is_screen_on = true;
    bool is_audio_playing = false;
    bool is_location_active = false;

    json to_json() const;
};

class TimeSeriesDatabase : public std::enable_shared_from_this<TimeSeriesDatabase> {
public:
    // [新增] 获取单例实例
    static std::shared_ptr<TimeSeriesDatabase> get_instance(size_t max_size = 900); // 默认30分钟@2s
    ~TimeSeriesDatabase() = default;

    TimeSeriesDatabase(const TimeSeriesDatabase&) = delete;
    TimeSeriesDatabase& operator=(const TimeSeriesDatabase&) = delete;

    // [新增] 添加一条新的指标记录
    void add_record(const MetricsRecord& record);
    
    // [新增] 获取指定时间范围内的记录
    std::vector<MetricsRecord> get_records_since(long long timestamp_ms) const;

    // [新增] 获取所有历史记录 (用于UI初始化)
    std::vector<MetricsRecord> get_all_records() const;
    
    // [新增] 获取最新的一条记录
    std::optional<MetricsRecord> get_latest_record() const;

private:
    explicit TimeSeriesDatabase(size_t max_size);

    static std::shared_ptr<TimeSeriesDatabase> instance_;
    static std::mutex instance_mutex_;

    size_t max_size_;
    std::deque<MetricsRecord> records_;
    mutable std::mutex db_mutex_;
};

#endif // CERBERUS_TIME_SERIES_DATABASE_H