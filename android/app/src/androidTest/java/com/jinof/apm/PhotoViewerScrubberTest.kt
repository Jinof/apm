package com.jinof.apm

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PhotoViewerScrubberTest {
    @get:Rule val rule = createComposeRule()
    private val viewport get() = rule.onNodeWithTag("viewport")
    private val control get() = rule.onNodeWithTag("photo_viewer_scrubber")

    @Test fun liveDragPreservesGrabOffsetAndSnapsToTheNearestPhoto() {
        var selected by mutableIntStateOf(2)
        rule.setContent {
            PhotoViewerScrubber(selected, 9, { selected = it }, Modifier.testTag("viewport")) {}
        }
        assertThumbAt(0.25f)
        val view = viewport.fetchSemanticsNode().boundsInRoot
        val grabX = view.width * 0.9f + with(rule.density) { 12.dp.toPx() }
        fun fingerAt(fraction: Float) = Offset(grabX, view.height * (0.10f + 0.70f * fraction) + 5f)

        viewport.performTouchInput { down(fingerAt(0.25f)) }
        viewport.performTouchInput { moveTo(fingerAt(0.57f)) }
        rule.runOnIdle { assertEquals(5, selected) }
        assertThumbAt(0.57f)
        viewport.performTouchInput { up() }
        assertThumbAt(5f / 8)

        viewport.performTouchInput { swipe(fingerAt(5f / 8), fingerAt(1.10f)) }
        rule.runOnIdle { assertEquals(8, selected) }
        assertThumbAt(1f)
        viewport.performTouchInput { swipe(fingerAt(1f), fingerAt(-0.08f)) }
        rule.runOnIdle { assertEquals(0, selected) }
        assertThumbAt(0f)
        viewport.performTouchInput { swipe(fingerAt(0.5f), fingerAt(1f)) }
        rule.runOnIdle { assertEquals(0, selected) }
    }

    @Test fun emptySingleAndAccessibleProgressUseThePhysicalRightSideInALandscapeViewport() {
        var selected by mutableIntStateOf(-1)
        var count by mutableIntStateOf(0)
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                PhotoViewerScrubber(selected, count, { selected = it }, Modifier.size(300.dp, 160.dp).testTag("viewport")) {}
            }
        }
        control.assertDoesNotExist()
        rule.runOnIdle { count = 1; selected = 0 }
        control.assertIsNotEnabled()
        assertThumbAt(0f)
        rule.runOnIdle { count = 5 }
        control.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(3f)) }
        rule.runOnIdle { assertEquals(2, selected) }
        assertThumbAt(0.5f)
        rule.runOnIdle { selected = 4 }
        assertThumbAt(1f)
    }

    private fun assertThumbAt(fraction: Float) {
        val view = viewport.fetchSemanticsNode().boundsInRoot
        val thumb = rule.onNodeWithTag("photo_viewer_scrubber_thumb", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertEquals(view.width * 0.01f, thumb.width, 1f)
        assertEquals(view.height * 0.02f, thumb.height, 1f)
        assertEquals(view.left + view.width * 0.90f, thumb.center.x, 1.5f)
        assertEquals(view.top + view.height * (0.10f + 0.70f * fraction), thumb.center.y, 1.5f)
        val target = control.fetchSemanticsNode().size
        val minimumTouch = with(rule.density) { 48.dp.roundToPx() }
        assertTrue(target.width >= minimumTouch && target.height >= minimumTouch)
    }
}
