package com.jinof.apm

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AlbumScrollTest {
    @get:Rule val rule = createComposeRule()

    private val latest = LocalDate.of(2026, 11, 20)
    private val grid = LazyGridState()
    private lateinit var scope: CoroutineScope
    private val red = solidBitmap(Color.Red)
    private val wall get() = rule.onNodeWithTag("photo_wall_gesture_surface")
    private val floating get() = rule.onNodeWithTag("album_floating_date")

    @Test fun countAndDateScrollAwayAndTheFirstPartlyVisibleRowOwnsTheFloatingDate() {
        val photos = List(240) { photo(it, dated = it < 180) }
        showAlbum({ photos })
        val count = rule.onNodeWithTag("album_total").assertTextEquals("240 张照片").fetchSemanticsNode().boundsInRoot
        val date = rule.onNodeWithTag("album_latest_date").assertTextEquals(latest.toString()).fetchSemanticsNode().boundsInRoot
        val first = tile("p0").fetchSemanticsNode().boundsInRoot
        assertEquals(wall.fetchSemanticsNode().boundsInRoot.center.x, count.center.x, 1f)
        assertTrue(count.bottom <= date.top && date.bottom <= first.top)
        floating.assertDoesNotExist()

        rule.runOnIdle { scope.launch { grid.scrollToItem(0, 12) } }
        rule.onNodeWithTag("album_latest_date").assertIsDisplayed()
        floating.assertDoesNotExist()

        // Grid item zero is the header. Leave half of the row starting with photo 70 visible.
        rule.runOnIdle { scope.launch { grid.scrollToItem(71, (first.height / 2).toInt()) } }
        floating.assertTextEquals(latest.minusDays(70).toString())
        tile("p70").assertIsDisplayed()
        assertTrue(tile("p70").fetchSemanticsNode().boundsInRoot.height < first.height)
        wall.performScrollToIndex(76)
        floating.assertTextEquals(latest.minusDays(75).toString())
        wall.performScrollToIndex(217)
        floating.assertTextEquals("拍摄时间未记录")
        wall.performScrollToIndex(0)
        floating.assertDoesNotExist()
        rule.onNodeWithTag("album_total").assertIsDisplayed()
        rule.onNodeWithTag("album_latest_date").assertTextEquals(latest.toString())
    }

    @Test fun emptyUndatedAndFilteredAlbumsShowTheRightCountAndDate() {
        val photos = mutableStateOf(emptyList<GalleryPhotoCard>())
        val range = mutableStateOf<PhotoHeatmapSelection?>(null)
        showAlbum({ photos.value }, range = { range.value })
        rule.onNodeWithTag("album_total").assertTextEquals("0 张照片")
        rule.onNodeWithTag("album_latest_date").assertDoesNotExist()
        floating.assertDoesNotExist()
        rule.runOnIdle { photos.value = listOf(photo(0, dated = false)) }
        rule.onNodeWithTag("album_latest_date").assertTextEquals("拍摄时间未记录")

        val selected = latest.minusDays(8)
        rule.runOnIdle {
            photos.value = List(36) { photo(it) }
            range.value = PhotoHeatmapSelection(PhotoHeatmapGranularity.DAY, selected, selected, 1)
        }
        rule.onNodeWithTag("album_total").assertTextEquals("36 张照片")
        rule.onNodeWithTag("album_latest_date").assertTextEquals(selected.toString())
        tile("p8").assertIsDisplayed()
        floating.assertDoesNotExist()
    }

    @Test fun aLongScrollLaysOutFramesAndOnlyLoadsTheSettledViewport() {
        val reads = ConcurrentLinkedQueue<String>()
        val photos = List(600) { photo(it) }
        showAlbum({ photos }, loader = PhotoThumbnailLoader { key, _ -> reads.add(key.photoId); red })
        rule.waitUntil(5_000) { centerColor("p0") == Color.Red }
        val rowHeight = tile("p0").fetchSemanticsNode().boundsInRoot.height
        val next = CompletableDeferred<Unit>()
        val settle = CompletableDeferred<Unit>()
        rule.runOnIdle {
            scope.launch {
                grid.scroll {
                    scrollBy(rowHeight * 20)
                    next.await()
                    scrollBy(rowHeight * 20)
                    settle.await()
                }
            }
        }
        try {
            val passing = fullyVisiblePhoto()
            val passingIndex = passing.removePrefix("p").toInt()
            assertTrue(passingIndex >= 75)
            val before = tile(passing).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(before.width, before.height, 1f)
            assertFalse(reads.contains(passing))
            assertTrue(centerColor(passing) != Color.Red)

            rule.runOnIdle { next.complete(Unit) }
            rule.waitUntil(5_000) { grid.firstVisibleItemIndex >= passingIndex + 75 }
            val destination = fullyVisiblePhoto()
            val frame = tile(destination).fetchSemanticsNode().boundsInRoot
            assertFalse(reads.contains(destination))
            assertTrue(centerColor(destination) != Color.Red)
            rule.runOnIdle { settle.complete(Unit) }
            rule.waitUntil(5_000) { centerColor(destination) == Color.Red }
            assertEquals(frame, tile(destination).fetchSemanticsNode().boundsInRoot)
            assertFalse("A skipped row must never start reading", reads.contains(passing))
            wall.performScrollToIndex(0)
            rule.waitUntil(5_000) { centerColor("p0") == Color.Red }
            assertEquals(1, reads.count { it == "p0" })
        } finally {
            rule.runOnUiThread {
                next.complete(Unit)
                settle.complete(Unit)
            }
        }
    }

    @Test fun aCancelledProviderCannotFillAReboundTileOrItsCache() {
        val old = photo(0)
        val replacement = old.copy(photoId = "replacement")
        val photos = mutableStateOf(listOf(old))
        val redStarted = CountDownLatch(1)
        val redCancelled = CountDownLatch(1)
        val releaseRed = CountDownLatch(1)
        val redFinished = CountDownLatch(1)
        val keys = ConcurrentLinkedQueue<PhotoThumbnailKey>()
        val blue = solidBitmap(Color.Blue)
        val loader = PhotoThumbnailLoader { key, signal ->
            if (key.photoId == old.photoId) {
                keys.add(key)
                signal.setOnCancelListener { redCancelled.countDown() }
                redStarted.countDown()
                releaseRed.await(10, TimeUnit.SECONDS)
                redFinished.countDown()
                red
            } else blue
        }
        try {
            showAlbum({ photos.value }, loader = loader)
            assertTrue(redStarted.await(5, TimeUnit.SECONDS))
            rule.runOnIdle { photos.value = listOf(replacement) }
            rule.waitUntil(5_000) { centerColor(replacement.photoId) == Color.Blue }
            assertTrue(redCancelled.await(2, TimeUnit.SECONDS))
            releaseRed.countDown()
            assertTrue(redFinished.await(2, TimeUnit.SECONDS))
            rule.waitForIdle()
            assertNull(loader.cached(keys.single()))
            assertEquals(Color.Blue, centerColor(replacement.photoId))
        } finally {
            releaseRed.countDown()
        }
    }

    private fun showAlbum(
        photos: () -> List<GalleryPhotoCard>,
        range: () -> PhotoHeatmapSelection? = { null },
        loader: PhotoThumbnailLoader = PhotoThumbnailLoader { _, _ -> red },
    ) {
        rule.setContent {
            scope = rememberCoroutineScope()
            MaterialTheme {
                Box(Modifier.size(width = 300.dp, height = 320.dp)) {
                    GalleryScreen(GalleryUiState(galleryPhotos = photos()), {}, {}, {}, {}, range(), {}, { _, _ -> },
                        gridState = grid, thumbnailLoader = loader)
                }
            }
        }
    }

    private fun fullyVisiblePhoto(): String = rule.runOnIdle {
        val layout = grid.layoutInfo
        layout.visibleItemsInfo.first {
            it.index > 0 && it.offset.y >= layout.viewportStartOffset && it.offset.y + it.size.height <= layout.viewportEndOffset
        }.key as String
    }

    private fun photo(index: Int, dated: Boolean = true): GalleryPhotoCard {
        val capture = if (dated) latest.minusDays(index.toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() else null
        return GalleryPhotoCard("p$index", "content://apm.test/$index", "Photo $index", capture, null, null, null, null)
    }

    private fun tile(id: String) = rule.onNodeWithTag("photo_wall_thumbnail_$id")

    private fun centerColor(id: String): Color {
        val pixels = tile(id).captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    private fun solidBitmap(color: Color) = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
        eraseColor(color.toArgb())
    }
}
