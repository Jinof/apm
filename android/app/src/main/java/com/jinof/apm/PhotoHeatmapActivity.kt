package com.jinof.apm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

internal fun Context.observeHeatmapTimeZone(onChange: () -> Unit): BroadcastReceiver {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onChange()
    }
    androidx.core.content.ContextCompat.registerReceiver(
        this, receiver, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED),
        androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    return receiver
}

class PhotoHeatmapActivity : ComponentActivity() {
    private lateinit var database: ApmDatabase
    private val executor = Executors.newSingleThreadExecutor()
    private var data by mutableStateOf<PreparedHeatmap?>(null)
    private var error by mutableStateOf<String?>(null)
    private lateinit var zoneReceiver: BroadcastReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        database = ApmDatabase(applicationContext)
        zoneReceiver = observeHeatmapTimeZone(::reloadPhotos)
        setContent {
            ApmTheme {
                PhotoHeatmapPage(
                    data = data,
                    loadError = error,
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
    }

    override fun onResume() {
        super.onResume()
        reloadPhotos()
    }

    override fun onDestroy() {
        unregisterReceiver(zoneReceiver)
        executor.shutdownNow()
        database.close()
        super.onDestroy()
    }

    private fun reloadPhotos() {
        executor.execute {
            val result = runCatching { database.preparedHeatmap() }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    data = result.getOrNull()
                    error = result.exceptionOrNull()?.let { "读取热力图失败" }
                }
            }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoHeatmapPage(
    data: PreparedHeatmap?,
    loadError: String? = null,
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
        if (data == null) {
            Box(
                modifier = Modifier.fillMaxSize().testTag("heatmap_loading"),
                contentAlignment = Alignment.Center,
            ) {
                if (loadError == null) CircularProgressIndicator() else Text(loadError)
            }
        } else {
            var chosenYear by rememberSaveable { mutableStateOf<Int?>(null) }
            val displayedYear = chosenYear ?: data.initialYear
            var granularity by rememberSaveable { mutableStateOf(PhotoHeatmapGranularity.DAY) }
            var selectedDay by rememberSaveable(displayedYear, granularity) { mutableStateOf<Long?>(null) }
            val heatmap = data.years[displayedYear] ?: key(displayedYear) {
                // Years outside the catalog only need an empty calendar template.
                val empty by produceState<PhotoHeatmapYear?>(null) {
                    value = withContext(Dispatchers.Default) { PhotoWallOrganizer.heatmap(displayedYear, emptyMap()) }
                }
                empty
            }
            val selectedRange = remember(heatmap, granularity, selectedDay) {
                selectedDay?.let { heatmap?.selection(granularity, LocalDate.ofEpochDay(it)) }
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
                        selectedDay = null
                    },
                    onPreviousYear = {
                        chosenYear = displayedYear - 1
                        selectedDay = null
                    },
                    onNextYear = {
                        chosenYear = displayedYear + 1
                        selectedDay = null
                    },
                )
                if (heatmap != null) PhotoHeatmapContent(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    heatmap = heatmap,
                    heatmapGranularity = granularity,
                    selectedRange = selectedRange,
                    onSelectRange = { selectedDay = it.startDate.toEpochDay() },
                    onClearRange = { selectedDay = null },
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
                "${heatmapUnitLabel(granularity)}最多 ${heatmap.maxCount(granularity)} 张",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val dark = isSystemInDarkTheme()
        val label = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
        val ink = MaterialTheme.colorScheme.onSurface.toArgb()
        val palette = remember(dark, label, ink) {
            HeatmapMatrixPalette(
                levels = (if (dark) darkHeatmapColors else lightHeatmapColors).map { it.toArgb() },
                label = label,
                today = ink,
                selection = (if (dark) Color(0xFF58A6FF) else Color(0xFF0969DA)).toArgb(),
            )
        }
        BoxWithConstraints(modifier = Modifier.weight(1f, fill = false).fillMaxWidth()) {
            val rows = (heatmap.cells(granularity).size + HEATMAP_COLUMNS - 1) / HEATMAP_COLUMNS
            val height = if (granularity == PhotoHeatmapGranularity.DAY) maxHeight
                else minOf(maxHeight, maxWidth * rows / HEATMAP_COLUMNS)
            HeatmapMatrix(
                modifier = Modifier.fillMaxWidth().height(height).testTag("heatmap_matrix"),
                model = heatmap,
                mode = granularity,
                selectedDate = selectedRange?.startDate,
                today = today,
                palette = palette,
                onSelect = onSelectRange,
            )
        }
        GitHubHeatmapGuide()
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
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = RoundedCornerShape(2.dp),
            ) {}
        }
        Spacer(Modifier.width(5.dp))
        Text("多", style = MaterialTheme.typography.labelSmall)
    }
}

private val lightHeatmapColors = listOf(
    Color(0xFFEBEDF0), Color(0xFF9BE9A8), Color(0xFF40C463), Color(0xFF30A14E), Color(0xFF216E39),
)
private val darkHeatmapColors = listOf(
    Color(0xFF161B22), Color(0xFF0E4429), Color(0xFF006D32), Color(0xFF26A641), Color(0xFF39D353),
)

@Composable
private fun githubHeatmapColor(level: Int): Color = (if (isSystemInDarkTheme()) darkHeatmapColors else lightHeatmapColors)[level]

private fun heatmapUnitLabel(granularity: PhotoHeatmapGranularity): String = when (granularity) {
    PhotoHeatmapGranularity.DAY -> "单日"
    PhotoHeatmapGranularity.WEEK -> "单周"
    PhotoHeatmapGranularity.MONTH -> "单月"
}

internal fun heatmapSelectionLabel(selection: PhotoHeatmapSelection): String = when (selection.granularity) {
    PhotoHeatmapGranularity.DAY ->
        selection.startDate.format(heatmapDayFormat)
    PhotoHeatmapGranularity.WEEK -> heatmapRangeLabel(selection.startDate, selection.endDate)
    PhotoHeatmapGranularity.MONTH ->
        selection.startDate.format(DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINA))
}

private fun heatmapRangeLabel(startDate: LocalDate, endDate: LocalDate): String = when {
    startDate == endDate -> startDate.format(heatmapDayFormat)
    startDate.year == endDate.year && startDate.month == endDate.month ->
        "${startDate.format(heatmapDayFormat)}–${endDate.dayOfMonth}日"
    startDate.year == endDate.year ->
        "${startDate.format(heatmapDayFormat)}–${endDate.format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA))}"
    else ->
        "${startDate.format(heatmapDayFormat)}–${endDate.format(heatmapDayFormat)}"
}
