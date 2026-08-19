package com.jinof.apm

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.semantics.SemanticsActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PhotoWallHeatmapActivityTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun showsGitHubAnnualDailyWeeklyAndMonthlyHeatmapsWithExactSelection() {
        assumeTrue(
            "This test creates temporary emulator images only when explicitly requested.",
            InstrumentationRegistry.getArguments().getString(ARGUMENT) == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val photos = createHeatmapPhotos(context)
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            ApmDatabase(context).use { database -> database.upsertAccessiblePhotos(photos) }
            scenario = ActivityScenario.launch(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            scrollUntilVisible("gallery_content_tabs")
            scrollUntilVisible("photo_wall_controls")
            composeRule.onNodeWithTag("photo_wall_controls").assertExists()
            composeRule.onNodeWithTag("photo_wall_heatmap").assertDoesNotExist()
            scrollBackUntilVisible("gallery_tab_heatmap")
            composeRule.onNodeWithTag("gallery_tab_heatmap")
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
                .assertIsSelected()
            scrollUntilVisible("heatmap_previous_year")
            composeRule.onNodeWithTag("photo_wall_controls").assertDoesNotExist()
            composeRule.onNodeWithTag("photo_wall_thumbnail_${photos.first().photoId}").assertDoesNotExist()

            (0..PhotoWallOrganizer.MAX_HEAT_LEVEL).forEach { level ->
                composeRule.onNodeWithTag("heatmap_guide_level_$level").assertExists()
            }
            composeRule.onNodeWithTag("heatmap_previous_year").performClick()
            composeRule.onNodeWithTag("heatmap_year_label").assertTextEquals("2039年")
            composeRule.onNodeWithTag("heatmap_next_year").performClick()
            composeRule.onNodeWithTag("heatmap_year_label").assertTextEquals("2040年")

            composeRule.onNodeWithTag("heatmap_day_2040-01-01").assertExists()
            composeRule.onNodeWithTag("heatmap_day_2040-12-31").assertExists()
            scrollUntilVisible("heatmap_day_2040-01-01")
            assertVerticallyOrdered("heatmap_day_2040-01-01", "heatmap_day_2040-01-08")
            scrollUntilVisible("heatmap_day_2040-08-14")
            composeRule.onNodeWithTag("heatmap_day_2040-08-14")
                .assertContentDescriptionEquals("2040年8月14日，4张照片，热度4级")
            composeRule.onNodeWithTag("heatmap_day_2040-08-15")
                .assertContentDescriptionEquals("2040年8月15日，2张照片，热度2级")
                .performClick()
            composeRule.onNodeWithTag("heatmap_day_2040-08-15")
                .assertContentDescriptionEquals("2040年8月15日，2张照片，热度2级，已选择")
            captureScreenshot("photo-wall-github-annual-day-vertical.png")
            scrollUntilVisible("selected_photo_range_summary")
            composeRule.onNodeWithTag("selected_photo_range_summary")
                .assertTextEquals("已选 2040年8月15日 · 2 张照片")
            composeRule.onNodeWithTag("view_selected_photos").performClick()
            composeRule.onNodeWithTag("photo_wall_heatmap").assertDoesNotExist()
            scrollBackUntilVisible("selected_photo_range_summary")
            composeRule.onNodeWithTag("selected_photo_range_summary")
                .assertTextEquals("只看 2040年8月15日 · 2 张照片")

            scrollBackUntilVisible("gallery_tab_heatmap")
            composeRule.onNodeWithTag("gallery_tab_heatmap")
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
                .assertIsSelected()
            scrollUntilVisible("heatmap_granularity_week")
            composeRule.onNodeWithTag("heatmap_granularity_week")
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            scrollUntilVisible("heatmap_week_2040-08-12")
            assertVerticallyOrdered("heatmap_week_2040-08-12", "heatmap_week_2040-08-19")
            composeRule.onNodeWithTag("heatmap_week_2040-08-12")
                .assertContentDescriptionEquals("2040年8月12日–18日，7张照片，热度4级")
                .performClick()
            captureScreenshot("photo-wall-github-annual-week-vertical.png")
            scrollUntilVisible("selected_photo_range_summary")
            composeRule.onNodeWithTag("selected_photo_range_summary")
                .assertTextEquals("已选 2040年8月12日–18日 · 7 张照片")
            scrollBackUntilVisible("heatmap_granularity_month")
            composeRule.onNodeWithTag("heatmap_granularity_month")
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            scrollUntilVisible("heatmap_month_01")
            assertVerticallyOrdered("heatmap_month_01", "heatmap_month_02")
            composeRule.onNodeWithTag("heatmap_month_12").assertExists()
            scrollUntilVisible("heatmap_month_08")
            composeRule.onNodeWithTag("heatmap_month_08")
                .assertContentDescriptionEquals("2040年8月，7张照片，热度4级")
                .performClick()
            captureScreenshot("photo-wall-github-annual-month-vertical.png")
            scrollUntilVisible("selected_photo_range_summary")
            composeRule.onNodeWithTag("selected_photo_range_summary")
                .assertTextEquals("已选 2040年8月 · 7 张照片")
            composeRule.onNodeWithTag("view_selected_photos").performClick()
            scrollBackUntilVisible("selected_photo_range_summary")
            composeRule.onNodeWithTag("selected_photo_range_summary")
                .assertTextEquals("只看 2040年8月 · 7 张照片")
        } finally {
            scenario?.close()
            photos.forEach { photo ->
                context.contentResolver.delete(android.net.Uri.parse(photo.uri), null, null)
            }
        }
    }

    private fun assertVerticallyOrdered(earlierTag: String, laterTag: String) {
        val earlierTop = composeRule.onNodeWithTag(earlierTag).fetchSemanticsNode().boundsInRoot.top
        val laterTop = composeRule.onNodeWithTag(laterTag).fetchSemanticsNode().boundsInRoot.top
        assertTrue("Expected $laterTag below $earlierTag, but $laterTop <= $earlierTop", laterTop > earlierTop)
    }

    private fun scrollUntilVisible(tag: String) {
        repeat(20) {
            composeRule.waitForIdle()
            if (isLaidOut(tag)) {
                return
            }
            val scrollNodes = composeRule.onAllNodes(hasScrollAction()).fetchSemanticsNodes()
            if (scrollNodes.isNotEmpty()) {
                composeRule.onAllNodes(hasScrollAction())[0].performTouchInput { swipeUp() }
            }
            Thread.sleep(250)
        }
        composeRule.onNodeWithTag(tag).assertExists()
    }

    private fun scrollBackUntilVisible(tag: String) {
        repeat(20) {
            composeRule.waitForIdle()
            if (isLaidOut(tag)) {
                return
            }
            val scrollNodes = composeRule.onAllNodes(hasScrollAction()).fetchSemanticsNodes()
            if (scrollNodes.isNotEmpty()) {
                composeRule.onAllNodes(hasScrollAction())[0].performTouchInput { swipeDown() }
            }
            Thread.sleep(250)
        }
        composeRule.onNodeWithTag(tag).assertExists()
    }

    private fun isLaidOut(tag: String): Boolean = composeRule
        .onAllNodesWithTag(tag)
        .fetchSemanticsNodes()
        .any { node -> node.boundsInRoot.width > 24f && node.boundsInRoot.height > 24f }

    private fun createHeatmapPhotos(context: Context): List<ScannedPhoto> {
        val days = listOf(14, 14, 14, 14, 15, 15, 16)
        return days.mapIndexed { index, day ->
            val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(40 + index * 20, 90 + index * 10, 150 - index * 8))
            }
            val bytes = ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
            bitmap.recycle()
            val displayName = "apm-heatmap-${UUID.randomUUID()}-$index.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/APM-Test")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = requireNotNull(
                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values),
            )
            context.contentResolver.openOutputStream(uri).use { output ->
                requireNotNull(output).write(bytes)
            }
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
            ScannedPhoto(
                uri = uri.toString(),
                mediaStoreId = ContentUris.parseId(uri),
                photoId = MessageDigest.getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { "%02x".format(it) },
                displayName = displayName,
                mediaType = "image/png",
                byteSize = bytes.size.toLong(),
                modifiedSeconds = System.currentTimeMillis() / 1_000L,
                dateTakenMillis = captured(day, 9 + index),
            )
        }
    }

    private fun captured(day: Int, hour: Int): Long =
        ZonedDateTime.of(2040, 8, day, hour, 0, 0, 0, ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun captureScreenshot(fileName: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val outputDir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        val outputFile = File(outputDir, fileName)
        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { output ->
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            try {
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
            } finally {
                screenshot.recycle()
            }
        }
    }

    private companion object {
        const val ARGUMENT = "verifyPhotoWallHeatmap"
    }
}
