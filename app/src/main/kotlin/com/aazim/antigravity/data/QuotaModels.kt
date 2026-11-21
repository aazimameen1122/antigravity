package com.aazim.antigravity.data

import com.aazim.antigravity.service.CliModel

data class QuotaBucket(
    val bucketId: String,
    val displayName: String,
    val window: String = "",
    val resetTime: String? = null,
    val description: String = "",
    val remainingFraction: Double = 0.0,
    val disabled: Boolean = false,
    val subtext: String? = null
) {
    val percentage: Int get() = kotlin.math.round(remainingFraction * 100.0).toInt().coerceIn(0, 100)
}

enum class ModelAvailability {
    AVAILABLE,
    UNAVAILABLE,
    UNKNOWN
}

data class AvailableModelInfo(
    val modelId: String,
    val displayName: String,
    val provider: String,
    val remainingFraction: Double? = null,
    val resetTime: String? = null,
    val availability: ModelAvailability = ModelAvailability.UNKNOWN
) {
    val percentage: Int? get() = remainingFraction?.let { kotlin.math.round(it * 100.0).toInt().coerceIn(0, 100) }

    fun matches(query: String): Boolean {
        return modelId.equals(query, ignoreCase = true) ||
                displayName.equals(query, ignoreCase = true) ||
                (query.contains("Flash", ignoreCase = true) && (modelId.contains("Flash", ignoreCase = true) || displayName.contains("Flash", ignoreCase = true))) ||
                (query.contains("Argon", ignoreCase = true) && (modelId.contains("Argon", ignoreCase = true) || displayName.contains("Argon", ignoreCase = true))) ||
                (query.contains("Carbon", ignoreCase = true) && (modelId.contains("Carbon", ignoreCase = true) || displayName.contains("Carbon", ignoreCase = true))) ||
                (query.contains("Sonnet", ignoreCase = true) && (modelId.contains("Sonnet", ignoreCase = true) || displayName.contains("Sonnet", ignoreCase = true) || modelId.equals("Claude", ignoreCase = true))) ||
                (query.contains("Opus", ignoreCase = true) && (modelId.contains("Opus", ignoreCase = true) || displayName.contains("Opus", ignoreCase = true) || modelId.equals("Claude", ignoreCase = true))) ||
                (query.contains("GPT", ignoreCase = true) && (modelId.contains("GPT", ignoreCase = true) || displayName.contains("GPT", ignoreCase = true)))
    }
}

data class QuotaGroup(
    val displayName: String,
    val description: String? = null,
    val sharedModels: List<String> = emptyList(),
    val buckets: List<QuotaBucket> = emptyList()
)

data class UserQuotaSummary(
    val groups: List<QuotaGroup> = emptyList(),
    val availableModels: List<AvailableModelInfo> = emptyList(),
    val description: String? = null,

    val modelsFetchFailed: Boolean = false,

    val pickerModels: List<CliModel> = emptyList()
)

data class TierInfo(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val upgradeSubscriptionUri: String = "",
    val upgradeSubscriptionText: String = ""
)

data class CodeAssistTier(
    val currentTier: TierInfo = TierInfo(),
    val paidTier: TierInfo? = null
)

