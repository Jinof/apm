package com.jinof.apm

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class CollageLayout(val count: Int, val columns: Int, val rows: Int, val cell: Int) {
    val width get() = columns * cell
    val height get() = rows * cell
    val labelHeight get() = (cell / 10).coerceAtLeast(16)
    val padding get() = (cell / 64).coerceAtLeast(2)
    companion object {
        fun create(count: Int, maxEdge: Int): CollageLayout {
            require(count in 1..8 && maxEdge >= 192)
            val columns = ceil(sqrt(count.toDouble())).toInt()
            val rows = (count + columns - 1) / columns
            return CollageLayout(count, columns, rows, maxEdge / maxOf(columns, rows))
        }
    }
}

/** One bounded canvas and one decoded thumbnail at a time; never writes source images or files. */
internal object NumberedCollage {
    fun loadPhoto(resolver: ContentResolver, uri: Uri, maxEdge: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            // Decode the source at a bounded size instead of accepting a provider's possibly cropped preview.
            val scale = minOf(1.0, maxEdge.toDouble() / maxOf(info.size.width, info.size.height))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1),
                (info.size.height * scale).roundToInt().coerceAtLeast(1))
        }

    fun render(count: Int, maxEdge: Int, load: (index: Int, edge: Int) -> Bitmap): Bitmap {
        val grid = CollageLayout.create(count, maxEdge)
        val output = Bitmap.createBitmap(grid.width, grid.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            canvas.drawColor(Color.BLACK)
            val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = grid.labelHeight * 0.8f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            for (index in 0 until count) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val x = (index % grid.columns) * grid.cell
                val y = (index / grid.columns) * grid.cell
                val fm = labelPaint.fontMetrics
                canvas.drawText("#${index + 1}", x + grid.cell / 2f,
                    y + grid.labelHeight / 2f - (fm.ascent + fm.descent) / 2f, labelPaint)
                val available = RectF((x + grid.padding).toFloat(), (y + grid.labelHeight + grid.padding).toFloat(),
                    (x + grid.cell - grid.padding).toFloat(), (y + grid.cell - grid.padding).toFloat())
                val source = load(index, grid.cell)
                try {
                    val scale = minOf(available.width() / source.width, available.height() / source.height)
                    val width = source.width * scale
                    val height = source.height * scale
                    val left = available.centerX() - width / 2
                    val top = available.centerY() - height / 2
                    canvas.drawBitmap(source, null, RectF(left, top, left + width, top + height), imagePaint)
                } finally { source.recycle() }
            }
            return output
        } catch (error: Throwable) {
            output.recycle()
            throw error
        }
    }

    fun encode(count: Int, maxEdge: Int, load: (Int, Int) -> Bitmap): EncodedVlmImage {
        val collage = render(count, maxEdge, load)
        return try { VlmThumbnailEncoder.encode(collage, maxEdge) } finally { collage.recycle() }
    }
}
