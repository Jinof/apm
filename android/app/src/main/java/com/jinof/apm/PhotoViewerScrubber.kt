package com.jinof.apm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

@Composable
internal fun PhotoViewerScrubber(
    selectedIndex: Int,
    photoCount: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = AbsoluteAlignment.TopLeft) {
        if (selectedIndex !in 0 until photoCount) {
            Box(Modifier.fillMaxSize(), content = content)
            return@BoxWithConstraints
        }
        val thumbWidth = maxWidth * 0.01f
        val thumbHeight = maxHeight * 0.02f
        val touchWidth = maxOf(48.dp, thumbWidth)
        val touchHeight = maxOf(48.dp, thumbHeight)
        val travel = maxHeight * 0.70f
        val travelPx = with(LocalDensity.current) { travel.toPx() }
        var dragFraction by remember(photoCount, travelPx) { mutableStateOf<Float?>(null) }
        val selectedFraction = PhotoViewerNavigator.scrubFraction(selectedIndex, photoCount)
        val currentFraction by rememberUpdatedState(selectedFraction)
        val selectPhoto by rememberUpdatedState(onSelect)
        val fraction = dragFraction ?: selectedFraction
        val centerX = maxWidth * 0.90f
        val centerY = maxHeight * 0.10f + travel * fraction

        val drag = Modifier.pointerInput(photoCount, travelPx, touchWidth, touchHeight) {
            if (photoCount == 1 || travelPx <= 0f) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(pass = PointerEventPass.Initial)
                val trackTop = size.height * 0.10f
                val grabOffset = down.position.y - (trackTop + currentFraction * travelPx)
                if (abs(down.position.x - size.width * 0.90f) > touchWidth.toPx() / 2f ||
                    abs(grabOffset) > touchHeight.toPx() / 2f
                ) return@awaitEachGesture
                down.consume()
                try {
                    do {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                            ?: break
                        if (change.isConsumed) break
                        // Coordinates stay in the viewport while the thumb and the displayed photo move.
                        val position = ((change.position.y - grabOffset - trackTop) / travelPx).coerceIn(0f, 1f)
                        dragFraction = position
                        selectPhoto(PhotoViewerNavigator.indexAtFraction(position, photoCount))
                        change.consume()
                    } while (change.pressed)
                } finally {
                    dragFraction = null
                }
            }
        }
        Box(Modifier.fillMaxSize().then(drag), contentAlignment = AbsoluteAlignment.TopLeft) {
            Box(
                Modifier.fillMaxSize().diffuseBehindThumb(centerX, centerY, thumbWidth, thumbHeight),
                content = content,
            )
            Box(
                modifier = Modifier
                    .absoluteOffset(x = centerX - touchWidth / 2, y = centerY - touchHeight / 2)
                    .size(touchWidth, touchHeight)
                    .testTag("photo_viewer_scrubber")
                    .semantics {
                        contentDescription = "全屏相册滑动条"
                        stateDescription = "第 ${selectedIndex + 1} 张，共 ${photoCount} 张"
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            (selectedIndex + 1).toFloat(), 1f..photoCount.toFloat(), (photoCount - 2).coerceAtLeast(0),
                        )
                        if (photoCount == 1) disabled() else setProgress { position ->
                            if (!position.isFinite()) return@setProgress false
                            val index = position.roundToInt().coerceIn(1, photoCount) - 1
                            if (index != selectedIndex) onSelect(index)
                            index != selectedIndex
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                FrostedPhotoThumb(Modifier.size(thumbWidth, thumbHeight))
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
        // Reuse the recorded viewer layer to average five neighboring samples inside the glass.
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
        modifier.testTag("photo_viewer_scrubber_thumb")
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
