package com.jinof.apm

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

private data class PhotoViewerUiState(
    val loading: Boolean = true,
    val photos: List<PhotoViewerItem> = emptyList(),
    val selectedIndex: Int = -1,
    val error: String? = null,
) {
    val selectedPhoto: PhotoViewerItem?
        get() = photos.getOrNull(selectedIndex)
}

private sealed interface ViewerImageLoadState {
    data object Loading : ViewerImageLoadState

    data class Loaded(val bitmap: Bitmap) : ViewerImageLoadState

    data class Failed(val message: String) : ViewerImageLoadState
}

class PhotoViewerActivity : ComponentActivity() {
    private lateinit var database: ApmDatabase
    private val executor = Executors.newSingleThreadExecutor()
    private var state by mutableStateOf(PhotoViewerUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        database = ApmDatabase(applicationContext)
        setContent {
            ApmTheme {
                PhotoViewerScreen(
                    state = state,
                    onBack = ::finish,
                    onPrevious = ::showPrevious,
                    onNext = ::showNext,
                )
            }
        }
        loadPhotos()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        database.close()
        super.onDestroy()
    }

    private fun loadPhotos() {
        val requestedPhotoId = intent.getStringExtra(EXTRA_PHOTO_ID).orEmpty()
        val requestedUri = intent.getStringExtra(EXTRA_URI).orEmpty()
        executor.execute {
            runCatching {
                val photos = PhotoViewerNavigator.items(database.galleryPhotos())
                val selectedIndex = PhotoViewerNavigator.selectedIndex(
                    photos = photos,
                    requestedPhotoId = requestedPhotoId,
                    requestedUri = requestedUri,
                )
                check(selectedIndex >= 0) { "这张照片当前不可访问，请返回照片墙重新授权。" }
                PhotoViewerUiState(
                    loading = false,
                    photos = photos,
                    selectedIndex = selectedIndex,
                )
            }.onSuccess { loaded ->
                runOnUiThread { state = loaded }
            }.onFailure { error ->
                runOnUiThread {
                    state = PhotoViewerUiState(
                        loading = false,
                        error = error.message ?: "无法读取照片。",
                    )
                }
            }
        }
    }

    private fun showPrevious() {
        val index = PhotoViewerNavigator.previous(state.selectedIndex, state.photos.size)
        if (index >= 0 && index != state.selectedIndex) state = state.copy(selectedIndex = index)
    }

    private fun showNext() {
        val index = PhotoViewerNavigator.next(state.selectedIndex, state.photos.size)
        if (index >= 0 && index != state.selectedIndex) state = state.copy(selectedIndex = index)
    }

    companion object {
        private const val EXTRA_PHOTO_ID = "photo_id"
        private const val EXTRA_URI = "uri"

        fun intent(context: Context, photoId: String, uri: String): Intent =
            Intent(context, PhotoViewerActivity::class.java)
                .putExtra(EXTRA_PHOTO_ID, photoId)
                .putExtra(EXTRA_URI, uri)
    }
}

