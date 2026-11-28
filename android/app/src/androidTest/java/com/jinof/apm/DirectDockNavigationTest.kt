package com.jinof.apm

import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectDockNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun nonAdjacentTapSlidesTheActualEndpointsAcrossOneViewport() {
        val entered = mutableSetOf<DockDestination>()
        rule.setContent {
            AppDockPager(DockDestination.ALBUM, {}, {}) { page, _ ->
                entered += page
                Text(page.label)
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithTag("dock_settings").performClick()
            rule.mainClock.advanceTimeByFrame()
            rule.mainClock.advanceTimeBy(100)
            val width = rule.onNodeWithTag("dock_horizontal_pager").fetchSemanticsNode().boundsInRoot.width
            val origin = rule.onNodeWithTag("dock_page_swipe_album").fetchSemanticsNode().boundsInRoot
            val target = rule.onNodeWithTag("dock_page_swipe_settings").fetchSemanticsNode().boundsInRoot
            assertTrue(origin.width in 1f..(width - 1f) && target.width in 1f..(width - 1f))
            assertEquals(origin.right, target.left, 1f)
            assertEquals(width, origin.width + target.width, 1f)
            assertEquals(setOf(DockDestination.ALBUM, DockDestination.SETTINGS), entered)
        } finally {
            rule.mainClock.autoAdvance = true
        }
    }

    @Test fun distantTapsOnlyMountTheirEndpointsAndRestoreNaturalOrderAndPageState() {
        val entered = mutableSetOf<DockDestination>()
        rule.setContent {
            var settled by remember { mutableStateOf(DockDestination.ALBUM) }
            AppDockPager(settled, {}, { settled = it }) { page, _ ->
                // Record the actual body invocation, including prefetch that never becomes visible.
                entered += page
                var count by rememberSaveable { mutableIntStateOf(0) }
                Button(onClick = { count++ }, modifier = Modifier.testTag("counter_${page.name}")) { Text("$count") }
            }
        }
        rule.onNodeWithTag("counter_ALBUM").performClick()
        var source = DockDestination.ALBUM
        for (target in listOf(DockDestination.AGENT, DockDestination.ALBUM, DockDestination.SETTINGS,
                DockDestination.HEATMAP, DockDestination.SETTINGS, DockDestination.ALBUM)) {
            rule.runOnIdle { entered.clear() }
            rule.onNodeWithTag("dock_${target.name.lowercase()}").performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("dock_${target.name.lowercase()}").assertIsSelected()
            rule.runOnIdle {
                assertEquals("Unexpected page mounted for $source → $target",
                    setOf(target), entered - source)
            }
            source = target
        }
        rule.onNodeWithTag("counter_ALBUM").assertTextEquals("1")
        rule.onNodeWithTag("dock_horizontal_pager").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("dock_heatmap").assertIsSelected()
    }
}
