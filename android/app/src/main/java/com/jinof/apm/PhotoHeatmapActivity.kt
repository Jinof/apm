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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
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
                    onBack = ::finish,
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
private fun PhotoHeatmapPage(
    photos: List<GalleryPhotoCard>?,
    onBack: () -> Unit,
    onViewPhotos: (PhotoHeatmapSelection) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("heatmap_back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回照片墙")
                    }
                },
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
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = 12.dp,
                        end = 12.dp,
                        top = padding.calculateTopPadding() + 10.dp,
                        bottom = padding.calculateBottomPadding() + 12.dp,
                    )
                    .testTag("photo_heatmap_page"),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                PhotoHeatmapSideControls(
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
                    modifier = Modifier.weight(1f).fillMaxHeight(),
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
private fun PhotoHeatmapSideControls(
    year: Int,
    granularity: PhotoHeatmapGranularity,
    onGranularity: (PhotoHeatmapGranularity) -> Unit,
    onPreviousYear: () -> Unit,
    onNextYear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(84.dp)
            .fillMaxHeight()
            .testTag("heatmap_side_controls"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("年度", style = MaterialTheme.typography.labelMedium)
        IconButton(onClick = onPreviousYear, modifier = Modifier.testTag("heatmap_previous_year")) {
            Icon(Icons.Outlined.ChevronLeft, contentDescription = "上一年")
        }
        Text(
            "${year}年",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.testTag("heatmap_year_label"),
        )
        IconButton(onClick = onNextYear, modifier = Modifier.testTag("heatmap_next_year")) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = "下一年")
        }
        Spacer(Modifier.height(8.dp))
        Text("粒度", style = MaterialTheme.typography.labelMedium)
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
                modifier = Modifier.testTag("heatmap_granularity_${option.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun PhotoHeatmapSectionTitle(title: String, supporting: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            supporting,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
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
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PhotoHeatmapSectionTitle("照片热力图", "年度 · ${heatmapGranularity.label}")
        AnnualPhotoCountHeatmap(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            heatmap = heatmap,
            granularity = heatmapGranularity,
            selectedRange = selectedRange,
            onSelectRange = onSelectRange,
        )
        if (selectedRange != null) {
            Text(
                "已选 ${heatmapSelectionLabel(selectedRange)} · ${selectedRange.count} 张照片",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("selected_photo_range_summary"),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onClearRange,
                    modifier = Modifier.testTag("clear_photo_range"),
                ) {
                    Text("清除选择")
                }
                FilledTonalButton(
                    onClick = { onViewPhotos(selectedRange) },
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
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
                "${heatmapUnitLabel(granularity)}最多 ${heatmap.maxCount(granularity)} 张 · " +
                    when (granularity) {
                        PhotoHeatmapGranularity.DAY -> "每周一行"
                        PhotoHeatmapGranularity.WEEK -> "每周一行"
                        PhotoHeatmapGranularity.MONTH -> "每月一行"
                    },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (granularity) {
            PhotoHeatmapGranularity.DAY -> GitHubAnnualDayHeatmap(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                heatmap = heatmap,
                selectedRange = selectedRange,
                today = today,
                onSelectRange = onSelectRange,
            )
            PhotoHeatmapGranularity.WEEK -> GitHubAnnualWeekHeatmap(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                heatmap = heatmap,
                selectedRange = selectedRange,
                onSelectRange = onSelectRange,
            )
            PhotoHeatmapGranularity.MONTH -> GitHubAnnualMonthHeatmap(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                heatmap = heatmap,
                selectedRange = selectedRange,
                onSelectRange = onSelectRange,
            )
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
    val cellSize = ((LocalConfiguration.current.screenHeightDp - 260).dp / 53f)
        .coerceIn(6.dp, 14.dp)
    val gap = 1.dp
    val monthByWeek = heatmap.monthLabels.associateBy(PhotoHeatmapMonthLabel::startWeekIndex)
    Column(
        modifier = modifier.fillMaxSize().testTag("heatmap_day_vertical"),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            Spacer(Modifier.width(20.dp))
            listOf("日", "一", "二", "三", "四", "五", "六").forEach { weekday ->
                Box(
                    modifier = Modifier.size(cellSize),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        weekday,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 7.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        heatmap.dayCells.chunked(7).forEachIndexed { weekIndex, week ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.width(20.dp).height(cellSize),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    monthByWeek[weekIndex]?.let { label ->
                        Text(
                            "${label.month.monthValue}月",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 6.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                week.forEach { cell ->
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
                                if (cell.inYear && cell.count > 0) {
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
                        color = if (cell.inYear) githubHeatmapColor(cell.level) else Color.Transparent,
                        border = if (cell.inYear) githubHeatmapBorder(selected, isToday) else null,
                        shape = RoundedCornerShape(2.dp),
                    ) {}
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
    val cellSize = ((LocalConfiguration.current.screenHeightDp - 250).dp / heatmap.weekCells.size.toFloat())
        .coerceIn(7.dp, 14.dp)
    val monthByWeek = heatmap.monthLabels.associateBy(PhotoHeatmapMonthLabel::startWeekIndex)
    Column(
        modifier = modifier.fillMaxSize().testTag("heatmap_week_vertical"),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        heatmap.weekCells.forEachIndexed { weekIndex, cell ->
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.width(20.dp).height(cellSize),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    monthByWeek[weekIndex]?.let { label ->
                        Text(
                            "${label.month.monthValue}月",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 6.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                    shape = RoundedCornerShape(2.dp),
                ) {}
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
    val cellHeight = ((LocalConfiguration.current.screenHeightDp - 220).dp / 12f)
        .coerceIn(24.dp, 42.dp)
    Column(
        modifier = modifier.fillMaxSize().testTag("heatmap_month_vertical"),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        heatmap.monthCells.forEachIndexed { index, cell ->
            val selected = selectedRange?.let { selection ->
                selection.granularity == PhotoHeatmapGranularity.MONTH &&
                    selection.startDate == cell.startDate
            } == true
            val description = buildString {
                append(cell.startDate.format(DateTimeFormatter.ofPattern("yyyy年M月")))
                append("，${cell.count}张照片，热度${cell.level}级")
                if (selected) append("，已选择")
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(cellHeight),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${index + 1}月",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(32.dp),
                )
                Surface(
                    modifier = Modifier
                        .width(30.dp)
                        .fillMaxHeight()
                        .testTag("heatmap_month_${(index + 1).toString().padStart(2, '0')}")
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
                    shape = RoundedCornerShape(3.dp),
                ) {}
                Text(
                    "${cell.count} 张",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
