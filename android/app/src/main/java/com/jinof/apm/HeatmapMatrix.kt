package com.jinof.apm

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import java.time.LocalDate
import kotlin.math.roundToInt

internal class HeatmapMatrixPalette(val levels: List<Int>, val label: Int, val today: Int, val selection: Int) {
    val textOnLevel = levels.map { if (ColorUtils.calculateLuminance(it) > 0.18) Color.BLACK else Color.WHITE }
}

@Composable
internal fun HeatmapMatrix(
    modifier: Modifier,
    model: PhotoHeatmapYear,
    mode: PhotoHeatmapGranularity,
    selectedDate: LocalDate?,
    today: LocalDate,
    palette: HeatmapMatrixPalette,
    onSelect: (PhotoHeatmapSelection) -> Unit,
) {
    val density = LocalDensity.current
    val grid = remember(model, mode, density) {
        MatrixGrid(model, mode, density.density, with(density) { 12.sp.toPx() })
    }
    val select by rememberUpdatedState(onSelect)
    val hasCellNodes = needsCellNavigation()
    var focused by remember(grid) { mutableStateOf<LocalDate?>(null) }

    Layout(
        modifier = modifier
            .drawBehind { grid.draw(drawContext.canvas.nativeCanvas, palette, selectedDate, today, focused) }
            .pointerInput(grid) {
                detectTapGestures { position ->
                    grid.cellAt(position.x, position.y)?.let { cell ->
                        select(PhotoHeatmapSelection(mode, cell.startDate, cell.endDate, cell.count))
                    }
                }
            }
            .semantics { collectionInfo = CollectionInfo(grid.rows, HEATMAP_COLUMNS) },
        content = {
            // Ordinary touch only needs the grid above. A screen reader or keyboard also needs
            // individually addressable dates. Group their lightweight nodes by calendar row.
            if (hasCellNodes) AccessibleMatrixRows(
                grid, mode, selectedDate, today, select,
                onFocus = { date, hasFocus ->
                    if (hasFocus) focused = date else if (focused == date) focused = null
                },
            )
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        grid.resize(width, height)
        val bands = measurables.mapIndexed { row, measurable ->
            measurable.measure(Constraints.fixed(width - grid.x[0], grid.y[row + 1] - grid.y[row]))
        }
        layout(width, height) {
            bands.forEachIndexed { row, band -> band.place(grid.x[0], grid.y[row]) }
        }
    }
}

@Composable
private fun AccessibleMatrixRows(
    grid: MatrixGrid,
    mode: PhotoHeatmapGranularity,
    selectedDate: LocalDate?,
    today: LocalDate,
    select: (PhotoHeatmapSelection) -> Unit,
    onFocus: (LocalDate, Boolean) -> Unit,
) {
    val platform = LocalViewConfiguration.current
    // This dense grid handles touches as one surface. Expanded accessibility bounds would
    // overlap neighboring rows and hide dates from touch exploration.
    val exactCells = remember(platform) {
        object : ViewConfiguration by platform { override val minimumTouchTargetSize = DpSize.Zero }
    }
    CompositionLocalProvider(LocalViewConfiguration provides exactCells) {
        for (row in 0 until grid.rows) {
            Layout(
                modifier = Modifier.semantics { isTraversalGroup = true },
                content = {
                    for (col in 0 until HEATMAP_COLUMNS) {
                        val slot = row * HEATMAP_COLUMNS + col
                        val cell = grid.cells.getOrNull(slot)
                        if (cell == null || !cell.inYear) Spacer(Modifier) else {
                            val isSelected = selectedDate == cell.startDate
                            val range = remember(mode, cell) { PhotoHeatmapSelection(mode, cell.startDate, cell.endDate, cell.count) }
                            val description = remember(cell, mode, isSelected, today) {
                                buildString {
                                    if (cell is PhotoHeatmapDayCell) append(cell.description)
                                    else append("${heatmapSelectionLabel(range)}，${cell.count}张照片，热度${cell.level}级")
                                    if (mode == PhotoHeatmapGranularity.DAY && today == cell.startDate) append("，今天")
                                    if (isSelected) append("，已选择")
                                }
                            }
                            val action = if (cell.count == 0) Modifier else Modifier
                                .onFocusChanged { onFocus(cell.startDate, it.isFocused) }
                                .clickable { select(range) }
                            Spacer(Modifier.testTag("heatmap_cell_$slot").semantics {
                                contentDescription = description
                                selected = isSelected
                                collectionItemInfo = CollectionItemInfo(row, 1, col, 1)
                            }.then(action))
                        }
                    }
                },
            ) { measurables, constraints ->
                val tiles = measurables.mapIndexed { col, measurable ->
                    measurable.measure(Constraints.fixed(grid.x[col + 1] - grid.x[col], constraints.maxHeight))
                }
                layout(constraints.maxWidth, constraints.maxHeight) {
                    tiles.forEachIndexed { col, tile -> tile.place(grid.x[col] - grid.x[0], 0) }
                }
            }
        }
    }
}

@Composable
private fun needsCellNavigation(): Boolean {
    val manager = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    var enabled by remember(manager) { mutableStateOf(manager.isEnabled) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.AccessibilityStateChangeListener { enabled = it }
        manager.addAccessibilityStateChangeListener(listener)
        enabled = manager.isEnabled
        onDispose { manager.removeAccessibilityStateChangeListener(listener) }
    }
    return enabled || LocalInputModeManager.current.inputMode == InputMode.Keyboard
}

private class MatrixGrid(
    private val model: PhotoHeatmapYear,
    private val mode: PhotoHeatmapGranularity,
    private val density: Float,
    private val labelSize: Float,
) {
    val cells = model.cells(mode)
    val rows = (cells.size + HEATMAP_COLUMNS - 1) / HEATMAP_COLUMNS
    val x = IntArray(HEATMAP_COLUMNS + 1)
    val y = IntArray(rows + 1)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val tile = Rect()
    private val day = mode == PhotoHeatmapGranularity.DAY
    private val periodLabels = when (mode) {
        PhotoHeatmapGranularity.DAY -> emptyList()
        PhotoHeatmapGranularity.WEEK -> cells.map { "${it.startDate.monthValue}/${it.startDate.dayOfMonth}" }
        PhotoHeatmapGranularity.MONTH -> MONTH_NAMES
    }
    private val monthCounts = if (mode == PhotoHeatmapGranularity.MONTH) cells.map { "${it.count}张" } else emptyList()

    fun resize(width: Int, height: Int) {
        val left = if (day) minOf((labelSize * 2.6f).roundToInt(), width / 6) else 0
        val top = if (day) minOf((labelSize * 1.7f).roundToInt(), height / 6) else 0
        for (i in x.indices) x[i] = left + (width - left) * i / HEATMAP_COLUMNS
        for (i in y.indices) y[i] = top + (height - top) * i / rows
    }

    fun cellAt(px: Float, py: Float): PhotoHeatmapCell? {
        if (px < x.first() || py < y.first() || px >= x.last() || py >= y.last()) return null
        val slot = y.indexOfLast { it <= py } * HEATMAP_COLUMNS + x.indexOfLast { it <= px }
        return cells.getOrNull(slot)?.takeIf { it.inYear && it.count > 0 }
    }

    fun draw(canvas: Canvas, palette: HeatmapMatrixPalette, selectedDate: LocalDate?, today: LocalDate, focused: LocalDate?) {
        if (day) {
            text.color = palette.label
            text.textSize = minOf(labelSize, x.first() / 2.6f, y.first() / 1.5f)
            WEEKDAYS.forEachIndexed { col, name -> drawText(canvas, name, (x[col] + x[col + 1]) / 2f, y.first() / 2f) }
            for (month in model.monthLabels) {
                val row = month.startWeekIndex
                drawText(canvas, MONTH_NAMES[month.month.monthValue - 1], x.first() / 2f, (y[row] + y[row + 1]) / 2f)
            }
        }
        for ((slot, cell) in cells.withIndex()) {
            if (!cell.inYear) continue
            val row = slot / HEATMAP_COLUMNS
            val col = slot % HEATMAP_COLUMNS
            tile.set(x[col], y[row], x[col + 1], y[row + 1])
            if (tile.isEmpty) continue
            val gap = minOf(density, tile.height() / 8f, tile.width() / 8f)
            val radius = minOf(2f * density, tile.height() / 4f)
            val left = tile.left + gap
            val top = tile.top + gap
            val right = tile.right - gap
            val bottom = tile.bottom - gap
            fill.color = palette.levels[cell.level]
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, fill)
            val highlighted = cell.startDate == selectedDate || cell.startDate == focused
            if (highlighted || (day && cell.startDate == today)) {
                line.color = if (highlighted) palette.selection else palette.today
                line.strokeWidth = minOf((if (highlighted) 2f else 1f) * density, (bottom - top) / 3f, (right - left) / 3f)
                val inset = line.strokeWidth / 2f
                canvas.drawRoundRect(left + inset, top + inset, right - inset, bottom - inset, radius, radius, line)
            }
            if (!day) {
                text.color = palette.textOnLevel[cell.level]
                text.textSize = minOf(labelSize, tile.width() / 3.3f, tile.height() / 3f)
                val cy = tile.exactCenterY()
                val dy = if (mode == PhotoHeatmapGranularity.MONTH) text.textSize * 0.60f else 0f
                drawText(canvas, periodLabels[slot], tile.exactCenterX(), cy - dy)
                if (mode == PhotoHeatmapGranularity.MONTH) drawText(canvas, monthCounts[slot], tile.exactCenterX(), cy + dy)
            }
        }
    }

    private fun drawText(canvas: Canvas, value: String, cx: Float, cy: Float) =
        canvas.drawText(value, cx, cy - (text.ascent() + text.descent()) / 2f, text)

    private companion object {
        val WEEKDAYS = listOf("日", "一", "二", "三", "四", "五", "六")
        val MONTH_NAMES = (1..12).map { "${it}月" }
    }
}
