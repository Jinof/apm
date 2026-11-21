package com.jinof.apm

import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal data class PhotoThumbnailKey(val photoId: String, val uri: String, val size: Int)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val thumbnailDispatcher = Dispatchers.IO.limitedParallelism(4)

internal class PhotoThumbnailLoader(
    private val decode: (PhotoThumbnailKey, CancellationSignal) -> Bitmap,
) {
    private val cacheBytes = minOf(16L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8).toInt()
    private val cache = object : LruCache<PhotoThumbnailKey, Bitmap>(cacheBytes) {
        override fun sizeOf(key: PhotoThumbnailKey, value: Bitmap) = value.allocationByteCount
    }

    fun cached(key: PhotoThumbnailKey): Bitmap? = cache.get(key)

    suspend fun load(key: PhotoThumbnailKey): Bitmap? {
        val bitmap = withContext(thumbnailDispatcher) {
            cached(key) ?: suspendCancellableCoroutine<Bitmap?> { request ->
                val signal = CancellationSignal()
                request.invokeOnCancellation { signal.cancel() }
                if (request.isActive) {
                    val result = try {
                        decode(key, signal).also { if (request.isActive) it.prepareToDraw() }
                    } catch (_: Exception) {
                        // A provider may lose access to a photo while its tile is on screen.
                        null
                    }
                    request.resume(result)
                }
            }
        }
        if (bitmap != null) cache.put(key, bitmap)
        return bitmap
    }
}

@Composable
internal fun rememberPhotoThumbnailLoader(): PhotoThumbnailLoader {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        PhotoThumbnailLoader { key, signal ->
            resolver.loadThumbnail(Uri.parse(key.uri), Size(key.size, key.size), signal)
        }
    }
}

@Composable
internal fun PhotoThumbnailLoader.rememberBitmap(key: PhotoThumbnailKey, canLoad: () -> Boolean): Bitmap? {
    var bitmap by remember(this, key) { mutableStateOf(cached(key)) }
    if (bitmap == null && canLoad()) {
        LaunchedEffect(this, key) { bitmap = load(key) }
    }
    return bitmap
}
