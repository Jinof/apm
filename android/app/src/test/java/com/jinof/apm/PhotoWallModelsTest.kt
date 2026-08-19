package com.jinof.apm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class PhotoWallModelsTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun groupsOnlyByRealLocalCaptureDayAndLeavesUnknownLast() {
        val beforeMidnight = card(
            "b",
            ZonedDateTime.of(2026, 8, 15, 23, 59, 0, 0, zoneId).toInstant().toEpochMilli(),
        )
        val afterMidnight = card(
            "a",
            ZonedDateTime.of(2026, 8, 16, 0, 1, 0, 0, zoneId).toInstant().toEpochMilli(),
        )
        val unknown = card("c", null).copy(annotatedAt = "2099-01-01T00:00:00Z")

        val groups = PhotoWallOrganizer.groupByDay(
            listOf(unknown, beforeMidnight, afterMidnight, afterMidnight.copy(uri = "duplicate")),
            zoneId,
        )

        assertEquals(
            listOf(LocalDate.of(2026, 8, 16), LocalDate.of(2026, 8, 15), null),
            groups.map(PhotoDayGroup::date),
        )
        assertEquals(listOf("a", "b", "c"), groups.flatMap { it.photos }.map { it.photoId })
        assertNull(groups.last().date)
        assertEquals(listOf("c"), groups.last().photos.map { it.photoId })
    }

    @Test
    fun ordersNewestPhotosThenStablePhotoIdWithinEachDay() {
        val date = LocalDate.of(2026, 8, 16)
        fun millis(hour: Int) = date.atTime(hour, 0).atZone(zoneId).toInstant().toEpochMilli()

        val groups = PhotoWallOrganizer.groupByDay(
            listOf(card("z", millis(9)), card("b", millis(10)), card("a", millis(10))),
            zoneId,
        )

        assertEquals(listOf("a", "b", "z"), groups.single().photos.map { it.photoId })
    }

    @Test
    fun buildsSundayFirstAnnualDayGridWithEveryDateAndGitHubLevels() {
        val photos = buildList {
            add(card("one", captured(2026, 1, 1, 9)))
            repeat(2) { add(card("two-$it", captured(2026, 8, 2, 9 + it))) }
            repeat(4) { add(card("four-$it", captured(2026, 12, 31, 9 + it))) }
            repeat(5) { add(card("before-$it", captured(2025, 12, 31, 9 + it))) }
            repeat(5) { add(card("after-$it", captured(2027, 1, 1, 9 + it))) }
            add(card("unknown", null))
        }

        val heatmap = PhotoWallOrganizer.heatmap(2026, photos, zoneId)

        assertEquals(371, heatmap.dayCells.size)
        assertEquals(365, heatmap.dayCells.count(PhotoHeatmapDayCell::inYear))
        assertEquals(LocalDate.of(2025, 12, 28), heatmap.dayCells.first().date)
        assertEquals(LocalDate.of(2027, 1, 2), heatmap.dayCells.last().date)
        assertEquals(53, heatmap.weekCount)
        assertEquals(12, heatmap.monthCells.size)
        assertEquals(12, heatmap.monthLabels.size)
        assertEquals(53, heatmap.monthLabels.sumOf(PhotoHeatmapMonthLabel::spanWeeks))
        assertEquals(7, heatmap.totalCount)
        assertEquals(4, heatmap.maxDayCount)
        val days = heatmap.dayCells.associateBy(PhotoHeatmapDayCell::date)
        assertEquals(1, days.getValue(LocalDate.of(2026, 1, 1)).count)
        assertEquals(2, days.getValue(LocalDate.of(2026, 8, 2)).count)
        assertEquals(4, days.getValue(LocalDate.of(2026, 12, 31)).count)
        assertEquals(1, days.getValue(LocalDate.of(2026, 1, 1)).level)
        assertEquals(2, days.getValue(LocalDate.of(2026, 8, 2)).level)
        assertEquals(4, days.getValue(LocalDate.of(2026, 12, 31)).level)
        assertEquals(0, heatmap.dayCells.first().count)
        assertEquals(0, heatmap.dayCells.first().level)
        assertEquals(1, heatmap.monthLabels.first().month.monthValue)
        assertEquals(0, heatmap.monthLabels.first().startWeekIndex)
        assertEquals(12, heatmap.monthLabels.last().month.monthValue)
    }

    @Test
    fun aggregatesSundayWeeksExactlyAndClipsBoundaryWeeksToTheYear() {
        val photos = buildList {
            repeat(2) { add(card("jan1-$it", captured(2026, 1, 1, 9 + it))) }
            add(card("jan3", captured(2026, 1, 3, 9)))
            add(card("jan4", captured(2026, 1, 4, 9)))
            repeat(4) { add(card("dec31-$it", captured(2026, 12, 31, 9 + it))) }
            repeat(5) { add(card("outside-before-$it", captured(2025, 12, 31, 9 + it))) }
            repeat(5) { add(card("outside-after-$it", captured(2027, 1, 1, 9 + it))) }
        }

        val heatmap = PhotoWallOrganizer.heatmap(2026, photos, zoneId)

        assertEquals(53, heatmap.weekCells.size)
        assertEquals(LocalDate.of(2026, 1, 1), heatmap.weekCells.first().startDate)
        assertEquals(LocalDate.of(2026, 1, 3), heatmap.weekCells.first().endDate)
        assertEquals(3, heatmap.weekCells.first().count)
        assertEquals(3, heatmap.weekCells.first().level)
        assertEquals(LocalDate.of(2026, 1, 4), heatmap.weekCells[1].startDate)
        assertEquals(1, heatmap.weekCells[1].count)
        assertEquals(LocalDate.of(2026, 12, 27), heatmap.weekCells.last().startDate)
        assertEquals(LocalDate.of(2026, 12, 31), heatmap.weekCells.last().endDate)
        assertEquals(4, heatmap.weekCells.last().count)
        assertEquals(4, heatmap.weekCells.last().level)
        assertEquals(8, heatmap.totalCount)
        val leapYear = PhotoWallOrganizer.heatmap(2028, emptyList(), zoneId)
        assertEquals(54, leapYear.weekCount)
        assertEquals(366, leapYear.dayCells.count(PhotoHeatmapDayCell::inYear))
    }

    @Test
    fun aggregatesAllTwelveMonthsWithModeRelativeLevels() {
        val photos = buildList {
            add(card("jan", captured(2026, 1, 5, 9)))
            repeat(3) { add(card("aug-$it", captured(2026, 8, 15, 9 + it))) }
            repeat(4) { add(card("dec-$it", captured(2026, 12, 20, 9 + it))) }
            add(card("outside", captured(2025, 12, 31, 9)))
        }

        val heatmap = PhotoWallOrganizer.heatmap(2026, photos, zoneId)

        assertEquals(12, heatmap.monthCells.size)
        assertEquals(1, heatmap.monthCells[0].count)
        assertEquals(1, heatmap.monthCells[0].level)
        assertEquals(0, heatmap.monthCells[1].count)
        assertEquals(0, heatmap.monthCells[1].level)
        assertEquals(3, heatmap.monthCells[7].count)
        assertEquals(3, heatmap.monthCells[7].level)
        assertEquals(4, heatmap.monthCells[11].count)
        assertEquals(4, heatmap.monthCells[11].level)
        assertEquals(4, heatmap.maxMonthCount)
        assertEquals(8, heatmap.totalCount)
        assertEquals(4, PhotoWallOrganizer.MAX_HEAT_LEVEL)
    }

    @Test
    fun heatmapSelectionContainsOnlyItsInclusiveRange() {
        val selection = PhotoHeatmapSelection(
            granularity = PhotoHeatmapGranularity.WEEK,
            startDate = LocalDate.of(2026, 8, 9),
            endDate = LocalDate.of(2026, 8, 15),
            count = 3,
        )

        assertTrue(selection.contains(LocalDate.of(2026, 8, 9)))
        assertTrue(selection.contains(LocalDate.of(2026, 8, 15)))
        assertTrue(!selection.contains(LocalDate.of(2026, 8, 8)))
        assertTrue(!selection.contains(LocalDate.of(2026, 8, 16)))
        assertTrue(!selection.contains(null))
    }

    @Test
    fun initialYearUsesLatestRealCaptureOrProvidedCurrentYear() {
        val fallback = 2030

        assertEquals(
            2026,
            PhotoWallOrganizer.initialYear(
                listOf(card("old", captured(2026, 7, 1, 9)), card("new", captured(2026, 8, 2, 9))),
                zoneId,
                fallback,
            ),
        )
        assertEquals(
            fallback,
            PhotoWallOrganizer.initialYear(listOf(card("unknown", null)), zoneId, fallback),
        )
    }

    private fun captured(year: Int, month: Int, day: Int, hour: Int): Long =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, zoneId).toInstant().toEpochMilli()

    private fun card(id: String, dateTakenMillis: Long?) = GalleryPhotoCard(
        photoId = id,
        uri = "content://apm.test/$id",
        displayName = "$id.jpg",
        dateTakenMillis = dateTakenMillis,
        annotation = null,
        modelName = null,
        promptVersion = null,
        annotatedAt = null,
    )
}
