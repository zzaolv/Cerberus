// daemon/cpp/time_series_database.h
#ifndef CERBERUS_TIME_SERIES_DATABASE_H
#define CERBERUS_TIME_SERIES_DATABASE_H

#include <vector>
#include <deque>
#include <mutex>
#include <chrono>
#include <nlohmann/json.hpp>
#include <memory>
#include <optional>

using json = nlohmann::json;

struct MetricsRecord {
    long long timestamp_ms;
    // [核心修改] total_cpu_usage_percent 用于仪表盘和旧逻辑
    float total_cpu_usage_percent = 0.0f; 
    // [核心新增] per_core_cpu_usage 用于新的统计图表
    std::vector<float> per_core_cpu_usage; 
    long mem_total_kb = 0;
    long mem_available_kb = 0;
    long swap_total_kb = 0;
    long swap_free_kb = 0;
    int battery_level = -1;
    float battery_temp_celsius = 0.0f;
    float battery_power_watt = 0.0f;
    bool is_charging = false;
    bool is_screen_on = true;
    bool is_audio_playing = false;
    bool is_location_active = false;

    json to_json() const;
};

class TimeSeriesDatabase : public std::enable_shared_from_this<TimeSeriesDatabase> {
public:
    static std::shared_ptr<TimeSeriesDatabase> get_instance(size_t max_size = 900);
    ~TimeSeriesDatabase() = default;

    TimeSeriesDatabase(const TimeSeriesDatabase&) = delete;
    TimeSeriesDatabase& operator=(const TimeSeriesDatabase&) = delete;

    void add_record(const MetricsRecord& record);
    std::vector<MetricsRecord> get_records_since(long long timestamp_ms) const;
    std::vector<MetricsRecord> get_all_records() const;
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