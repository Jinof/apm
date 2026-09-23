package com.jinof.apm

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

private const val EXTRA_HEATMAP_GRANULARITY = "photo_heatmap_granularity"
private const val EXTRA_HEATMAP_START_DATE = "photo_heatmap_start_date"
private const val EXTRA_HEATMAP_END_DATE = "photo_heatmap_end_date"
private const val EXTRA_HEATMAP_COUNT = "photo_heatmap_count"

internal fun Intent.putPhotoHeatmapSelection(selection: PhotoHeatmapSelection): Intent = apply {
    putExtra(EXTRA_HEATMAP_GRANULARITY, selection.granularity.name)
    putExtra(EXTRA_HEATMAP_START_DATE, selection.startDate.toString())
    putExtra(EXTRA_HEATMAP_END_DATE, selection.endDate.toString())
    putExtra(EXTRA_HEATMAP_COUNT, selection.count)
}

internal fun Intent.photoHeatmapSelection(): PhotoHeatmapSelection? {
    val granularity = getStringExtra(EXTRA_HEATMAP_GRANULARITY)
        ?.let { runCatching { PhotoHeatmapGranularity.valueOf(it) }.getOrNull() }
    val startDate = getStringExtra(EXTRA_HEATMAP_START_DATE)
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val endDate = getStringExtra(EXTRA_HEATMAP_END_DATE)
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    if (granularity == null || startDate == null || endDate == null) return null
    return PhotoHeatmapSelection(
        granularity = granularity,
        startDate = startDate,
        endDate = endDate,
        count = getIntExtra(EXTRA_HEATMAP_COUNT, 0),
    )
}

class PhotoHeatmapActivity : ComponentActivity() {
    private lateinit var database: ApmDatabase
    private val executor = Executors.newSingleThreadExecutor()
    private var initialized = false
    private var photos by mutableStateOf<List<GalleryPhotoCard>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        database = ApmDatabase(applicationContext)
        initialized = true
        setContent {
            ApmTheme {
                PhotoHeatmapPage(
                    photos = photos,
                    onViewPhotos = { selection ->
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .putPhotoHeatmapSelection(selection)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        )
                        finish()
                    },
                )
            }
        }
        reloadPhotos()
    }

    override fun onResume() {
        super.onResume()
        if (initialized) reloadPhotos()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        database.close()
        super.onDestroy()
    }

    private fun reloadPhotos() {
        executor.execute {
            val loaded = database.galleryPhotos()
            runOnUiThread {
                if (!isFinishing && !isDestroyed) photos = loaded
            }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoHeatmapPage(
    photos: List<GalleryPhotoCard>?,
    onViewPhotos: (PhotoHeatmapSelection) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("照片热力图", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "年度照片密度",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (photos == null) {
            Box(
                modifier = Modifier.fillMaxSize().testTag("heatmap_loading"),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            val zoneId = ZoneId.systemDefault()
            val initialYear = remember(photos, zoneId) {
                PhotoWallOrganizer.initialYear(photos, zoneId)
            }
            var displayedYear by remember(initialYear) { mutableStateOf(initialYear) }
            var granularity by remember { mutableStateOf(PhotoHeatmapGranularity.DAY) }
            var selectedRange by remember { mutableStateOf<PhotoHeatmapSelection?>(null) }
            val heatmap = remember(displayedYear, photos, zoneId) {
                PhotoWallOrganizer.heatmap(displayedYear, photos, zoneId)
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = 6.dp,
                        end = 6.dp,
                        top = padding.calculateTopPadding() + 4.dp,
                        bottom = padding.calculateBottomPadding() + 4.dp,
                    )
                    .testTag("photo_heatmap_page"),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                PhotoHeatmapTimeControls(
                    year = displayedYear,
                    granularity = granularity,
                    onGranularity = {
                        granularity = it
                        selectedRange = null
                    },
                    onPreviousYear = {
                        displayedYear -= 1
                        selectedRange = null
                    },
                    onNextYear = {
                        displayedYear += 1
                        selectedRange = null
                    },
                )
                PhotoHeatmapContent(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    heatmap = heatmap,
                    heatmapGranularity = granularity,
                    selectedRange = selectedRange,
                    onSelectRange = { selectedRange = it },
                    onClearRange = { selectedRange = null },
                    onViewPhotos = onViewPhotos,
                )
            }
        }
    }
}

@Composable
private fun PhotoHeatmapTimeControls(
    year: Int,
    granularity: PhotoHeatmapGranularity,
    onGranularity: (PhotoHeatmapGranularity) -> Unit,
    onPreviousYear: () -> Unit,
    onNextYear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .testTag("heatmap_time_controls"),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onPreviousYear,
            modifier = Modifier.size(36.dp).testTag("heatmap_previous_year"),
        ) {
            Icon(Icons.Outlined.ChevronLeft, contentDescription = "上一年")
        }
        Text(
            "${year}年",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.testTag("heatmap_year_label"),
        )
        IconButton(
            onClick = onNextYear,
            modifier = Modifier.size(36.dp).testTag("heatmap_next_year"),
        ) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = "下一年")
        }
        Spacer(Modifier.weight(1f))
        PhotoHeatmapGranularity.entries.forEach { option ->
            FilterChip(
                selected = granularity == option,
                onClick = { onGranularity(option) },
                label = {
                    Text(
                        when (option) {
                            PhotoHeatmapGranularity.DAY -> "日"
                            PhotoHeatmapGranularity.WEEK -> "周"
                            PhotoHeatmapGranularity.MONTH -> "月"
                        },
                    )
                },
                modifier = Modifier
                    .height(32.dp)
                    .testTag("heatmap_granularity_${option.name.lowercase()}"),
            )
        }
    }
}

