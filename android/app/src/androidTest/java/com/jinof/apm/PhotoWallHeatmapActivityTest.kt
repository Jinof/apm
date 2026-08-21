package com.jinof.apm

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
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
            composeRule.onNodeWithTag("startup_story_continue").performClick()
            waitForDisplayedTag("dock_page_swipe_album")
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
            composeRule.onNodeWithTag("startup_story_continue").performClick()
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
            composeRule.onNodeWithTag("startup_story_continue").performClick()
            composeRule.onNodeWithTag("dock_album").assertIsSelected()

            composeRule.onNodeWithTag("dock_page_swipe_album").performTouchInput { swipeLeft() }
            waitForSettledPage("dock_page_swipe_heatmap")
            composeRule.onNodeWithTag("dock_heatmap").assertIsSelected()
            composeRule.onNodeWithTag("heatmap_back").assertDoesNotExist()
            assertOnlyMainActivityResumed()

            composeRule.onNodeWithTag("dock_page_swipe_heatmap").performTouchInput { swipeLeft() }
            waitForSettledPage("dock_page_swipe_people")
            composeRule.onNodeWithTag("dock_identity").assertIsSelected()
            composeRule.onNodeWithTag("identity_back").assertDoesNotExist()
            assertOnlyMainActivityResumed()

            composeRule.onNodeWithTag("dock_page_swipe_people").performTouchInput { swipeLeft() }
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
            waitForSettledPage("dock_page_swipe_people")
            composeRule.onNodeWithTag("dock_identity").assertIsSelected()

            composeRule.onNodeWithTag("dock_page_swipe_people").performTouchInput { swipeRight() }
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
            composeRule.onNodeWithTag("startup_story_continue").performClick()
            composeRule.onNodeWithTag("dock_settings").performClick()
            waitForSettledPage("dock_page_swipe_settings")
            composeRule.onNodeWithTag("model_input").performTextReplacement("pager-draft-model")

            composeRule.onNodeWithTag("dock_page_swipe_settings").performTouchInput { swipeRight() }
            waitForSettledPage("dock_page_swipe_agent")
            composeRule.onNodeWithTag("dock_page_swipe_agent").performTouchInput { swipeLeft() }
            waitForSettledPage("dock_page_swipe_settings")

            composeRule.onNodeWithTag("model_input").assertTextContains("pager-draft-model")
            composeRule.onNodeWithTag("dock_settings").assertIsSelected()
            assertOnlyMainActivityResumed()
        } finally {
            scenario?.close()
        }
    }

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
            composeRule.onNodeWithTag("startup_story_dialog").assertExists()
            composeRule.onNodeWithTag("startup_story_continue").performClick()
            composeRule.onNodeWithTag("startup_story_dialog").assertDoesNotExist()
            composeRule.onNodeWithTag("photo_wall_heatmap").assertIsNotDisplayed()
            composeRule.onNodeWithTag("bottom_dock").assertExists()
            composeRule.onNodeWithTag("bottom_dock")
                .assertContentDescriptionEquals("固定底部 Dock：相册、热力图、人、Agent、设置；页面跟随左右滑动并平滑切换")
            composeRule.onNodeWithTag("dock_album")
                .assertContentDescriptionEquals("相册首页")
            composeRule.onNodeWithTag("dock_heatmap").assertExists()
            composeRule.onNodeWithTag("dock_settings").assertExists()
            composeRule.onNodeWithTag("dock_identity").assertExists()
            composeRule.onNodeWithTag("dock_agent").assertExists()
            composeRule.onNodeWithTag("open_identity").assertDoesNotExist()
            composeRule.onNodeWithTag("open_agent").assertDoesNotExist()
            composeRule.onNodeWithTag("open_settings").assertDoesNotExist()
            composeRule.onNodeWithTag("open_heatmap_page").assertDoesNotExist()
            assertDockOrder("dock_album", "dock_heatmap", "dock_identity", "dock_agent", "dock_settings")
            composeRule.onNodeWithTag("photo_wall_gesture_edge")
                .assertContentDescriptionEquals("从左边缘向右滑动打开年度照片热力图")
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
            composeRule.onNodeWithTag("heatmap_next_year").performClick()
            composeRule.onNodeWithTag("heatmap_year_label").assertTextEquals("2040年")

            composeRule.onNodeWithTag("heatmap_day_2040-01-01").assertExists()
            composeRule.onNodeWithTag("heatmap_day_2040-12-31").assertExists()
            composeRule.onNodeWithTag("heatmap_day_quarters").assertExists()
            assertQuarterMonthMatrix("day")
            assertInHeatmapViewport("heatmap_day_2040-01-01")
            assertInHeatmapViewport("heatmap_day_2040-12-31")
            assertVerticallyOrdered("heatmap_day_2040-01-01", "heatmap_day_2040-01-08")
            composeRule.onNodeWithTag("heatmap_day_2040-08-14").assertExists()
            composeRule.onNodeWithTag("heatmap_day_2040-08-14")
                .assertContentDescriptionEquals("2040年8月14日，4张照片，热度4级")
            composeRule.onNodeWithTag("heatmap_day_2040-08-15")
                .assertContentDescriptionEquals("2040年8月15日，2张照片，热度2级")
                .performClick()
            composeRule.onNodeWithTag("heatmap_day_2040-08-15")
                .assertContentDescriptionEquals("2040年8月15日，2张照片，热度2级，已选择")
            captureScreenshot("photo-wall-github-annual-day-quarter-month.png")
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
            composeRule.onNodeWithTag("heatmap_week_2040-08-12").assertExists()
            composeRule.onNodeWithTag("heatmap_week_2040-08-19").assertExists()
            composeRule.onNodeWithTag("heatmap_week_2040-01-01").assertExists()
            composeRule.onNodeWithTag("heatmap_week_2040-12-30").assertExists()
            composeRule.onNodeWithTag("heatmap_week_quarters").assertExists()
            assertQuarterMonthMatrix("week")
            assertInHeatmapViewport("heatmap_week_2040-01-01")
            assertInHeatmapViewport("heatmap_week_2040-12-30")
            assertInHeatmapViewport("heatmap_week_2040-08-12")
            assertInHeatmapViewport("heatmap_week_2040-08-19")
            assertVerticallyOrdered("heatmap_week_2040-08-12", "heatmap_week_2040-08-19")
            composeRule.onNodeWithTag("heatmap_week_2040-08-12")
                .assertContentDescriptionEquals("2040年8月12日–18日，7张照片，热度4级")
                .performClick()
            captureScreenshot("photo-wall-github-annual-week-quarter-month.png")
            displayedNodeWithTag("selected_photo_range_summary")
                .assertTextEquals("已选 2040年8月12日–18日 · 7 张照片")
            composeRule.onNodeWithTag("heatmap_granularity_month").assertExists()
            composeRule.onNodeWithTag("heatmap_granularity_month")
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            composeRule.onNodeWithTag("heatmap_month_01").assertExists()
            composeRule.onNodeWithTag("heatmap_month_12").assertExists()
            composeRule.onNodeWithTag("heatmap_month_quarters").assertExists()
            assertQuarterMonthMatrix("month")
            assertInHeatmapViewport("heatmap_month_01")
            assertInHeatmapViewport("heatmap_month_12")
            composeRule.onNodeWithTag("heatmap_month_08").assertExists()
            composeRule.onNodeWithTag("heatmap_month_08")
                .assertContentDescriptionEquals("2040年8月，7张照片，热度4级")
                .performClick()
            captureScreenshot("photo-wall-github-annual-month-quarter-month.png")
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

    private fun assertQuarterMonthMatrix(mode: String) {
        val quarterBounds = (1..4).map { quarter ->
            composeRule.onNodeWithTag("heatmap_${mode}_quarter_$quarter")
                .fetchSemanticsNode().boundsInRoot
        }
        assertTrue(
            "$mode quarters must run top-to-bottom: $quarterBounds",
            quarterBounds.zipWithNext().all { (upper, lower) -> upper.top < lower.top },
        )
        (1..4).forEach { quarter ->
            val monthStart = (quarter - 1) * 3 + 1
            val months = (monthStart..monthStart + 2).map { month ->
                composeRule.onNodeWithTag(
                    "heatmap_${mode}_month_panel_${month.toString().padStart(2, '0')}",
                ).fetchSemanticsNode().boundsInRoot
            }
            assertTrue(
                "$mode quarter $quarter months must share one horizontal row: $months",
                months.all { bounds -> abs(bounds.top - months.first().top) <= 1f },
            )
            assertTrue(
                "$mode quarter $quarter months must run left-to-right: $months",
                months.zipWithNext().all { (left, right) -> left.left < right.left },
            )
        }
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
        val quarterBounds = composeRule.onNodeWithTag("heatmap_quarters_vertical")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("borderless heatmap should use the content width", heatmapBounds.width >= contentBounds.width * 0.98f)
        assertTrue("quarter grid should use the full heatmap width", quarterBounds.width >= contentBounds.width * 0.98f)
        assertTrue(
            "quarter grid should consume most available heatmap height",
            quarterBounds.height >= contentBounds.height * 0.80f,
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
