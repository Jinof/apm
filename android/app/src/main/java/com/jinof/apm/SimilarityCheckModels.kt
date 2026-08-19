package com.jinof.apm

enum class SimilarityCheckMode(val displayName: String) {
    INCREMENTAL("增量检查"),
    RECENT("近期检查"),
    FULL("全量检查"),
}

enum class SimilarityRecentUnit(
    val displayName: String,
    internal val millis: Long,
) {
    HOURS("小时", 60L * 60L * 1_000L),
    DAYS("天", 24L * 60L * 60L * 1_000L),
    WEEKS("周", 7L * 24L * 60L * 60L * 1_000L),
}

data class SimilarityCheckRequest private constructor(
    val mode: SimilarityCheckMode,
    val recentAmount: Int?,
    val recentUnit: SimilarityRecentUnit?,
    val requestedAtMillis: Long,
    val recentCutoffMillis: Long?,
) {
    init {
        require(requestedAtMillis >= 0L) { "检查时间无效" }
        if (mode == SimilarityCheckMode.RECENT) {
            require(recentAmount != null && recentAmount in MIN_RECENT_AMOUNT..MAX_RECENT_AMOUNT) {
                "近期时间数量必须在 $MIN_RECENT_AMOUNT 到 $MAX_RECENT_AMOUNT 之间"
            }
            require(recentUnit != null && recentCutoffMillis != null && recentCutoffMillis >= 0L) {
                "近期检查必须提供有效时间单位和范围"
            }
        } else {
            require(recentAmount == null && recentUnit == null && recentCutoffMillis == null) {
                "只有近期检查可以提供时间范围"
            }
        }
    }

    val displayName: String = when (mode) {
        SimilarityCheckMode.INCREMENTAL -> "增量检查"
        SimilarityCheckMode.RECENT -> "最近 $recentAmount ${recentUnit!!.displayName}"
        SimilarityCheckMode.FULL -> "全量检查"
    }

    companion object {
        const val MIN_RECENT_AMOUNT = 1
        const val MAX_RECENT_AMOUNT = 9_999

        fun incremental(requestedAtMillis: Long = System.currentTimeMillis()) =
            SimilarityCheckRequest(
                mode = SimilarityCheckMode.INCREMENTAL,
                recentAmount = null,
                recentUnit = null,
                requestedAtMillis = requestedAtMillis,
                recentCutoffMillis = null,
            )

        fun recent(
            amount: Int,
            unit: SimilarityRecentUnit,
            requestedAtMillis: Long = System.currentTimeMillis(),
        ): SimilarityCheckRequest {
            require(amount in MIN_RECENT_AMOUNT..MAX_RECENT_AMOUNT) {
                "近期时间数量必须在 $MIN_RECENT_AMOUNT 到 $MAX_RECENT_AMOUNT 之间"
            }
            val durationMillis = Math.multiplyExact(amount.toLong(), unit.millis)
            val cutoffMillis = Math.subtractExact(requestedAtMillis, durationMillis)
            require(cutoffMillis >= 0L) { "近期时间范围超出有效日期" }
            return SimilarityCheckRequest(
                mode = SimilarityCheckMode.RECENT,
                recentAmount = amount,
                recentUnit = unit,
                requestedAtMillis = requestedAtMillis,
                recentCutoffMillis = cutoffMillis,
            )
        }

        fun full(requestedAtMillis: Long = System.currentTimeMillis()) =
            SimilarityCheckRequest(
                mode = SimilarityCheckMode.FULL,
                recentAmount = null,
                recentUnit = null,
                requestedAtMillis = requestedAtMillis,
                recentCutoffMillis = null,
            )
    }
}

data class VisualCheckSelection(
    val photos: List<PendingPhoto>,
    val excludedWithoutCaptureTime: Int = 0,
)
