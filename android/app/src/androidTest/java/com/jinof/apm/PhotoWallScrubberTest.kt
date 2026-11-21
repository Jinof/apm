package com.jinof.apm

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PhotoWallScrubberTest {
    @get:Rule val rule = createComposeRule()
    private val grid = LazyGridState()
    private val window get() = rule.onNodeWithTag("album_window")
    private val wall get() = rule.onNodeWithTag("photo_wall_gesture_surface")
    private val control get() = rule.onNodeWithTag("photo_wall_scrubber")
    private val thumb get() = rule.onNodeWithTag("photo_wall_scrubber_thumb", useUnmergedTree = true)
    private val latest = LocalDate.of(2026, 11, 20)
    private val reads = ConcurrentLinkedQueue<String>()
    private var opened: String? = null

    @Test fun ordinaryScrollingAndOffCenterDragsTrackTheGridAndReachThePartialLastRow() {
        val photos = List(243, ::photo)
        showAlbum({ photos })
        assertThumbAt(0f)
        wall.performScrollToIndex(81)
        val bounds = window.fetchSemanticsNode().boundsInRoot
        val afterScroll = thumb.fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
        assertTrue(afterScroll.y > bounds.height * 0.20f)
        val grab = Offset(afterScroll.x - 6f, afterScroll.y + 10f)
        fun endAt(fraction: Float) = Offset(grab.x, bounds.height * (0.10f + 0.70f * fraction) + 10f)
        window.performTouchInput { down(grab); moveTo(endAt(0.67f)) }
        assertThumbAt(0.67f)
        val passing = rule.runOnIdle { grid.layoutInfo.visibleItemsInfo.first { it.index > 0 }.key as String }
        assertFalse("Intermediate rows should only show frames while dragging", reads.contains(passing))
        window.performTouchInput { moveTo(endAt(1.07f)); up() }
        assertThumbAt(1f)
        rule.onNodeWithTag("photo_wall_thumbnail_p242").assertIsDisplayed()
        rule.waitUntil(5_000) { "p242" in reads }
        window.performTouchInput { swipe(endAt(1f), endAt(-0.06f), 140) }
        assertThumbAt(0f)
        rule.onNodeWithTag("album_total").assertIsDisplayed()
        assertEquals(null, opened)
        rule.onNodeWithContentDescription("Photo 0").performClick()
        assertEquals("p0", opened)
    }

    @Test fun searchCanBeTappedAndTheThumbCanBeDraggedWhereTheirTargetsCross() {
        val photos = List(181, ::photo)
        showAlbum({ photos })
        val bounds = window.fetchSemanticsNode().boundsInRoot
        val search = rule.onNodeWithTag("open_album_search")
        val searchY = search.fetchSemanticsNode().boundsInRoot.center.y - bounds.top
        val crossing = (searchY / bounds.height - 0.10f) / 0.70f
        control.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(crossing)) }
        assertThumbAt(crossing)
        search.performTouchInput { click(center) }
        rule.onNodeWithTag("search_input").assertIsDisplayed()
        rule.onNodeWithTag("close_album_search").performClick()
        assertThumbAt(crossing)
        val grab = thumb.fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
        window.performTouchInput { swipe(grab, Offset(grab.x, bounds.height * 0.84f), 180) }
        rule.onNodeWithTag("search_input").assertDoesNotExist()
        rule.onNodeWithTag("photo_wall_thumbnail_p180").assertIsDisplayed()
        assertThumbAt(1f)
    }

    @Test fun emptyShortAndFilteredWallsUseOnlyTheDisplayedPhotos() {
        var photos by mutableStateOf(emptyList<GalleryPhotoCard>())
        var range by mutableStateOf<PhotoHeatmapSelection?>(null)
        showAlbum({ photos }, range = { range }, direction = LayoutDirection.Rtl)
        control.assertDoesNotExist()
        rule.runOnIdle { photos = List(3, ::photo) }
        control.assertIsNotEnabled()
        assertThumbAt(0f)
        rule.runOnIdle {
            photos = List(200, ::photo)
            range = PhotoHeatmapSelection(PhotoHeatmapGranularity.MONTH, latest.minusDays(124), latest.minusDays(35), 90)
        }
        control.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(1f)) }
        rule.onNodeWithTag("photo_wall_thumbnail_p124").assertIsDisplayed()
        rule.onNodeWithTag("photo_wall_thumbnail_p125").assertDoesNotExist()
        assertThumbAt(1f)
    }

    private fun assertThumbAt(fraction: Float) {
        val view = window.fetchSemanticsNode().boundsInRoot
        val handle = thumb.fetchSemanticsNode().boundsInRoot
        assertEquals(view.width * 0.04f, handle.width, 1f)
        assertEquals(view.height * 0.08f, handle.height, 1f)
        assertEquals(view.left + view.width * 0.90f, handle.center.x, 1.5f)
        assertEquals(view.top + view.height * (0.10f + fraction * 0.70f), handle.center.y, 2f)
    }

    private fun showAlbum(photos: () -> List<GalleryPhotoCard>, range: () -> PhotoHeatmapSelection? = { null },
                          direction: LayoutDirection = LayoutDirection.Ltr) {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val thumbnails = PhotoThumbnailLoader { key, _ -> reads.add(key.photoId); bitmap }
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().testTag("album_window").padding(bottom = 80.dp)) {
                        GalleryScreen(GalleryUiState(galleryPhotos = photos()), {}, {}, {}, {}, range(), {},
                            { id, _ -> opened = id }, gridState = grid, thumbnailLoader = thumbnails)
                    }
                }
            }
        }
    }

    private fun photo(index: Int): GalleryPhotoCard {
        val date = latest.minusDays(index.toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return GalleryPhotoCard("p$index", "content://apm.test/$index", "Photo $index", date, null, null, null, null)
    }
}
