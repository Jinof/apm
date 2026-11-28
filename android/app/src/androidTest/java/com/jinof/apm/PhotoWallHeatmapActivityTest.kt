package com.jinof.apm

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
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
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PhotoWallHeatmapActivityTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun dockTapAnimatesPagerInPlaceWithoutMovingDockOrOpeningActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            waitForDisplayedTag("dock_page_swipe_album")
            composeRule.onRoot().saveRoadmapScreenshot("startup-home.png")
            val pagerBounds = composeRule.onNodeWithTag("dock_horizontal_pager")
                .fetchSemanticsNode().boundsInRoot
            val dockBefore = composeRule.onNodeWithTag("bottom_dock")
                .fetchSemanticsNode().boundsInRoot

            composeRule.mainClock.autoAdvance = false
            composeRule.onNodeWithTag("dock_heatmap").performClick()
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.mainClock.advanceTimeBy(160)

            val albumDuring = composeRule.onNodeWithTag("dock_page_swipe_album")
                .fetchSemanticsNode().boundsInRoot
            val heatmapDuring = composeRule.onNodeWithTag("dock_page_swipe_heatmap")
                .fetchSemanticsNode().boundsInRoot
            val dockDuring = composeRule.onNodeWithTag("bottom_dock")
                .fetchSemanticsNode().boundsInRoot
            assertTrue(
                "album should remain partially visible during the transition: $albumDuring",
                albumDuring.right > pagerBounds.left,
            )
            assertTrue(
                "album should be clipped while moving left: $albumDuring",
                albumDuring.width > 0f && albumDuring.width < pagerBounds.width,
            )
            assertTrue(
                "heatmap should enter the viewport during the transition: $heatmapDuring",
                heatmapDuring.left < pagerBounds.right,
            )
            assertTrue(
                "heatmap should be clipped while entering: $heatmapDuring",
                heatmapDuring.width > 0f && heatmapDuring.width < pagerBounds.width,
            )
            assertEquals(dockBefore.top, dockDuring.top, 1f)
            assertEquals(dockBefore.bottom, dockDuring.bottom, 1f)
            assertOnlyMainActivityResumed()

            composeRule.mainClock.advanceTimeBy(2_000)
            composeRule.mainClock.autoAdvance = true
            waitForSettledPage("dock_page_swipe_heatmap")
            composeRule.onNodeWithTag("dock_heatmap").assertIsSelected()
            assertOnlyMainActivityResumed()
        } finally {
            composeRule.mainClock.autoAdvance = true
            scenario?.close()
        }
    }

    @Test
    fun latestDockTapInterruptsInFlightAnimationWithoutQueuingStaleTarget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            waitForDisplayedTag("dock_page_swipe_album")
            waitForSettledPage("dock_page_swipe_album")
            val dockBefore = composeRule.onNodeWithTag("bottom_dock")
                .fetchSemanticsNode().boundsInRoot

            composeRule.mainClock.autoAdvance = false
            composeRule.onNodeWithTag("dock_settings").performClick()
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.mainClock.advanceTimeBy(80)
            composeRule.onNodeWithTag("dock_heatmap").performClick()
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.mainClock.advanceTimeBy(650)
            composeRule.waitForIdle()

            assertTrue(
                "latest Dock target should settle without waiting for settings animation",
                isSettledPage("dock_page_swipe_heatmap"),
            )
            composeRule.onNodeWithTag("dock_heatmap").assertIsSelected()
            val dockAfter = composeRule.onNodeWithTag("bottom_dock")
                .fetchSemanticsNode().boundsInRoot
            assertEquals(dockBefore.top, dockAfter.top, 1f)
            assertEquals(dockBefore.bottom, dockAfter.bottom, 1f)
            assertOnlyMainActivityResumed()
        } finally {
            composeRule.mainClock.autoAdvance = true
            scenario?.close()
        }
    }

    @Test
    fun pageSurfaceSwipeMovesSelectedDockAcrossTopLevelDestinations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            waitForDisplayedTag("dock_page_swipe_album")
            composeRule.onNodeWithTag("dock_album").assertIsSelected()

            composeRule.onNodeWithTag("dock_page_swipe_album").performTouchInput { swipeLeft() }
            waitForSettledPage("dock_page_swipe_heatmap")
            composeRule.onNodeWithTag("dock_heatmap").assertIsSelected()
            composeRule.onNodeWithTag("heatmap_back").assertDoesNotExist()
            assertOnlyMainActivityResumed()

            composeRule.onNodeWithTag("dock_page_swipe_heatmap").performTouchInput { swipeLeft() }
            waitForSettledPage("dock_page_swipe_agent")
            composeRule.onNodeWithTag("dock_agent").assertIsSelected()
            composeRule.onNodeWithTag("agent_back").assertDoesNotExist()
            assertOnlyMainActivityResumed()

            composeRule.onNodeWithTag("dock_page_swipe_agent").performTouchInput { swipeLeft() }
            waitForSettledPage("dock_page_swipe_settings")
            composeRule.onNodeWithTag("dock_settings").assertIsSelected()
            composeRule.onNodeWithTag("settings_back").assertDoesNotExist()
            assertOnlyMainActivityResumed()

            composeRule.onNodeWithTag("dock_page_swipe_settings").performTouchInput { swipeRight() }
            waitForSettledPage("dock_page_swipe_agent")
            composeRule.onNodeWithTag("dock_agent").assertIsSelected()

            composeRule.onNodeWithTag("dock_page_swipe_agent").performTouchInput { swipeRight() }
            waitForSettledPage("dock_page_swipe_heatmap")
            composeRule.onNodeWithTag("dock_heatmap").assertIsSelected()

            composeRule.onNodeWithTag("dock_page_swipe_heatmap").performTouchInput { swipeRight() }
            waitForSettledPage("dock_page_swipe_album")
            composeRule.onNodeWithTag("dock_album").assertIsSelected()
            assertOnlyMainActivityResumed()
        } finally {
            scenario?.close()
        }
    }

    @Test
    fun settingsDraftSurvivesAnimatedPagerRoundTrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            scenario = ActivityScenario.launch(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            waitForDisplayedTag("dock_page_swipe_album")
            composeRule.onNodeWithTag("dock_settings").performClick()
            waitForSettledPage("dock_page_swipe_settings")
            composeRule.onNodeWithTag("edit_profile_default").performClick()
            composeRule.onNodeWithTag("model_input").performScrollTo().performTextReplacement("pager-draft-model")

            composeRule.onNodeWithTag("dock_page_swipe_settings").performTouchInput { swipeRight() }
            waitForSettledPage("dock_page_swipe_agent")
            composeRule.onNodeWithTag("dock_page_swipe_agent").performTouchInput { swipeLeft() }
            waitForSettledPage("dock_page_swipe_settings")

            composeRule.onNodeWithTag("model_input").assertTextContains("pager-draft-model")
            composeRule.onNodeWithTag("dock_settings").assertIsSelected()
            assertOnlyMainActivityResumed()
            // The retained editor must not handle Back while another Dock page is visible.
            composeRule.onNodeWithTag("dock_page_swipe_settings").performTouchInput { swipeRight() }
            waitForSettledPage("dock_page_swipe_agent")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitForSettledPage("dock_page_swipe_album")
            composeRule.onNodeWithTag("discard_profile_changes").assertDoesNotExist()
            composeRule.onNodeWithTag("dock_settings").performClick()
            waitForSettledPage("dock_page_swipe_settings")
            composeRule.onNodeWithTag("model_input").assertTextContains("pager-draft-model")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.onNodeWithTag("discard_profile_changes").performClick()
            composeRule.onNodeWithTag("add_api_profile").assertIsDisplayed()
            composeRule.onNodeWithTag("dock_settings").assertIsSelected()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitForSettledPage("dock_page_swipe_album")
        } finally {
            scenario?.close()
        }
    }

    @Test
    fun showsSevenColumnAnnualHeatmapsWithExactSelection() {
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
            waitForDisplayedTag("dock_page_swipe_album")
            composeRule.onNodeWithTag("startup_story_dialog").assertDoesNotExist()
            composeRule.onNodeWithTag("photo_wall_heatmap").assertDoesNotExist()
            composeRule.onNodeWithTag("select_annotation_photos").assertDoesNotExist()
            composeRule.onNodeWithTag("search_input").assertDoesNotExist()
            val tiles = photos.sortedByDescending { it.dateTakenMillis }.map {
                composeRule.onNodeWithTag("photo_wall_thumbnail_${it.photoId}").fetchSemanticsNode().boundsInRoot
            }
            assertTrue(tiles.take(5).all { abs(it.top - tiles.first().top) < 1f })
            assertTrue(tiles.take(5).zipWithNext().all { (a, b) -> b.left >= a.right })
            assertTrue(tiles[5].top >= tiles[0].bottom && abs(tiles[5].left - tiles[0].left) < 1f)
            val wall = composeRule.onNodeWithTag("photo_wall_gesture_surface").fetchSemanticsNode().boundsInRoot
            val search = composeRule.onNodeWithTag("open_album_search").fetchSemanticsNode().boundsInRoot
            assertEquals(wall.center.y, search.center.y, 1f)
            assertTrue(search.right > wall.right - search.width / 2)
            composeRule.onNodeWithTag("open_album_search").performClick()
            composeRule.onNodeWithTag("search_input").assertIsDisplayed()
            composeRule.onNodeWithTag("close_album_search").performClick()
            composeRule.onNodeWithTag("dock_agent").performClick()
            waitForSettledPage("dock_page_swipe_agent")
            composeRule.onNodeWithTag("agent_title").assertTextEquals("Agent")
            assertVerticallyOrdered("select_annotation_photos", "annotate_selected")
            assertVerticallyOrdered("annotate_selected", "annotate_all")
            composeRule.onNodeWithTag("dock_album").performClick()
            waitForSettledPage("dock_page_swipe_album")
            composeRule.onNodeWithTag("bottom_dock").assertExists()
            composeRule.onNodeWithTag("bottom_dock")
                .assertContentDescriptionEquals("固定底部 Dock：相册、热力图、Agent、设置；页面跟随左右滑动并平滑切换")
            composeRule.onNodeWithTag("dock_album")
                .assertContentDescriptionEquals("相册首页")
            composeRule.onNodeWithTag("dock_heatmap").assertExists()
            composeRule.onNodeWithTag("dock_settings").assertExists()
            composeRule.onNodeWithTag("dock_identity").assertDoesNotExist()
            composeRule.onNodeWithTag("check_similar").assertDoesNotExist()
            composeRule.onNodeWithTag("dock_agent").assertExists()
            composeRule.onNodeWithTag("open_identity").assertDoesNotExist()
            composeRule.onNodeWithTag("open_agent").assertDoesNotExist()
            composeRule.onNodeWithTag("open_settings").assertDoesNotExist()
            composeRule.onNodeWithTag("open_heatmap_page").assertDoesNotExist()
            assertDockOrder("dock_album", "dock_heatmap", "dock_agent", "dock_settings")
            composeRule.onNodeWithTag("photo_wall_gesture_surface")
                .assertContentDescriptionEquals("照片墙，从左边缘向右滑动打开年度照片热力图")
                .performTouchInput {
                    swipeRight(startX = 2f, endX = 90f)
                }
            waitForSettledPage("dock_page_swipe_heatmap")
            composeRule.onNodeWithTag("heatmap_back").assertDoesNotExist()
            scenario?.onActivity { activity -> activity.onBackPressedDispatcher.onBackPressed() }
            waitForSettledPage("dock_page_swipe_album")
            composeRule.onNodeWithTag("dock_page_swipe_album").performTouchInput { swipeLeft() }
            waitForSettledPage("dock_page_swipe_heatmap")
            scenario?.onActivity { activity -> activity.onBackPressedDispatcher.onBackPressed() }
            waitForSettledPage("dock_page_swipe_album")
            composeRule.onNodeWithTag("dock_page_swipe_album").performTouchInput { swipeRight() }
            composeRule.onNodeWithTag("bottom_dock").assertExists()
            composeRule.onNodeWithTag("dock_heatmap").performClick()
            waitForSettledPage("dock_page_swipe_heatmap")
            composeRule.onNodeWithTag("heatmap_time_controls").assertExists()
            composeRule.onNodeWithTag("heatmap_side_controls").assertDoesNotExist()
            assertNoVisiblePageScrollInsidePager()
            assertHeatmapFillsContent()
            assertInHeatmapViewport("heatmap_time_controls")
            assertInHeatmapViewport("heatmap_previous_year")
            assertInHeatmapViewport("heatmap_granularity_month")
            composeRule.onNodeWithTag("photo_wall_controls").assertIsNotDisplayed()
            composeRule.onNodeWithTag("photo_wall_thumbnail_${photos.first().photoId}").assertIsNotDisplayed()

            (0..PhotoWallOrganizer.MAX_HEAT_LEVEL).forEach { level ->
                composeRule.onNodeWithTag("heatmap_guide_level_$level").assertExists()
            }
            composeRule.onNodeWithTag("heatmap_previous_year").performClick()
            composeRule.onNodeWithTag("heatmap_year_label").assertTextEquals("2039年")
            InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            assertMatrix(2039, PhotoHeatmapGranularity.DAY)
            composeRule.onNodeWithTag("heatmap_next_year").performClick()
            composeRule.onNodeWithTag("heatmap_year_label").assertTextEquals("2040年")

            assertMatrix(2040, PhotoHeatmapGranularity.DAY)
            val august14 = LocalDate.of(2040, 8, 14).dayOfYear - 1 // January 1 was a Sunday.
            composeRule.onNodeWithTag("heatmap_cell_$august14")
                .assertContentDescriptionEquals("2040年8月14日，4张照片，热度4级")
            selectMatrixCell(august14 + 1, "2040年8月15日，2张照片，热度2级", touch = true)
            captureScreenshot("photo-wall-seven-column-day.png")
            composeRule.onNodeWithTag("heatmap_matrix").performTouchInput { swipeLeft() }
            waitForSettledPage("dock_page_swipe_agent")
            composeRule.onNodeWithTag("dock_heatmap").performClick()
            waitForSettledPage("dock_page_swipe_heatmap")
            scrollUntilVisible("selected_photo_range_summary")
            displayedNodeWithTag("selected_photo_range_summary")
                .assertTextEquals("已选 2040年8月15日 · 2 张照片")
            composeRule.onNodeWithTag("view_selected_photos").performClick()
            waitForSettledPage("dock_page_swipe_album")
            composeRule.onNodeWithTag("photo_wall_heatmap").assertIsNotDisplayed()
            composeRule.onNodeWithTag("bottom_dock").assertExists()
            displayedNodeWithTag("selected_photo_range_summary")
                .assertTextEquals("只看 2040年8月15日 · 2 张照片")

            composeRule.onNodeWithTag("dock_heatmap").performClick()
            waitForSettledPage("dock_page_swipe_heatmap")
            composeRule.onNodeWithTag("heatmap_granularity_week").assertExists()
            composeRule.onNodeWithTag("heatmap_granularity_week")
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            assertMatrix(2040, PhotoHeatmapGranularity.WEEK)
            selectMatrixCell((LocalDate.of(2040, 8, 12).dayOfYear - 1) / 7,
                "2040年8月12日–18日，7张照片，热度4级", touch = false)
            captureScreenshot("photo-wall-seven-column-week.png")
            displayedNodeWithTag("selected_photo_range_summary")
                .assertTextEquals("已选 2040年8月12日–18日 · 7 张照片")
            composeRule.onNodeWithTag("heatmap_granularity_month").assertExists()
            composeRule.onNodeWithTag("heatmap_granularity_month")
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            assertMatrix(2040, PhotoHeatmapGranularity.MONTH)
            selectMatrixCell(7, "2040年8月，7张照片，热度4级", touch = true)
            captureScreenshot("photo-wall-seven-column-month.png")
            displayedNodeWithTag("selected_photo_range_summary")
                .assertTextEquals("已选 2040年8月 · 7 张照片")
            composeRule.onNodeWithTag("view_selected_photos").performClick()
            waitForSettledPage("dock_page_swipe_album")
            scrollBackUntilVisible("selected_photo_range_summary")
            displayedNodeWithTag("selected_photo_range_summary")
                .assertTextEquals("只看 2040年8月 · 7 张照片")
        } finally {
            scenario?.close()
            photos.forEach { photo ->
                context.contentResolver.delete(android.net.Uri.parse(photo.uri), null, null)
            }
        }
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
        displayedNodeWithTag(tag).assertExists()
    }

    private fun waitForDisplayedTag(tag: String) {
        composeRule.waitUntil(8_000) {
            runCatching {
                displayedNodeWithTag(tag).assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
    }

    private fun waitForSettledPage(tag: String) {
        composeRule.waitUntil(8_000) {
            isSettledPage(tag)
        }
    }

    private fun isSettledPage(tag: String): Boolean = runCatching {
        val pager = composeRule.onNodeWithTag("dock_horizontal_pager")
            .fetchSemanticsNode().boundsInRoot
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().any { node ->
            val bounds = node.boundsInRoot
            abs(bounds.left - pager.left) <= 1f &&
                abs(bounds.right - pager.right) <= 1f &&
                bounds.height > 0f
        }
    }.getOrDefault(false)

    private fun assertOnlyMainActivityResumed() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val resumed = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .map { it::class.java }
            assertEquals(listOf(MainActivity::class.java), resumed)
        }
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
        displayedNodeWithTag(tag).assertExists()
    }

    private fun isLaidOut(tag: String): Boolean = runCatching {
        displayedNodeWithTag(tag).assertIsDisplayed()
        true
    }.getOrDefault(false)

    private fun displayedNodeWithTag(tag: String): SemanticsNodeInteraction {
        val nodes = composeRule.onAllNodesWithTag(tag)
        val count = nodes.fetchSemanticsNodes().size
        val index = (0 until count).firstOrNull { candidate ->
            runCatching {
                nodes[candidate].assertIsDisplayed()
                true
            }.getOrDefault(false)
        } ?: error("No displayed node found for tag $tag")
        return nodes[index]
    }

    private fun assertVerticallyOrdered(earlierTag: String, laterTag: String) {
        val earlierTop = composeRule.onNodeWithTag(earlierTag).fetchSemanticsNode().boundsInRoot.top
        val laterTop = composeRule.onNodeWithTag(laterTag).fetchSemanticsNode().boundsInRoot.top
        assertTrue("Expected $laterTag below $earlierTag, but $laterTop <= $earlierTop", laterTop > earlierTop)
    }

    private fun assertMatrix(year: Int, mode: PhotoHeatmapGranularity) {
        val jan1 = LocalDate.of(year, 1, 1)
        val leading = jan1.dayOfWeek.value % 7
        val weeks = (leading + jan1.lengthOfYear() + 6) / 7
        val dates = when (mode) {
            PhotoHeatmapGranularity.DAY -> (0 until jan1.lengthOfYear()).associate { leading + it to jan1.plusDays(it.toLong()) }
            PhotoHeatmapGranularity.WEEK -> (0 until weeks).associateWith { maxOf(jan1, jan1.plusDays((it * 7 - leading).toLong())) }
            PhotoHeatmapGranularity.MONTH -> (0 until 12).associateWith { jan1.plusMonths(it.toLong()) }
        }
        val matcher = SemanticsMatcher("date cells") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("heatmap_cell_") == true }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(matcher).fetchSemanticsNodes().size == dates.size
        }
        val nodes = composeRule.onAllNodes(matcher).fetchSemanticsNodes().associateBy {
            it.config[SemanticsProperties.TestTag].substringAfterLast('_').toInt()
        }
        assertEquals(dates.keys, nodes.keys)
        val matrix = composeRule.onNodeWithTag("heatmap_matrix").fetchSemanticsNode()
        val collection = matrix.config[SemanticsProperties.CollectionInfo]
        assertEquals(7, collection.columnCount)
        assertEquals(dates.keys.max() / 7 + 1, collection.rowCount)
        val bounds = mutableMapOf<Int, Rect>()
        for ((slot, date) in dates) {
            val node = nodes.getValue(slot)
            val prefix = if (mode == PhotoHeatmapGranularity.MONTH) "${year}年${date.monthValue}月，" else date.format(heatmapDayFormat)
            val description = node.config[SemanticsProperties.ContentDescription].single()
            assertTrue(description, description.startsWith(prefix))
            val item = node.config[SemanticsProperties.CollectionItemInfo]
            assertEquals(slot / 7, item.rowIndex)
            assertEquals(slot % 7, item.columnIndex)
            if (description.contains("，0张照片")) assertTrue(SemanticsActions.OnClick !in node.config)
            val rect = node.boundsInRoot
            assertTrue(rect.width > 0 && rect.height > 0 && matrix.boundsInRoot.contains(rect.center))
            if (slot % 7 != 0) bounds[slot - 1]?.let {
                assertEquals(it.right, rect.left, 1f)
                assertEquals(it.top, rect.top, 1f)
            }
            bounds[slot - 7]?.let {
                assertEquals(it.left, rect.left, 1f)
                assertEquals(it.bottom, rect.top, 1f)
            }
            bounds[slot] = rect
        }
    }

    private fun selectMatrixCell(slot: Int, description: String, touch: Boolean) {
        val cell = composeRule.onNodeWithTag("heatmap_cell_$slot").assertContentDescriptionEquals(description)
        if (touch) {
            val matrix = composeRule.onNodeWithTag("heatmap_matrix")
            val point = cell.fetchSemanticsNode().boundsInRoot.center - matrix.fetchSemanticsNode().boundsInRoot.topLeft
            matrix.performTouchInput { click(point) }
        } else {
            cell.performSemanticsAction(SemanticsActions.OnClick) { it() }
        }
        cell.assertContentDescriptionEquals("$description，已选择").assertIsSelected()
    }

    private fun assertDockOrder(vararg tags: String) {
        val leftEdges = tags.map { tag ->
            composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.left
        }
        assertTrue("Bottom Dock order changed: $leftEdges", leftEdges.zipWithNext().all { (left, right) -> left < right })
    }

    private fun assertInHeatmapViewport(tag: String) {
        val pageBounds = composeRule.onNodeWithTag("photo_heatmap_page").fetchSemanticsNode().boundsInRoot
        val nodeBounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertTrue("$tag has no laid-out bounds", nodeBounds.width > 0f && nodeBounds.height > 0f)
        assertTrue("$tag is above the one-screen viewport", nodeBounds.top >= pageBounds.top)
        assertTrue("$tag is below the one-screen viewport", nodeBounds.bottom <= pageBounds.bottom)
    }

    private fun assertHeatmapFillsContent() {
        val contentBounds = composeRule.onNodeWithTag("photo_heatmap_content").fetchSemanticsNode().boundsInRoot
        val heatmapBounds = composeRule.onNodeWithTag("photo_wall_heatmap").fetchSemanticsNode().boundsInRoot
        val matrixBounds = composeRule.onNodeWithTag("heatmap_matrix")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("borderless heatmap should use the content width", heatmapBounds.width >= contentBounds.width * 0.98f)
        assertTrue("seven-column grid should use the full heatmap width", matrixBounds.width >= contentBounds.width * 0.98f)
        assertTrue(
            "seven-column grid should consume most available heatmap height",
            matrixBounds.height >= contentBounds.height * 0.80f,
        )
    }

    private fun assertNoVisiblePageScrollInsidePager() {
        val pagerNode = composeRule.onNodeWithTag("dock_horizontal_pager").fetchSemanticsNode()
        val pagerBounds = pagerNode.boundsInRoot
        val unexpected = composeRule.onAllNodes(hasScrollAction())
            .fetchSemanticsNodes()
            .filter { node ->
                val bounds = node.boundsInRoot
                bounds.left < pagerBounds.right &&
                    bounds.right > pagerBounds.left &&
                    bounds.top < pagerBounds.bottom &&
                    bounds.bottom > pagerBounds.top
            }
            .filter { node -> node.id != pagerNode.id }
        val details = unexpected.map { node ->
            "id=${node.id} bounds=${node.boundsInRoot} config=${node.config}"
        }
        assertTrue("heatmap page should not expose a visible page-local scroll container: $details", unexpected.isEmpty())
    }

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
