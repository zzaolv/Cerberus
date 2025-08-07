// daemon/cpp/adj_mapper.cpp
#include "adj_mapper.h"
#include <android/log.h>
#include <fstream>
#include <cmath>
#include <algorithm>

#define LOG_TAG "cerberusd_adj_mapper_v3_robust_parse" // 版本号更新
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// save_rules 函数保持不变
void AdjMapper::save_rules(const json& j) {
    try {
        std::ofstream ofs(config_path_);
        if (ofs.is_open()) {
            ofs << j.dump(2);
            LOGI("Saved default rules to '%s'.", config_path_.c_str());
        } else {
            LOGE("Failed to open '%s' for writing default rules.", config_path_.c_str());
        }
    } catch (const std::exception& e) {
        LOGE("Exception while saving default rules: %s", e.what());
    }
}

// 构造函数保持不变
AdjMapper::AdjMapper(const std::string& config_path) : config_path_(config_path) {
    default_rule_ = {
        .source_min = -1000, .source_max = 1001, .type = AdjRule::Type::LINEAR,
        .target_min = 0, .target_max = 200
    };
    load_rules();
}

// load_rules 函数保持不变
void AdjMapper::load_rules() {
    std::ifstream ifs(config_path_);
    if (!ifs.is_open()) {
        LOGW("adj_rules.json not found at '%s'. Loading default rules and creating file.", config_path_.c_str());
        load_default_rules(); 
        return;
    }

    try {
        json j = json::parse(ifs);
        parse_rules(j);
        LOGI("Successfully loaded and parsed %zu rules from '%s'.", rules_.size(), config_path_.c_str());
    } catch (const json::exception& e) {
        LOGE("Failed to parse adj_rules.json: %s. Loading default rules.", e.what());
        load_default_rules();
    }
}

// load_default_rules 函数保持不变
void AdjMapper::load_default_rules() {
    rules_.clear();
    json default_json = R"({
      "rules": [
        { "source_range": [-1000, 0], "type": "linear", "target_range": [-1000, -900] },
        { "source_range": [1, 200], "type": "linear", "target_range": [1, 10] },
        { "source_range": [201, 899], "type": "sigmoid", "params": { "target_min": -500, "target_max": -200, "steepness": 0.02, "midpoint": 500 } },
        { "source_range": [900, 1001], "type": "linear", "target_range": [21, 30] }
      ]
    })"_json;
    
    save_rules(default_json);
    parse_rules(default_json);
}

// [核心修复] 修改 parse_rules 函数，使用更安全的方式解析
void AdjMapper::parse_rules(const json& j) {
    rules_.clear();
    if (!j.contains("rules") || !j["rules"].is_array()) {
        LOGE("JSON is missing 'rules' array. Using fallback.");
        return;
    }

    for (const auto& item : j["rules"]) {
        try {
            AdjRule rule;
            // 使用 .value() 提供默认值，防止 key 不存在时崩溃
            rule.source_min = item.value("source_range", json::array({0,0}))[0].get<int>();
            rule.source_max = item.value("source_range", json::array({0,0}))[1].get<int>();
            std::string type_str = item.value("type", "unknown");

            if (type_str == "linear") {
                rule.type = AdjRule::Type::LINEAR;
                // 使用 .contains() 检查 key 是否存在，然后再访问
                if (item.contains("target_range") && item["target_range"].is_array() && item["target_range"].size() == 2) {
                    rule.target_min = item["target_range"][0].get<int>();
                    rule.target_max = item["target_range"][1].get<int>();
                } else {
                    // 如果不存在或格式不对，提供一个安全的默认值
                    rule.target_min = 0;
                    rule.target_max = 0;
                    LOGW("Linear rule is missing or has invalid 'target_range'. Using [0,0].");
                }
            } else if (type_str == "sigmoid") {
                rule.type = AdjRule::Type::SIGMOID;
                 // 使用 .contains() 检查 key 是否存在
                if (item.contains("params") && item["params"].is_object()) {
                    const auto& params = item["params"];
                    // 使用 .value() 安全地获取每个参数
                    double target_min = params.value("target_min", 0.0);
                    double target_max = params.value("target_max", 0.0);
                    rule.sigmoid_L = target_max - target_min;
                    rule.sigmoid_k = params.value("steepness", 0.0);
                    rule.sigmoid_x0 = params.value("midpoint", 0.0);
                    rule.sigmoid_D = target_min;
                } else {
                    LOGW("Sigmoid rule is missing 'params' object. Using default sigmoid params.");
                    rule.sigmoid_L = 0;
                    rule.sigmoid_k = 0;
                    rule.sigmoid_x0 = 0;
                    rule.sigmoid_D = 0;
                }
            } else {
                LOGW("Unknown rule type '%s', skipping.", type_str.c_str());
                continue;
            }
            rules_.push_back(rule);
        } catch (const json::exception& e) {
            LOGW("Skipping invalid rule due to JSON parsing error: %s", e.what());
        }
    }
    // 排序逻辑保持不变
    std::sort(rules_.begin(), rules_.end(), [](const auto& a, const auto& b) {
        return a.source_min < b.source_min;
    });
}


// map_adj 函数保持不变
int AdjMapper::map_adj(int original_adj) const {
    const AdjRule* selected_rule = &default_rule_;

    for (const auto& rule : rules_) {
        if (original_adj >= rule.source_min && original_adj <= rule.source_max) {
            selected_rule = &rule;
            break;
        }
    }

    double result;
    switch (selected_rule->type) {
        case AdjRule::Type::LINEAR: {
            double source_range = selected_rule->source_max - selected_rule->source_min;
            double target_range = selected_rule->target_max - selected_rule->target_min;
            if (source_range == 0) {
                result = selected_rule->target_min;
            } else {
                result = selected_rule->target_min + ((double)(original_adj - selected_rule->source_min) / source_range) * target_range;
            }
            break;
        }
        case AdjRule::Type::SIGMOID: {
            result = selected_rule->sigmoid_D + selected_rule->sigmoid_L / (1.0 + std::exp(-selected_rule->sigmoid_k * (original_adj - selected_rule->sigmoid_x0)));
            break;
        }
        default:
            return 100;
    }

    return static_cast<int>(std::round(result));
}