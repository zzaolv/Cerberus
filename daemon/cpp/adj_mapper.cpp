// daemon/cpp/adj_mapper.cpp
#include "adj_mapper.h"
#include <android/log.h>
#include <fstream>
#include <cmath>
#include <algorithm>

#define LOG_TAG "cerberusd_adj_mapper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

AdjMapper::AdjMapper(const std::string& config_path) : config_path_(config_path) {
    // 定义一个万能的默认规则，确保任何adj都有返回值
    default_rule_ = {
        .source_min = -1000, .source_max = 1001, .type = AdjRule::Type::LINEAR,
        .target_min = 0, .target_max = 200
    };
    load_rules();
}

void AdjMapper::load_rules() {
    std::ifstream ifs(config_path_);
    if (!ifs.is_open()) {
        LOGW("adj_rules.json not found at '%s'. Loading default rules.", config_path_.c_str());
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

void AdjMapper::load_default_rules() {
    rules_.clear();
    // 复制您在需求中提供的默认规则
    json default_json = R"({
      "rules": [
        { "source_range": [-1000, 0], "type": "linear", "target_range": [-1000, -900] },
        { "source_range": [1, 200], "type": "linear", "target_range": [1, 10] },
        { "source_range": [201, 899], "type": "sigmoid", "params": { "target_min": 11, "target_max": 20, "steepness": 0.02, "midpoint": 500 } },
        { "source_range": [900, 1001], "type": "linear", "target_range": [21, 30] }
      ]
    })"_json;
    parse_rules(default_json);
}

void AdjMapper::parse_rules(const json& j) {
    rules_.clear();
    if (!j.contains("rules") || !j["rules"].is_array()) {
        LOGE("JSON is missing 'rules' array. Using fallback.");
        return;
    }

    for (const auto& item : j["rules"]) {
        try {
            AdjRule rule;
            rule.source_min = item.at("source_range").at(0).get<int>();
            rule.source_max = item.at("source_range").at(1).get<int>();
            std::string type_str = item.at("type").get<std::string>();

            if (type_str == "linear") {
                rule.type = AdjRule::Type::LINEAR;
                rule.target_min = item.at("target_range").at(0).get<int>();
                rule.target_max = item.at("target_range").at(1).get<int>();
            } else if (type_str == "sigmoid") {
                rule.type = AdjRule::Type::SIGMOID;
                const auto& params = item.at("params");
                double target_min = params.at("target_min").get<double>();
                double target_max = params.at("target_max").get<double>();
                rule.sigmoid_L = target_max - target_min;
                rule.sigmoid_k = params.at("steepness").get<double>();
                rule.sigmoid_x0 = params.at("midpoint").get<double>();
                rule.sigmoid_D = target_min;
            } else {
                continue;
            }
            rules_.push_back(rule);
        } catch (const json::exception& e) {
            LOGW("Skipping invalid rule: %s", e.what());
        }
    }
    // 按source_min排序，便于查找
    std::sort(rules_.begin(), rules_.end(), [](const auto& a, const auto& b) {
        return a.source_min < b.source_min;
    });
}

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
            // Sigmoid: D + L / (1 + e^(-k * (x - x0)))
            result = selected_rule->sigmoid_D + selected_rule->sigmoid_L / (1.0 + std::exp(-selected_rule->sigmoid_k * (original_adj - selected_rule->sigmoid_x0)));
            break;
        }
        default:
            return 100; // Fallback
    }

    return static_cast<int>(std::round(result));
}