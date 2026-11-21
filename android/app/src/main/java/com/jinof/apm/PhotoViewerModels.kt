package com.jinof.apm

import kotlin.math.max
import kotlin.math.min

data class PhotoViewerItem(
    val photoId: String,
    val uri: String,
    val displayName: String,
    val dateTakenMillis: Long?,
)

data class PhotoViewerTransform(
    val scale: Float = PhotoViewerTransformPolicy.MIN_SCALE,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

object PhotoViewerNavigator {
    fun items(photos: List<GalleryPhotoCard>): List<PhotoViewerItem> = photos
        .distinctBy(GalleryPhotoCard::photoId)
        .map { photo ->
            PhotoViewerItem(
                photoId = photo.photoId,
                uri = photo.uri,
                displayName = photo.displayName,
                dateTakenMillis = photo.dateTakenMillis,
            )
        }

    fun selectedIndex(
        photos: List<PhotoViewerItem>,
        requestedPhotoId: String,
        requestedUri: String,
    ): Int = photos.indexOfFirst { photo ->
        requestedPhotoId.isNotBlank() && photo.photoId == requestedPhotoId
    }.takeIf { it >= 0 } ?: photos.indexOfFirst { photo ->
        requestedUri.isNotBlank() && photo.uri == requestedUri
    }

    fun previous(index: Int, size: Int): Int = (index - 1).coerceInValidRange(size)

    fun next(index: Int, size: Int): Int = (index + 1).coerceInValidRange(size)

    private fun Int.coerceInValidRange(size: Int): Int = when {
        size <= 0 -> -1
        else -> coerceIn(0, size - 1)
    }
}

object PhotoViewerTransformPolicy {
    const val MIN_SCALE = 1f
    const val MIN_MAX_SCALE = 5f

    fun maximumScale(
        viewportWidth: Float,
        viewportHeight: Float,
        imageWidth: Float,
        imageHeight: Float,
    ): Float {
        if (
            viewportWidth <= 0f ||
            viewportHeight <= 0f ||
            imageWidth <= 0f ||
            imageHeight <= 0f
        ) {
            return MIN_MAX_SCALE
        }
        val fitScale = min(viewportWidth / imageWidth, viewportHeight / imageHeight)
        return max(MIN_MAX_SCALE, 1f / fitScale)
    }

    fun update(
        current: PhotoViewerTransform,
        zoomChange: Float,
        panX: Float,
        panY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        imageWidth: Float,
        imageHeight: Float,
    ): PhotoViewerTransform {
        val maximumScale = maximumScale(
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        )
        val scale = (current.scale * zoomChange).coerceIn(MIN_SCALE, maximumScale)
        if (
            viewportWidth <= 0f ||
            viewportHeight <= 0f ||
            imageWidth <= 0f ||
            imageHeight <= 0f
        ) {
            return PhotoViewerTransform(scale = scale)
        }

        val fitScale = min(viewportWidth / imageWidth, viewportHeight / imageHeight)
        val scaledWidth = imageWidth * fitScale * scale
        val scaledHeight = imageHeight * fitScale * scale
        val maxOffsetX = max(0f, (scaledWidth - viewportWidth) / 2f)
        val maxOffsetY = max(0f, (scaledHeight - viewportHeight) / 2f)
        return PhotoViewerTransform(
            scale = scale,
            offsetX = if (maxOffsetX == 0f) {
                0f
            } else {
                (current.offsetX + panX).coerceIn(-maxOffsetX, maxOffsetX)
            },
            offsetY = if (maxOffsetY == 0f) {
                0f
            } else {
                (current.offsetY + panY).coerceIn(-maxOffsetY, maxOffsetY)
            },
        )
    }
}
