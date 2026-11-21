package com.jinof.apm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoViewerModelsTest {
    @Test
    fun viewerItemsPreserveWallOrderAndSelectExactTappedPhoto() {
        val photos = listOf(card("new"), card("middle"), card("old"), card("middle", "duplicate"))

        val items = PhotoViewerNavigator.items(photos)

        assertEquals(listOf("new", "middle", "old"), items.map(PhotoViewerItem::photoId))
        assertEquals(1, PhotoViewerNavigator.selectedIndex(items, "middle", ""))
        assertEquals(2, PhotoViewerNavigator.selectedIndex(items, "", "content://apm.test/old"))
        assertEquals(-1, PhotoViewerNavigator.selectedIndex(items, "missing", "content://missing"))
    }

    @Test
    fun adjacentNavigationStopsAtSequenceBoundaries() {
        assertEquals(0, PhotoViewerNavigator.previous(0, 3))
        assertEquals(0, PhotoViewerNavigator.previous(1, 3))
        assertEquals(1, PhotoViewerNavigator.next(0, 3))
        assertEquals(2, PhotoViewerNavigator.next(2, 3))
        assertEquals(-1, PhotoViewerNavigator.previous(0, 0))
        assertEquals(-1, PhotoViewerNavigator.next(0, 0))
    }

    @Test
    fun zoomAndPanClampToFitImageAndViewportBounds() {
        val transformed = PhotoViewerTransformPolicy.update(
            current = PhotoViewerTransform(),
            zoomChange = 10f,
            panX = 10_000f,
            panY = -10_000f,
            viewportWidth = 1_000f,
            viewportHeight = 1_000f,
            imageWidth = 1_000f,
            imageHeight = 2_000f,
        )

        assertEquals(PhotoViewerTransformPolicy.MIN_MAX_SCALE, transformed.scale, 0f)
        assertEquals(750f, transformed.offsetX, 0f)
        assertEquals(-2_000f, transformed.offsetY, 0f)

        val fitted = PhotoViewerTransformPolicy.update(
            current = transformed,
            zoomChange = 0.01f,
            panX = 100f,
            panY = 100f,
            viewportWidth = 1_000f,
            viewportHeight = 1_000f,
            imageWidth = 1_000f,
            imageHeight = 2_000f,
        )
        assertEquals(PhotoViewerTransform(), fitted)
    }

    @Test
    fun missingViewportCannotCreateUnboundedPan() {
        val transformed = PhotoViewerTransformPolicy.update(
            current = PhotoViewerTransform(offsetX = 50f, offsetY = 50f),
            zoomChange = 2f,
            panX = 500f,
            panY = 500f,
            viewportWidth = 0f,
            viewportHeight = 0f,
            imageWidth = 1_000f,
            imageHeight = 500f,
        )

        assertEquals(2f, transformed.scale, 0f)
        assertTrue(transformed.offsetX == 0f && transformed.offsetY == 0f)
    }

    @Test
    fun largeOriginalCanZoomToOneSourcePixelPerDisplayPixel() {
        val maximumScale = PhotoViewerTransformPolicy.maximumScale(
            viewportWidth = 1_000f,
            viewportHeight = 1_000f,
            imageWidth = 12_000f,
            imageHeight = 3_000f,
        )

        assertEquals(12f, maximumScale, 0f)
        val transformed = PhotoViewerTransformPolicy.update(
            current = PhotoViewerTransform(),
            zoomChange = 100f,
            panX = 0f,
            panY = 0f,
            viewportWidth = 1_000f,
            viewportHeight = 1_000f,
            imageWidth = 12_000f,
            imageHeight = 3_000f,
        )
        assertEquals(12f, transformed.scale, 0f)
    }

    private fun card(id: String, uriSuffix: String = id) = GalleryPhotoCard(
        photoId = id,
        uri = "content://apm.test/$uriSuffix",
        displayName = "$id.jpg",
        dateTakenMillis = null,
        annotation = null,
        modelName = null,
        promptVersion = null,
        annotatedAt = null,
    )
}
