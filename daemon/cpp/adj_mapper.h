// daemon/cpp/adj_mapper.h
#ifndef CERBERUS_ADJ_MAPPER_H
#define CERBERUS_ADJ_MAPPER_H

#include <string>
#include <vector>
#include <nlohmann/json.hpp>
#include <optional>

using json = nlohmann::json;

class AdjMapper {
public:
    struct AdjRule {
        enum class Type { UNKNOWN, LINEAR, SIGMOID };

        int source_min;
        int source_max;
        Type type = Type::UNKNOWN;

        // For linear
        int target_min;
        int target_max;

        // For sigmoid
        double sigmoid_L; // target_max - target_min
        double sigmoid_k; // steepness
        double sigmoid_x0; // midpoint
        double sigmoid_D; // target_min
    };

    explicit AdjMapper(const std::string& config_path);
    void load_rules();
    int map_adj(int original_adj) const;

private:
    void parse_rules(const json& j);
    void load_default_rules();

    std::string config_path_;
    std::vector<AdjRule> rules_;
    AdjRule default_rule_;
};

#endif // CERBERUS_ADJ_MAPPER_H