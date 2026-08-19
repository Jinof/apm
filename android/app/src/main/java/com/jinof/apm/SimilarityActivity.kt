package com.jinof.apm

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private data class SimilarityUiState(
    val loading: Boolean = true,
    val indexed: Boolean = false,
    val results: List<SimilarPhotoCard> = emptyList(),
    val message: String = "正在读取本地相似索引…",
)

class SimilarityActivity : ComponentActivity() {
    private lateinit var database: ApmDatabase
    private val executor = Executors.newSingleThreadExecutor()
    private var state by mutableStateOf(SimilarityUiState())
    private lateinit var queryPhotoId: String
    private lateinit var queryUri: String
    private lateinit var queryName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        queryPhotoId = intent.getStringExtra(EXTRA_PHOTO_ID).orEmpty()
        queryUri = intent.getStringExtra(EXTRA_URI).orEmpty()
        queryName = intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty()
        if (queryPhotoId.isBlank() || queryUri.isBlank()) {
            finish()
            return
        }
        database = ApmDatabase(applicationContext)
        enableEdgeToEdge()
        setContent {
            ApmTheme {
                SimilarityScreen(
                    queryUri = queryUri,
                    queryName = queryName,
                    state = state,
                    onBack = ::finish,
                    onPhoto = ::openPhoto,
                )
            }
        }
        executor.execute {
            val indexed = database.hasVisualFeature(queryPhotoId)
            val results = if (indexed) database.similarPhotos(queryPhotoId) else emptyList()
            val message = when {
                !indexed -> OnnxRuntimeDinoV2ImageEncoder.availability(applicationContext)
                    ?: "这张照片尚未建立 DINOv2 相似索引，请返回并点击“检查相似”。"
                results.isEmpty() -> "没有找到达到当前门槛的相似照片。"
                else -> "找到 ${results.size} 张；分数只来自本地图像与主体特征。"
            }
            runOnUiThread {
                state = SimilarityUiState(
                    loading = false,
                    indexed = indexed,
                    results = results,
                    message = message,
                )
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        database.close()
        super.onDestroy()
    }

    private fun openPhoto(card: SimilarPhotoCard) {
        startActivity(
            PhotoViewerActivity.intent(
                context = this,
                photoId = card.result.candidatePhotoId,
                uri = card.uri,
            ),
        )
    }

    companion object {
        const val EXTRA_PHOTO_ID = "photo_id"
        const val EXTRA_URI = "uri"
        const val EXTRA_DISPLAY_NAME = "display_name"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimilarityScreen(
    queryUri: String,
    queryName: String,
    state: SimilarityUiState,
    onBack: () -> Unit,
    onPhoto: (SimilarPhotoCard) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("相似照片") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = padding.calculateTopPadding() + 10.dp,
                bottom = padding.calculateBottomPadding() + 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        SimilarityThumbnail(queryUri, 96)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("当前照片", style = MaterialTheme.typography.labelLarge)
                            Text(
                                queryName.ifBlank { "照片" },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "全图 · 4×4 构图 · 人脸/宠物/物体",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (state.loading) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            item {
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (!state.indexed && !state.loading) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            items(state.results, key = { it.result.candidatePhotoId }) { card ->
                SimilarityResultCard(card, onClick = { onPhoto(card) })
            }
        }
    }
}

@Composable
private fun SimilarityResultCard(card: SimilarPhotoCard, onClick: () -> Unit) {
    val result = card.result
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            SimilarityThumbnail(card.uri, 112)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                AssistChip(
                    onClick = {},
                    label = { Text(result.relationship.displayName, fontWeight = FontWeight.Bold) },
                    leadingIcon = { Icon(Icons.Outlined.ImageSearch, contentDescription = null) },
                )
                Text(
                    card.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        append("全图 ${percent(result.globalSimilarity)} · 构图 ${percent(result.compositionSimilarity)}")
                        result.subjectSimilarity?.let { append(" · 主体 ${percent(it)}") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (result.captureDeltaMillis != null) {
                    Text(
                        "拍摄间隔 ${formatDelta(result.captureDeltaMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    result.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SimilarityThumbnail(uriText: String, sizeDp: Int) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = uriText) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(Uri.parse(uriText), Size(360, 360), null)
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
        } else {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "照片缩略图",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun percent(value: Float): String = "${(value * 100).toInt()}%"

private fun formatDelta(millis: Long): String = when {
    millis < 1_000 -> "${millis}ms"
    millis < 60_000 -> "${millis / 1_000}s"
    millis < 3_600_000 -> "${millis / 60_000}min"
    else -> "${millis / 3_600_000}h"
}