@Composable
private fun PhotoViewerScreen(
    state: PhotoViewerUiState,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val photo = state.selectedPhoto
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("photo_viewer"),
    ) {
        when {
            state.loading -> CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).testTag("photo_viewer_loading"),
            )
            photo != null -> key(photo.photoId, photo.uri) {
                ZoomableViewerImage(photo)
            }
            else -> ViewerError(
                message = state.error ?: "没有可查看的照片。",
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.58f))
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("photo_viewer_back")) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "返回照片墙",
                    tint = Color.White,
                )
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = photo?.displayName ?: "照片查看器",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (photo != null) {
                    Text(
                        text = formatViewerCaptureDate(photo.dateTakenMillis),
                        color = Color.White.copy(alpha = 0.74f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
            }
            if (photo != null) {
                Text(
                    text = "${state.selectedIndex + 1} / ${state.photos.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp).testTag("photo_viewer_position"),
                )
            }
        }

        if (photo != null) {
            Surface(
                color = Color.Black.copy(alpha = 0.58f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    IconButton(
                        onClick = onPrevious,
                        enabled = state.selectedIndex > 0,
                        modifier = Modifier.testTag("photo_viewer_previous"),
                    ) {
                        Icon(
                            Icons.Outlined.ChevronLeft,
                            contentDescription = "上一张照片",
                            tint = if (state.selectedIndex > 0) Color.White else Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    Text(
                        text = "双指缩放与拖动",
                        color = Color.White.copy(alpha = 0.82f),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                    IconButton(
                        onClick = onNext,
                        enabled = state.selectedIndex < state.photos.lastIndex,
                        modifier = Modifier.testTag("photo_viewer_next"),
                    ) {
                        Icon(
                            Icons.Outlined.ChevronRight,
                            contentDescription = "下一张照片",
                            tint = if (state.selectedIndex < state.photos.lastIndex) {
                                Color.White
                            } else {
                                Color.White.copy(alpha = 0.3f)
                            },
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableViewerImage(photo: PhotoViewerItem) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val loadState by produceState<ViewerImageLoadState>(
        initialValue = ViewerImageLoadState.Loading,
        key1 = photo.photoId,
        key2 = photo.uri,
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ViewerImageLoadState.Loaded(
                    loadOriginalViewerBitmap(context.contentResolver, Uri.parse(photo.uri)),
                )
            }.getOrElse { error ->
                ViewerImageLoadState.Failed(error.message ?: "照片读取失败。")
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val image = loadState) {
            ViewerImageLoadState.Loading -> CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.testTag("photo_viewer_image_loading"),
            )
            is ViewerImageLoadState.Failed -> ViewerError(
                message = "无法在 APM 内读取这张照片：${image.message}",
                modifier = Modifier.testTag("photo_viewer_image_error"),
            )
            is ViewerImageLoadState.Loaded -> ZoomableBitmap(
                photo = photo,
                bitmap = image.bitmap,
            )
        }
    }
}

@Composable
private fun ZoomableBitmap(photo: PhotoViewerItem, bitmap: Bitmap) {
    var viewport by remember(photo.photoId) { mutableStateOf(IntSize.Zero) }
    var transform by remember(photo.photoId) { mutableStateOf(PhotoViewerTransform()) }
    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        transform = PhotoViewerTransformPolicy.update(
            current = transform,
            zoomChange = zoomChange,
            panX = panChange.x,
            panY = panChange.y,
            viewportWidth = viewport.width.toFloat(),
            viewportHeight = viewport.height.toFloat(),
            imageWidth = bitmap.width.toFloat(),
            imageHeight = bitmap.height.toFloat(),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = photo.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = transform.scale
                    scaleY = transform.scale
                    translationX = transform.offsetX
                    translationY = transform.offsetY
                }
                .transformable(transformableState)
                .testTag("photo_viewer_image"),
        )
        if (transform.scale > PhotoViewerTransformPolicy.MIN_SCALE) {
            Surface(
                color = Color.Black.copy(alpha = 0.62f),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
            ) {
                IconButton(
                    onClick = { transform = PhotoViewerTransform() },
                    modifier = Modifier.testTag("photo_viewer_reset_zoom"),
                ) {
                    Icon(
                        Icons.Outlined.CenterFocusStrong,
                        contentDescription = "适合屏幕",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerError(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Outlined.PhotoLibrary,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = message,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

internal fun loadOriginalViewerBitmap(
    resolver: ContentResolver,
    uri: Uri,
): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, _, _ ->
    decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
    decoder.setOnPartialImageListener {
        false
    }
}

private fun formatViewerCaptureDate(value: Long?): String {
    if (value == null) return "拍摄时间未记录"
    return Instant.ofEpochMilli(value)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.CHINA))
}
