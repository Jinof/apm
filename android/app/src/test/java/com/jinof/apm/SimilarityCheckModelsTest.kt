package com.jinof.apm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SimilarityCheckModelsTest {
    @Test
    fun incrementalAndFullHaveNoImplicitRecentWindow() {
        val incremental = SimilarityCheckRequest.incremental(2_000_000_000L)
        val full = SimilarityCheckRequest.full(2_000_000_000L)

        assertEquals(SimilarityCheckMode.INCREMENTAL, incremental.mode)
        assertNull(incremental.recentAmount)
        assertNull(incremental.recentUnit)
        assertNull(incremental.recentCutoffMillis)
        assertEquals(SimilarityCheckMode.FULL, full.mode)
        assertNull(full.recentCutoffMillis)
    }

    @Test
    fun recentWindowUsesExactUserAmountAndUnit() {
        val requestedAt = 2_000_000_000L

        val request = SimilarityCheckRequest.recent(
            amount = 3,
            unit = SimilarityRecentUnit.DAYS,
            requestedAtMillis = requestedAt,
        )

        assertEquals(SimilarityCheckMode.RECENT, request.mode)
        assertEquals(3, request.recentAmount)
        assertEquals(SimilarityRecentUnit.DAYS, request.recentUnit)
        assertEquals(requestedAt - 3L * 24L * 60L * 60L * 1_000L, request.recentCutoffMillis)
        assertEquals("最近 3 天", request.displayName)
    }

    @Test
    fun invalidRecentInputNeverFallsBackToAnotherMode() {
        assertThrows(IllegalArgumentException::class.java) {
            SimilarityCheckRequest.recent(0, SimilarityRecentUnit.HOURS, 2_000_000_000L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SimilarityCheckRequest.recent(10_000, SimilarityRecentUnit.WEEKS, Long.MAX_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SimilarityCheckRequest.recent(1, SimilarityRecentUnit.WEEKS, 1_000L)
        }
    }
}
