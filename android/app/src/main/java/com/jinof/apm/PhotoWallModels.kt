package com.jinof.apm

import java.time.Instant
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil
import kotlin.math.roundToLong

internal const val ALBUM_COLUMNS = 5

internal data class WallScrollTarget(val item: Int, val offset: Int = 0)

/** The header occupies grid item zero. Each following row has up to five square photos. */
internal class PhotoWallScrollRange(
    private val photoCount: Int,
    private val headerHeight: Int,
    viewportWidth: Int,
    viewportHeight: Int,
    private val gap: Int,
) {
    private val rows = (photoCount.toLong() + ALBUM_COLUMNS - 1) / ALBUM_COLUMNS
    private val availableWidth = viewportWidth - gap * (ALBUM_COLUMNS - 1)
    private val tileHeight = ((availableWidth + ALBUM_COLUMNS - 1) / ALBUM_COLUMNS).coerceAtLeast(1)
    private val stride = tileHeight + gap
    private val maxOffset = (headerHeight + rows * stride - viewportHeight).coerceAtLeast(0L)

    fun fraction(item: Int, offset: Int, canScrollBack: Boolean, canScrollForward: Boolean): Float {
        if (!canScrollBack || photoCount == 0) return 0f
        if (!canScrollForward) return 1f
        if (maxOffset == 0L) return 0f
        val rowTop = if (item == 0) 0L else headerHeight + gap + (item - 1L) / ALBUM_COLUMNS * stride
        return ((rowTop + offset).toDouble() / maxOffset).coerceIn(0.0, 1.0).toFloat()
    }

    fun target(fraction: Float): WallScrollTarget {
        if (fraction <= 0f || photoCount == 0) return WallScrollTarget(0)
        if (fraction >= 1f) return WallScrollTarget(photoCount)
        val y = (maxOffset * fraction.toDouble()).roundToLong()
        val inPhotos = y - headerHeight - gap
        if (inPhotos < 0) return WallScrollTarget(0, y.toInt())
        return WallScrollTarget((1 + inPhotos / stride * ALBUM_COLUMNS).toInt(), (inPhotos % stride).toInt())
    }
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

private val heatmapDayFormat = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)

data class PhotoHeatmapDayCell(
    val date: LocalDate,
    val count: Int,
    val level: Int,
    val inYear: Boolean,
) {
    val description = "${date.format(heatmapDayFormat)}，${count}张照片，热度${level}级"
}

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

    // Each month has a Sunday-first, six-row grid. Prepare its slots with the annual data.
    val calendarMonths = (1..12).map { number ->
        val month = YearMonth.of(year, number)
        val first = month.atDay(1)
        val firstIndex = (first.toEpochDay() - gridStart.toEpochDay()).toInt()
        val leadingBlanks = first.dayOfWeek.value % 7
        List(42) { slot ->
            val index = slot - leadingBlanks
            if (index in 0 until month.lengthOfMonth()) dayCells[firstIndex + index] else null
        }.chunked(7)
    }
    val weeksByMonth = weekCells.groupBy { it.startDate.monthValue }

    fun selection(granularity: PhotoHeatmapGranularity, start: LocalDate): PhotoHeatmapSelection? {
        if (granularity == PhotoHeatmapGranularity.DAY) {
            val cell = dayCells.getOrNull((start.toEpochDay() - gridStart.toEpochDay()).toInt())
            return cell?.takeIf { it.date == start && it.count > 0 }?.let {
                PhotoHeatmapSelection(granularity, start, start, it.count)
            }
        }
        val periods = if (granularity == PhotoHeatmapGranularity.WEEK) weekCells else monthCells
        return periods.firstOrNull { it.startDate == start && it.count > 0 }?.let {
            PhotoHeatmapSelection(granularity, start, it.endDate, it.count)
        }
    }

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
    internal val captureOrder = compareByDescending<GalleryPhotoCard> { it.dateTakenMillis != null }
        .thenByDescending { it.dateTakenMillis ?: Long.MIN_VALUE }
        .thenBy(GalleryPhotoCard::photoId)

    fun groupByDay(
        photos: List<GalleryPhotoCard>,
        zoneId: ZoneId,
    ): List<PhotoDayGroup> {
        val ordered = photos
            .distinctBy(GalleryPhotoCard::photoId)
            .sortedWith(captureOrder)
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
            .mapNotNull { group -> group.date?.takeIf { it.year == year }?.let { it to group.photos.size } }
            .toMap()
        return heatmap(year, counts)
    }

    internal fun heatmap(year: Int, counts: Map<LocalDate, Int>): PhotoHeatmapYear {
        val yearStart = LocalDate.of(year, 1, 1)
        val yearEnd = LocalDate.of(year, 12, 31)
        val maxDayCount = counts.values.maxOrNull() ?: 0
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
                count = week.sumOf { it.count },
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
                count = (1..month.lengthOfMonth()).sumOf { counts[month.atDay(it)] ?: 0 },
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
            totalCount = counts.values.sum(),
            maxDayCount = maxDayCount,
            maxWeekCount = maxWeekCount,
            maxMonthCount = maxMonthCount,
        )
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