@Composable
internal fun PhotoHeatmapContent(
    modifier: Modifier = Modifier,
    heatmap: PhotoHeatmapYear,
    heatmapGranularity: PhotoHeatmapGranularity,
    selectedRange: PhotoHeatmapSelection?,
    onSelectRange: (PhotoHeatmapSelection) -> Unit,
    onClearRange: () -> Unit,
    onViewPhotos: (PhotoHeatmapSelection) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().testTag("photo_heatmap_content"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AnnualPhotoCountHeatmap(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            heatmap = heatmap,
            granularity = heatmapGranularity,
            selectedRange = selectedRange,
            onSelectRange = onSelectRange,
        )
        if (selectedRange != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "已选 ${heatmapSelectionLabel(selectedRange)} · ${selectedRange.count} 张照片",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("selected_photo_range_summary"),
                )
                TextButton(
                    onClick = onClearRange,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.testTag("clear_photo_range"),
                ) {
                    Text("清除选择")
                }
                FilledTonalButton(
                    onClick = { onViewPhotos(selectedRange) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                    modifier = Modifier.testTag("view_selected_photos"),
                ) {
                    Text("去照片中查看")
                }
            }
        }
    }
}

@Composable
private fun AnnualPhotoCountHeatmap(
    modifier: Modifier = Modifier,
    heatmap: PhotoHeatmapYear,
    granularity: PhotoHeatmapGranularity,
    selectedRange: PhotoHeatmapSelection?,
    onSelectRange: (PhotoHeatmapSelection) -> Unit,
) {
    val today = remember { LocalDate.now() }
    Column(
        modifier = modifier.fillMaxSize().testTag("photo_wall_heatmap"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "全年 ${heatmap.totalCount} 张",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${heatmapUnitLabel(granularity)}最多 ${heatmap.maxCount(granularity)} 张 · 四季纵排 · 月份横排",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("heatmap_quarters_vertical"),
        ) {
            when (granularity) {
                PhotoHeatmapGranularity.DAY -> GitHubAnnualDayHeatmap(
                    modifier = Modifier.fillMaxSize(),
                    heatmap = heatmap,
                    selectedRange = selectedRange,
                    today = today,
                    onSelectRange = onSelectRange,
                )
                PhotoHeatmapGranularity.WEEK -> GitHubAnnualWeekHeatmap(
                    modifier = Modifier.fillMaxSize(),
                    heatmap = heatmap,
                    selectedRange = selectedRange,
                    onSelectRange = onSelectRange,
                )
                PhotoHeatmapGranularity.MONTH -> GitHubAnnualMonthHeatmap(
                    modifier = Modifier.fillMaxSize(),
                    heatmap = heatmap,
                    selectedRange = selectedRange,
                    onSelectRange = onSelectRange,
                )
            }
        }
        GitHubHeatmapGuide()
    }
}

