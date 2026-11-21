package com.jinof.apm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.withSaveLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

@Composable
internal fun PhotoWallScrubber(
    grid: LazyGridState,
    photoCount: Int,
    headerHeightPx: Int,
    gap: Dp,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.(isScrubbing: () -> Boolean) -> Unit,
) {
    var window by remember { mutableStateOf(IntSize.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(modifier.fillMaxSize().onGloballyPositioned { coordinates ->
        window = coordinates.findRootCoordinates().size
        val position = coordinates.positionInRoot()
        // Pager animation moves the whole page. Only the stationary window insets affect the track.
        val maxInset = (window.width - coordinates.size.width).coerceAtLeast(0).toFloat()
        origin = Offset(position.x.coerceIn(0f, maxInset), position.y)
    }, contentAlignment = AbsoluteAlignment.TopLeft) {
        val wall = this
        val density = LocalDensity.current
        val screenWidth = with(density) { if (window.width > 0) window.width.toDp() else maxWidth }
        val screenHeight = with(density) { if (window.height > 0) window.height.toDp() else maxHeight }
        val thumbWidth = (screenWidth * 0.04f).coerceAtMost(maxWidth)
        val thumbHeight = (screenHeight * 0.08f).coerceAtMost(maxHeight)
        val touchWidth = maxOf(48.dp, thumbWidth).coerceAtMost(maxWidth)
        val touchHeight = maxOf(48.dp, thumbHeight).coerceAtMost(maxHeight)
        val centerX = with(density) { screenWidth * 0.90f - origin.x.toDp() }
            .coerceIn(touchWidth / 2, maxWidth - touchWidth / 2)
        val minY = touchHeight / 2
        val maxY = maxHeight - minY
        val top = with(density) { screenHeight * 0.10f - origin.y.toDp() }.coerceIn(minY, maxY)
        val bottom = with(density) { screenHeight * 0.80f - origin.y.toDp() }.coerceIn(top, maxY)
        val travel = bottom - top

        val gapPx = with(density) { gap.roundToPx() }
        val range = remember(photoCount, headerHeightPx, constraints, gapPx) {
            PhotoWallScrollRange(photoCount, headerHeightPx, constraints.maxWidth, constraints.maxHeight, gapPx)
        }
        val progress by remember(grid, range) { derivedStateOf {
            range.fraction(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset, grid.canScrollBackward, grid.canScrollForward)
        } }
        val scope = rememberCoroutineScope()
        val seek by rememberUpdatedState<(Float) -> Unit> { fraction ->
            val target = range.target(fraction)
            scope.launch(start = CoroutineStart.UNDISPATCHED) { grid.scrollToItem(target.item, target.offset) }
        }
        val enabled = photoCount > 0 && travel > 0.dp && (grid.canScrollBackward || grid.canScrollForward)
        var dragFraction by remember(grid, photoCount) { mutableStateOf<Float?>(null) }
        val centerY = top + travel * (dragFraction ?: progress)
        val currentProgress by rememberUpdatedState(progress)
        val gesture = Modifier.pointerInput(grid, photoCount, enabled, centerX, top, travel, touchWidth, touchHeight) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(pass = PointerEventPass.Initial)
                val grabOffset = down.position.y - (top.toPx() + travel.toPx() * currentProgress)
                if (abs(down.position.x - centerX.toPx()) > touchWidth.toPx() / 2 ||
                    abs(grabOffset) > touchHeight.toPx() / 2) return@awaitEachGesture
                // A tap can still reach the search button where it crosses the track.
                try {
                    do {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) break
                        if (dragFraction != null || abs(change.position.y - down.position.y) > viewConfiguration.touchSlop) {
                            val position = ((change.position.y - grabOffset - top.toPx()) / travel.toPx()).coerceIn(0f, 1f)
                            change.consume()
                            if (position != dragFraction) {
                                dragFraction = position
                                seek(position)
                            }
                        }
                    } while (change.pressed)
                } finally {
                    dragFraction = null
                }
            }
        }
        Box(Modifier.fillMaxSize().then(gesture), contentAlignment = AbsoluteAlignment.TopLeft) {
            val glass = if (photoCount > 0) Modifier.diffuseBehindThumb(centerX, centerY, thumbWidth, thumbHeight) else Modifier
            Box(Modifier.fillMaxSize().then(glass)) { wall.content { dragFraction != null } }
            if (photoCount > 0) {
                Box(
                    Modifier.absoluteOffset(x = centerX - touchWidth / 2, y = centerY - touchHeight / 2)
                        .size(touchWidth, touchHeight).testTag("photo_wall_scrubber")
                        .then(if (enabled) Modifier.pointerInput(Unit) {
                            // The thumb owns this tap, so a tile beneath it cannot open the viewer.
                            awaitEachGesture { awaitFirstDown().consume() }
                        } else Modifier)
                        .semantics {
                            contentDescription = "照片墙滑动条"
                            stateDescription = "已滚动 ${(progress * 100).roundToInt()}%，共 $photoCount 张"
                            progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                            if (!enabled) disabled() else setProgress { value ->
                                if (!value.isFinite() || value.coerceIn(0f, 1f) == progress) return@setProgress false
                                seek(value.coerceIn(0f, 1f))
                                true
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { FrostedPhotoThumb(Modifier.size(thumbWidth, thumbHeight)) }
            }
            FilledTonalIconButton(onClick = onSearch,
                modifier = Modifier.align(AbsoluteAlignment.CenterRight).padding(horizontal = 4.dp)
                    .size(48.dp).testTag("open_album_search")) {
                Icon(Icons.Outlined.Search, contentDescription = "搜索照片")
            }
        }
    }
}

private fun Modifier.diffuseBehindThumb(x: Dp, y: Dp, width: Dp, height: Dp) = drawWithCache {
    val halfWidth = width.toPx() / 2f
    val halfHeight = height.toPx() / 2f
    val bounds = Rect(x.toPx() - halfWidth, y.toPx() - halfHeight, x.toPx() + halfWidth, y.toPx() + halfHeight)
    val pill = Path().apply { addRoundRect(RoundRect(bounds, CornerRadius(minOf(halfWidth, halfHeight)))) }
    val radius = minOf(2.dp.toPx(), halfWidth, halfHeight)
    val samples = listOf(Offset(-radius, 0f), Offset(radius, 0f), Offset(0f, -radius), Offset(0f, radius))
    val paint = Paint()
    onDrawWithContent {
        drawContent()
        // Reuse the recorded grid layer to average five neighboring samples inside the glass.
        clipPath(pill) {
            samples.forEachIndexed { index, offset ->
                paint.alpha = 1f / (index + 2)
                drawContext.canvas.withSaveLayer(bounds, paint) {
                    translate(offset.x, offset.y) { this@onDrawWithContent.drawContent() }
                }
            }
        }
    }
}.graphicsLayer()

@Composable
private fun FrostedPhotoThumb(modifier: Modifier) {
    val glass = remember {
        Brush.linearGradient(listOf(Color(0x85A9B1BA), Color(0x8059616A), Color(0x98535B63)))
    }
    Box(
        modifier.testTag("photo_wall_scrubber_thumb")
            .clip(CircleShape)
            .background(glass)
            .border(0.35.dp, Color.White.copy(alpha = 0.58f), CircleShape)
            .drawWithCache {
                val random = Random(0)
                val grainSize = 0.5.dp.toPx()
                val grain = List((size.width * size.height / (8f * grainSize * grainSize)).toInt()) {
                    Offset(random.nextFloat() * size.width, random.nextFloat() * size.height)
                }
                val halfWidth = minOf(size.width * 0.31f, size.height * 0.19f)
                val rise = minOf(halfWidth, size.height * 0.15f)
                val x = size.width / 2f
                val top = size.height * 0.25f
                val bottom = size.height - top
                val arrows = Path().apply {
                    moveTo(x - halfWidth, top + rise)
                    lineTo(x, top)
                    lineTo(x + halfWidth, top + rise)
                    moveTo(x - halfWidth, bottom - rise)
                    lineTo(x, bottom)
                    lineTo(x + halfWidth, bottom - rise)
                }
                val stroke = Stroke(width = halfWidth * 0.50f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                onDrawBehind {
                    drawPoints(grain, PointMode.Points, Color.White.copy(alpha = 0.18f), grainSize)
                    drawPath(arrows, Color.White, style = stroke)
                }
            },
    )
}
