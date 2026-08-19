package com.jinof.apm

import java.time.Instant
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil

enum class PhotoWallDisplayMode(val label: String) {
    THUMBNAILS("缩略图"),
    DETAILS("明细"),
}

enum class PhotoHeatmapGranularity(val label: String) {
    DAY("每天"),
    WEEK("每周"),
    MONTH("每月"),
}

data class PhotoDayGroup(
    val date: LocalDate?,
    val photos: List<GalleryPhotoCard>,
)

data class PhotoHeatmapDayCell(
    val date: LocalDate,
    val count: Int,
    val level: Int,
    val inYear: Boolean,
)

data class PhotoHeatmapPeriodCell(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val count: Int,
    val level: Int,
)

data class PhotoHeatmapMonthLabel(
    val month: YearMonth,
    val startWeekIndex: Int,
    val spanWeeks: Int,
)

data class PhotoHeatmapYear(
    val year: Int,
    val gridStart: LocalDate,
    val dayCells: List<PhotoHeatmapDayCell>,
    val weekCells: List<PhotoHeatmapPeriodCell>,
    val monthCells: List<PhotoHeatmapPeriodCell>,
    val monthLabels: List<PhotoHeatmapMonthLabel>,
    val totalCount: Int,
    val maxDayCount: Int,
    val maxWeekCount: Int,
    val maxMonthCount: Int,
) {
    val weekCount: Int get() = weekCells.size

    fun maxCount(granularity: PhotoHeatmapGranularity): Int = when (granularity) {
        PhotoHeatmapGranularity.DAY -> maxDayCount
        PhotoHeatmapGranularity.WEEK -> maxWeekCount
        PhotoHeatmapGranularity.MONTH -> maxMonthCount
    }
}

data class PhotoHeatmapSelection(
    val granularity: PhotoHeatmapGranularity,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val count: Int,
) {
    fun contains(date: LocalDate?): Boolean = date != null &&
        !date.isBefore(startDate) &&
        !date.isAfter(endDate)
}

object PhotoWallOrganizer {
    fun groupByDay(
        photos: List<GalleryPhotoCard>,
        zoneId: ZoneId,
    ): List<PhotoDayGroup> {
        val ordered = photos
            .distinctBy(GalleryPhotoCard::photoId)
            .sortedWith(
                compareByDescending<GalleryPhotoCard> { it.dateTakenMillis != null }
                    .thenByDescending { it.dateTakenMillis ?: Long.MIN_VALUE }
                    .thenBy(GalleryPhotoCard::photoId),
            )
        val groups = linkedMapOf<LocalDate?, MutableList<GalleryPhotoCard>>()
        ordered.forEach { photo ->
            val date = photo.dateTakenMillis?.let { captureMillis ->
                Instant.ofEpochMilli(captureMillis).atZone(zoneId).toLocalDate()
            }
            groups.getOrPut(date) { mutableListOf() } += photo
        }
        return groups.map { (date, groupedPhotos) -> PhotoDayGroup(date, groupedPhotos) }
    }

    fun initialYear(
        photos: List<GalleryPhotoCard>,
        zoneId: ZoneId,
        currentYear: Int = LocalDate.now(zoneId).year,
    ): Int = photos.asSequence()
        .mapNotNull(GalleryPhotoCard::dateTakenMillis)
        .maxOrNull()
        ?.let { Instant.ofEpochMilli(it).atZone(zoneId).year }
        ?: currentYear

    fun heatmap(
        year: Int,
        photos: List<GalleryPhotoCard>,
        zoneId: ZoneId,
    ): PhotoHeatmapYear {
        val counts = groupByDay(photos, zoneId)
            .mapNotNull { group -> group.date?.let { it to group.photos.size } }
            .toMap()
        val yearStart = LocalDate.of(year, 1, 1)
        val yearEnd = LocalDate.of(year, 12, 31)
        val yearCounts = counts.filterKeys { it.year == year }
        val maxDayCount = yearCounts.values.maxOrNull() ?: 0
        val gridStart = yearStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        val gridEnd = yearEnd.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
        val dayCellCount = ChronoUnit.DAYS.between(gridStart, gridEnd).toInt() + 1
        val dayCells = List(dayCellCount) { offset ->
            val date = gridStart.plusDays(offset.toLong())
            val inYear = date.year == year
            val count = if (inYear) counts[date] ?: 0 else 0
            PhotoHeatmapDayCell(
                date = date,
                count = count,
                level = discreteHeatLevel(count, maxDayCount),
                inYear = inYear,
            )
        }

        val rawWeeks = dayCells.chunked(DAYS_PER_WEEK).map { week ->
            val startDate = maxOf(week.first().date, yearStart)
            val endDate = minOf(week.last().date, yearEnd)
            PhotoHeatmapPeriodCell(
                startDate = startDate,
                endDate = endDate,
                count = countRange(counts, startDate, endDate),
                level = 0,
            )
        }
        val maxWeekCount = rawWeeks.maxOfOrNull(PhotoHeatmapPeriodCell::count) ?: 0
        val weekCells = rawWeeks.map { cell ->
            cell.copy(level = discreteHeatLevel(cell.count, maxWeekCount))
        }

        val rawMonths = (1..MONTHS_PER_YEAR).map { monthNumber ->
            val month = YearMonth.of(year, monthNumber)
            PhotoHeatmapPeriodCell(
                startDate = month.atDay(1),
                endDate = month.atEndOfMonth(),
                count = countRange(counts, month.atDay(1), month.atEndOfMonth()),
                level = 0,
            )
        }
        val maxMonthCount = rawMonths.maxOfOrNull(PhotoHeatmapPeriodCell::count) ?: 0
        val monthCells = rawMonths.map { cell ->
            cell.copy(level = discreteHeatLevel(cell.count, maxMonthCount))
        }

        val monthStartWeeks = (1..MONTHS_PER_YEAR).map { monthNumber ->
            val month = YearMonth.of(year, monthNumber)
            month to (ChronoUnit.DAYS.between(gridStart, month.atDay(1)).toInt() / DAYS_PER_WEEK)
        }
        val monthLabels = monthStartWeeks.mapIndexed { index, (month, startWeekIndex) ->
            val nextStart = monthStartWeeks.getOrNull(index + 1)?.second ?: weekCells.size
            PhotoHeatmapMonthLabel(
                month = month,
                startWeekIndex = startWeekIndex,
                spanWeeks = nextStart - startWeekIndex,
            )
        }

        return PhotoHeatmapYear(
            year = year,
            gridStart = gridStart,
            dayCells = dayCells,
            weekCells = weekCells,
            monthCells = monthCells,
            monthLabels = monthLabels,
            totalCount = yearCounts.values.sum(),
            maxDayCount = maxDayCount,
            maxWeekCount = maxWeekCount,
            maxMonthCount = maxMonthCount,
        )
    }

    private fun countRange(
        counts: Map<LocalDate, Int>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): Int = counts.entries.sumOf { (date, count) ->
        if (!date.isBefore(startDate) && !date.isAfter(endDate)) count else 0
    }

    private fun discreteHeatLevel(count: Int, maxCount: Int): Int = when {
        count <= 0 || maxCount <= 0 -> 0
        else -> ceil(count.toDouble() / maxCount * MAX_HEAT_LEVEL)
            .toInt()
            .coerceIn(1, MAX_HEAT_LEVEL)
    }

    private const val DAYS_PER_WEEK = 7
    private const val MONTHS_PER_YEAR = 12
    const val MAX_HEAT_LEVEL = 4
}
