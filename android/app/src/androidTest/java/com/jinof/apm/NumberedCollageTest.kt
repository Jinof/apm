package com.jinof.apm

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NumberedCollageTest {
    @Test fun allEightDistinctImagesHaveNumberedCellsAndUnusedCellStaysEmpty() {
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.CYAN, Color.MAGENTA, Color.WHITE, Color.GRAY)
        val loaded = mutableListOf<Bitmap>()
        val bitmap = NumberedCollage.render(8, 1024) { index, _ ->
            Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(colors[index]); loaded += this }
        }
        try {
            val grid = CollageLayout.create(8, 1024)
            assertEquals(grid.width, bitmap.width); assertEquals(grid.height, bitmap.height)
            for (index in 0..7) {
                val x = index % 3 * grid.cell; val y = index / 3 * grid.cell
                assertEquals(colors[index], bitmap.getPixel(x + grid.cell / 2, y + grid.cell / 2))
                assertTrue((y until y + grid.labelHeight).any { row ->
                    (x until x + grid.cell).any { column -> Color.red(bitmap.getPixel(column, row)) > 200 }
                })
            }
            assertEquals(Color.BLACK, bitmap.getPixel(grid.cell * 2 + grid.cell / 2, grid.cell * 2 + grid.cell / 2))
            assertTrue(loaded.all { it.isRecycled })
            save(bitmap, "eight-cell-layout.png")
        } finally { bitmap.recycle() }
    }

    @Test fun wideAndTallPhotosRetainColoredEdgesWithoutCroppingOrStretching() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = NumberedCollage.render(2, 1024) { index, _ ->
            val w = if (index == 0) 800 else 100; val h = if (index == 0) 100 else 800
            val source = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                val canvas = Canvas(this);val p = Paint().apply { color = Color.RED }
                canvas.drawRect(0f, 0f, w * .2f, h.toFloat(), p)
                p.color = Color.BLUE;canvas.drawRect(w * .8f, 0f, w.toFloat(), h.toFloat(), p)
            }
            val file = File.createTempFile("collage-aspect-", ".png", context.cacheDir)
            try {
                file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val original = file.readBytes()
                NumberedCollage.loadPhoto(context.contentResolver, android.net.Uri.fromFile(file), 512).also {
                    assertEquals(if (index == 0) 512 else 64, it.width)
                    assertEquals(if (index == 0) 64 else 512, it.height)
                    assertArrayEquals(original, file.readBytes())
                }
            } finally { source.recycle(); file.delete() }
        }
        try {
            val grid = CollageLayout.create(2, 1024)
            for (index in 0..1) {
                val x = index * grid.cell
                val y = (grid.labelHeight + grid.cell) / 2
                val nonBlack = (x until x + grid.cell).filter { bitmap.getPixel(it, y) != Color.BLACK }
                assertTrue(nonBlack.isNotEmpty())
                assertEquals(Color.RED, bitmap.getPixel(nonBlack[nonBlack.size / 10], y))
                assertEquals(Color.BLUE, bitmap.getPixel(nonBlack[nonBlack.size * 9 / 10], y))
                if (index == 0) assertTrue(nonBlack.size > 400) else assertTrue(nonBlack.size < 100)
            }
            save(bitmap, "collage-aspect-ratios.png")
        } finally { bitmap.recycle() }
    }

    @Test fun androidParserRejectsInvalidSlotsAndMapsReversedResults() {
        val one = org.json.JSONObject("""{"caption":"第一张","tags":[],"visible_text":"","facets":{"daylight":"不确定","sky":[],"objects":[],"people":[],"actions":[],"scenes":[],"weather":[]}}""")
        val response = org.json.JSONObject().put("photos", org.json.JSONArray()
            .put(org.json.JSONObject().put("slot", 2).put("annotation", org.json.JSONObject(one.toString()).put("caption", "第二张")))
            .put(org.json.JSONObject().put("slot", 1).put("annotation", one)))
        assertEquals(listOf("第一张", "第二张"), BatchAnnotationContract.parse(response, 2).map { it.caption })
        for (bad in listOf<Any>("2", 1.5, 3, 1)) {
            response.getJSONArray("photos").getJSONObject(0).put("slot", bad)
            assertThrows(RuntimeException::class.java) { BatchAnnotationContract.parse(response, 2) }
        }
    }

    @Test fun exportEightCellFixtureUsingProductionEncoderAndRequestContract() {
        val words = listOf("ALPHA", "BRAVO", "CHARLIE", "DELTA", "ECHO", "FOXTROT", "GOLF", "HOTEL")
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.rgb(255, 140, 0), Color.MAGENTA, Color.CYAN, Color.rgb(160, 80, 20), Color.BLACK)
        val encoded = NumberedCollage.encode(8, 1024) { index, _ ->
            Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                val canvas = Canvas(this)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 44f; textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD }
                canvas.drawText(words[index], 192f, 62f, p)
                p.color = colors[index]
                repeat(index + 1) { dot -> canvas.drawCircle(80f + (dot % 3) * 110f, 138f + (dot / 3) * 96f, 28f, p) }
            }
        }
        assertEquals(1023, encoded.width);assertEquals(1023, encoded.height)
        val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        File(dir).mkdirs()
        File(dir, "live-collage-request.json").writeText(AnnotationRequestFactory.createBatch("fixture-model", encoded.base64, 8).toString())
        File(dir, "live-collage-eight.jpg").writeBytes(android.util.Base64.decode(encoded.base64, android.util.Base64.NO_WRAP))
    }

    private fun save(bitmap: Bitmap, name: String) {
        val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        val file = File(dir, name);file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