@Composable
private fun GitHubAnnualDayHeatmap(
    modifier: Modifier = Modifier,
    heatmap: PhotoHeatmapYear,
    selectedRange: PhotoHeatmapSelection?,
    today: LocalDate,
    onSelectRange: (PhotoHeatmapSelection) -> Unit,
) {
    QuarterlyHeatmapLayout(modifier = modifier, mode = "day") { monthNumber, monthModifier ->
        GitHubMonthDayHeatmap(
            modifier = monthModifier,
            monthNumber = monthNumber,
            heatmap = heatmap,
            selectedRange = selectedRange,
            today = today,
            onSelectRange = onSelectRange,
        )
    }
}

@Composable
private fun GitHubMonthDayHeatmap(
    modifier: Modifier = Modifier,
    monthNumber: Int,
    heatmap: PhotoHeatmapYear,
    selectedRange: PhotoHeatmapSelection?,
    today: LocalDate,
    onSelectRange: (PhotoHeatmapSelection) -> Unit,
) {
    val month = remember(heatmap.year, monthNumber) { YearMonth.of(heatmap.year, monthNumber) }
    val cellsByDate = remember(heatmap.dayCells) { heatmap.dayCells.associateBy { it.date } }
    val leadingBlanks = month.atDay(1).dayOfWeek.value % 7
    val slots = List(42) { slot ->
        val day = slot - leadingBlanks + 1
        if (day in 1..month.lengthOfMonth()) cellsByDate[month.atDay(day)] else null
    }
    val gap = 1.dp
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val cellSize = minOf(
            (maxWidth - gap * 6) / 7f,
            (maxHeight - 12.dp - gap * 7) / 7f,
        ).coerceAtLeast(3.dp)
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "${monthNumber}月",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 7.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                listOf("日", "一", "二", "三", "四", "五", "六").forEach { weekday ->
                    Box(modifier = Modifier.size(cellSize), contentAlignment = Alignment.Center) {
                        Text(
                            weekday,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 6.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            slots.chunked(7).forEach { week ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    week.forEach { cell ->
                        if (cell == null) {
                            Spacer(Modifier.size(cellSize))
                        } else {
                            val selected = selectedRange?.let { selection ->
                                selection.granularity == PhotoHeatmapGranularity.DAY &&
                                    selection.startDate == cell.date
                            } == true
                            val isToday = cell.date == today
                            val description = buildString {
                                append(cell.date.format(DateTimeFormatter.ofPattern("yyyy年M月d日")))
                                append("，${cell.count}张照片，热度${cell.level}级")
                                if (isToday) append("，今天")
                                if (selected) append("，已选择")
                            }
                            Surface(
                                modifier = Modifier
                                    .size(cellSize)
                                    .testTag("heatmap_day_${cell.date}")
                                    .then(
                                        if (cell.count > 0) {
                                            Modifier.clickable {
                                                onSelectRange(
                                                    PhotoHeatmapSelection(
                                                        granularity = PhotoHeatmapGranularity.DAY,
                                                        startDate = cell.date,
                                                        endDate = cell.date,
                                                        count = cell.count,
                                                    ),
                                                )
                                            }
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .semantics {
                                        contentDescription = description
                                        this.selected = selected
                                    },
                                color = githubHeatmapColor(cell.level),
                                border = githubHeatmapBorder(selected, isToday),
                                shape = RoundedCornerShape(2.dp),
                            ) {}
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GitHubAnnualWeekHeatmap(
    modifier: Modifier = Modifier,
    heatmap: PhotoHeatmapYear,
    selectedRange: PhotoHeatmapSelection?,
    onSelectRange: (PhotoHeatmapSelection) -> Unit,
) {
    QuarterlyHeatmapLayout(modifier = modifier, mode = "week") { monthNumber, monthModifier ->
        GitHubMonthWeekHeatmap(
            modifier = monthModifier,
            monthNumber = monthNumber,
            heatmap = heatmap,
            selectedRange = selectedRange,
            onSelectRange = onSelectRange,
        )
    }
}

@Composable
private fun GitHubMonthWeekHeatmap(
    modifier: Modifier = Modifier,
    monthNumber: Int,
    heatmap: PhotoHeatmapYear,
    selectedRange: PhotoHeatmapSelection?,
    onSelectRange: (PhotoHeatmapSelection) -> Unit,
) {
    val cells = remember(heatmap.weekCells, monthNumber) {
        heatmap.weekCells.filter { it.startDate.monthValue == monthNumber }
    }
    val gap = 2.dp
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val rowCount = maxOf(1, (cells.size + 1) / 2)
        val cellSize = minOf(
            (maxWidth - gap) / 2f,
            (maxHeight - 14.dp - gap * (rowCount - 1)) / rowCount.toFloat(),
        ).coerceIn(6.dp, 40.dp)
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "${monthNumber}月",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 7.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            cells.chunked(2).forEach { rowCells ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    rowCells.forEach { cell ->
                        val selected = selectedRange?.let { selection ->
                            selection.granularity == PhotoHeatmapGranularity.WEEK &&
                                selection.startDate == cell.startDate &&
                                selection.endDate == cell.endDate
                        } == true
                        val description = buildString {
                            append(heatmapRangeLabel(cell.startDate, cell.endDate))
                            append("，${cell.count}张照片，热度${cell.level}级")
                            if (selected) append("，已选择")
                        }
                        Surface(
                            modifier = Modifier
                                .size(cellSize)
                                .testTag("heatmap_week_${cell.startDate}")
                                .then(
                                    if (cell.count > 0) {
                                        Modifier.clickable {
                                            onSelectRange(
                                                PhotoHeatmapSelection(
                                                    granularity = PhotoHeatmapGranularity.WEEK,
                                                    startDate = cell.startDate,
                                                    endDate = cell.endDate,
                                                    count = cell.count,
                                                ),
                                            )
                                        }
                                    } else {
                                        Modifier
                                    },
                                )
                                .semantics {
                                    contentDescription = description
                                    this.selected = selected
                                },
                            color = githubHeatmapColor(cell.level),
                            border = githubHeatmapBorder(selected, false),
                            shape = RoundedCornerShape(3.dp),
                        ) {}
                    }
                }
            }
        }
    }
}

@Composable
private fun GitHubAnnualMonthHeatmap(
    modifier: Modifier = Modifier,
    heatmap: PhotoHeatmapYear,
    selectedRange: PhotoHeatmapSelection?,
    onSelectRange: (PhotoHeatmapSelection) -> Unit,
) {
    QuarterlyHeatmapLayout(modifier = modifier, mode = "month") { monthNumber, monthModifier ->
        GitHubMonthCell(
            modifier = monthModifier,
            monthNumber = monthNumber,
            cell = heatmap.monthCells[monthNumber - 1],
            selectedRange = selectedRange,
            onSelectRange = onSelectRange,
        )
    }
}

@Composable
private fun GitHubMonthCell(
    modifier: Modifier = Modifier,
    monthNumber: Int,
    cell: PhotoHeatmapPeriodCell,
    selectedRange: PhotoHeatmapSelection?,
    onSelectRange: (PhotoHeatmapSelection) -> Unit,
) {
    val selected = selectedRange?.let { selection ->
        selection.granularity == PhotoHeatmapGranularity.MONTH &&
            selection.startDate == cell.startDate
    } == true
    val description = buildString {
        append(cell.startDate.format(DateTimeFormatter.ofPattern("yyyy年M月")))
        append("，${cell.count}张照片，热度${cell.level}级")
        if (selected) append("，已选择")
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val cellSize = minOf(maxWidth - 8.dp, maxHeight - 18.dp).coerceAtLeast(12.dp)
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "${monthNumber}月 · ${cell.count}张",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 7.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                modifier = Modifier
                    .size(cellSize)
                    .testTag("heatmap_month_${monthNumber.toString().padStart(2, '0')}")
                    .then(
                        if (cell.count > 0) {
                            Modifier.clickable {
                                onSelectRange(
                                    PhotoHeatmapSelection(
                                        granularity = PhotoHeatmapGranularity.MONTH,
                                        startDate = cell.startDate,
                                        endDate = cell.endDate,
                                        count = cell.count,
                                    ),
                                )
                            }
                        } else {
                            Modifier
                        },
                    )
                    .semantics {
                        contentDescription = description
                        this.selected = selected
                    },
                color = githubHeatmapColor(cell.level),
                border = githubHeatmapBorder(selected, false),
                shape = RoundedCornerShape(4.dp),
            ) {}
        }
    }
}

@Composable
private fun QuarterlyHeatmapLayout(
    modifier: Modifier,
    mode: String,
    monthContent: @Composable (monthNumber: Int, modifier: Modifier) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().testTag("heatmap_${mode}_quarters"),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(4) { quarterIndex ->
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("heatmap_${mode}_quarter_${quarterIndex + 1}"),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.width(14.dp).fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Q${quarterIndex + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 7.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                repeat(3) { monthOffset ->
                    val monthNumber = quarterIndex * 3 + monthOffset + 1
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .testTag(
                                "heatmap_${mode}_month_panel_" +
                                    monthNumber.toString().padStart(2, '0'),
                            ),
                    ) {
                        monthContent(monthNumber, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

@Composable
private fun GitHubHeatmapGuide() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, end = 20.dp, bottom = 4.dp)
            .testTag("heatmap_guide"),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("少", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(5.dp))
        (0..PhotoWallOrganizer.MAX_HEAT_LEVEL).forEach { level ->
            Surface(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(11.dp)
                    .semantics {
                        contentDescription = if (level == 0) "热度0级，无照片" else "热度${level}级"
                    }
                    .testTag("heatmap_guide_level_$level"),
                color = githubHeatmapColor(level),
                border = githubHeatmapBorder(false, false),
                shape = RoundedCornerShape(2.dp),
            ) {}
        }
        Spacer(Modifier.width(5.dp))
        Text("多", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun githubHeatmapColor(level: Int): Color {
    val dark = isSystemInDarkTheme()
    return if (dark) {
        when (level) {
            0 -> Color(0xFF161B22)
            1 -> Color(0xFF0E4429)
            2 -> Color(0xFF006D32)
            3 -> Color(0xFF26A641)
            else -> Color(0xFF39D353)
        }
    } else {
        when (level) {
            0 -> Color(0xFFEBEDF0)
            1 -> Color(0xFF9BE9A8)
            2 -> Color(0xFF40C463)
            3 -> Color(0xFF30A14E)
            else -> Color(0xFF216E39)
        }
    }
}

@Composable
private fun githubHeatmapBorder(selected: Boolean, today: Boolean): BorderStroke = when {
    selected -> BorderStroke(
        2.dp,
        if (isSystemInDarkTheme()) Color(0xFF58A6FF) else Color(0xFF0969DA),
    )
    today -> BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface)
    isSystemInDarkTheme() -> BorderStroke(1.dp, Color(0x1AFFFFFF))
    else -> BorderStroke(1.dp, Color(0x0F1B1F23))
}

private fun heatmapUnitLabel(granularity: PhotoHeatmapGranularity): String = when (granularity) {
    PhotoHeatmapGranularity.DAY -> "单日"
    PhotoHeatmapGranularity.WEEK -> "单周"
    PhotoHeatmapGranularity.MONTH -> "单月"
}

internal fun heatmapSelectionLabel(selection: PhotoHeatmapSelection): String = when (selection.granularity) {
    PhotoHeatmapGranularity.DAY ->
        selection.startDate.format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA))
    PhotoHeatmapGranularity.WEEK -> heatmapRangeLabel(selection.startDate, selection.endDate)
    PhotoHeatmapGranularity.MONTH ->
        selection.startDate.format(DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINA))
}

private fun heatmapRangeLabel(startDate: LocalDate, endDate: LocalDate): String = when {
    startDate == endDate -> startDate.format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA))
    startDate.year == endDate.year && startDate.month == endDate.month ->
        "${startDate.format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA))}–${endDate.dayOfMonth}日"
    startDate.year == endDate.year ->
        "${startDate.format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA))}–${endDate.format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA))}"
    else ->
        "${startDate.format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA))}–${endDate.format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA))}"
}
