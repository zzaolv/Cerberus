// app/src/main/java/com/crfzit/crfzit/ui/settings/more/OomRuleModels.kt
package com.crfzit.crfzit.ui.settings.more

import com.google.gson.annotations.SerializedName
import java.util.UUID

// 用于整个 adj_rules.json 文件的顶层结构
data class AdjRulesFile(
    val rules: List<OomRule> = emptyList()
)

// 代表每一条规则的数据模型
data class OomRule(
    // 使用 UUID 为每个规则实例提供一个唯一的、在 UI 中稳定的 ID
    val id: String = UUID.randomUUID().toString(),

    @SerializedName("source_range")
    val sourceRange: List<Int> = listOf(0, 0),

    val type: String = "linear", // "linear" or "sigmoid"

    @SerializedName("target_range")
    val targetRange: List<Int>? = null, // 仅用于 linear 类型

    val params: SigmoidParams? = null // 仅用于 sigmoid 类型
)

// Sigmoid 类型规则的参数
data class SigmoidParams(
    @SerializedName("target_min")
    val targetMin: Double = 0.0,
    @SerializedName("target_max")
    val targetMax: Double = 0.0,
    val steepness: Double = 0.0,
    val midpoint: Double = 0.0
)