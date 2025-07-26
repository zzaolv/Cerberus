// daemon/cpp/time_series_database.cpp
#include "time_series_database.h"
#include "uds_server.h"

// [新增] 全局UdsServer引用，用于实时数据广播
extern std::unique_ptr<UdsServer> g_server;

std::shared_ptr<TimeSeriesDatabase> TimeSeriesDatabase::instance_ = nullptr;
std::mutex TimeSeriesDatabase::instance_mutex_;

// [新增] MetricsRecord 序列化为 JSON
json MetricsRecord::to_json() const {
    return json{
        {"timestamp", timestamp_ms},
        {"cpu_usage_percent", cpu_usage_percent},
        {"mem_used_kb", mem_used_kb},
        {"battery_level", battery_level},
        {"battery_temp_celsius", battery_temp_celsius},
        {"battery_power_watt", battery_power_watt},
        {"is_charging", is_charging},
        {"is_screen_on", is_screen_on},
        {"is_audio_playing", is_audio_playing},
        {"is_location_active", is_location_active}
    };
}

std::shared_ptr<TimeSeriesDatabase> TimeSeriesDatabase::get_instance(size_t max_size) {
    std::lock_guard<std::mutex> lock(instance_mutex_);
    if (!instance_) {
        struct make_shared_enabler : public TimeSeriesDatabase {
            make_shared_enabler(size_t size) : TimeSeriesDatabase(size) {}
        };
        instance_ = std::make_shared<make_shared_enabler>(max_size);
    }
    return instance_;
}

TimeSeriesDatabase::TimeSeriesDatabase(size_t max_size) : max_size_(max_size) {}

void TimeSeriesDatabase::add_record(const MetricsRecord& record) {
    {
        std::lock_guard<std::mutex> lock(db_mutex_);
        if (records_.size() >= max_size_) {
            records_.pop_front();
        }
        records_.push_back(record);
    }
    
    // [新增] 广播新的记录给UI客户端
    if (g_server) {
        g_server->broadcast_message(json{
            {"type", "stream.new_stats_record"},
            {"payload", record.to_json()}
        }.dump());
    }
}

std::vector<MetricsRecord> TimeSeriesDatabase::get_records_since(long long timestamp_ms) const {
    std::vector<MetricsRecord> result;
    std::lock_guard<std::mutex> lock(db_mutex_);
    for (const auto& record : records_) {
        if (record.timestamp_ms >= timestamp_ms) {
            result.push_back(record);
        }
    }
    return result;
}

std::vector<MetricsRecord> TimeSeriesDatabase::get_all_records() const {
    std::lock_guard<std::mutex> lock(db_mutex_);
    return std::vector<MetricsRecord>(records_.begin(), records_.end());
}

std::optional<MetricsRecord> TimeSeriesDatabase::get_latest_record() const {
    std::lock_guard<std::mutex> lock(db_mutex_);
    if (records_.empty()) {
        return std::nullopt;
    }
    return records_.back();
}