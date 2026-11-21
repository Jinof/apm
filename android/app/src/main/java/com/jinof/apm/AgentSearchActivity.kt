package com.jinof.apm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal data class AgentUiState(
    val request: String = "",
    val busy: Boolean = false,
    val status: String = "",
    val result: AgentSearchResult? = null,
)

class AgentSearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDockDestination(DockDestination.AGENT)
        finish()
    }
}

internal class AgentSearchController(private val activity: ComponentActivity) {
    private val database = ApmDatabase(activity.applicationContext)
    private val settingsStore by lazy { SettingsStore(activity.applicationContext) }
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    internal var state by mutableStateOf(AgentUiState())
        private set

    internal fun close() {
        executor.shutdownNow()
        database.close()
    }

    internal fun changeRequest(request: String) {
        state = state.copy(request = request)
    }

    internal fun runAgent() {
        val request = state.request.trim()
        if (request.isEmpty() || !busy.compareAndSet(false, true)) return
        state = state.copy(busy = true, status = "Agent 正在规划本地搜索…", result = null)
        executor.execute {
            try {
                val planner = ApiSearchPlanner(
                    candidateConfig = settingsStore.load(),
                )
                val result = SearchAgent(planner, DatabasePhotoSearchSkill(database)).run(request)
                activity.runOnUiThread {
                    busy.set(false)
                    state = state.copy(
                        busy = false,
                        result = result,
                        status = "完成 ${result.invocations.size} 次只读搜索 · 找到 ${result.photos.size} 张",
                    )
                }
            } catch (error: Exception) {
                activity.runOnUiThread {
                    busy.set(false)
                    state = state.copy(
                        busy = false,
                        status = "Agent 搜索失败：${error.message ?: error.javaClass.simpleName}",
                    )
                }
            }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentSearchScreen(
    state: AgentUiState,
    gallery: GalleryUiState,
    onSelectPhotos: () -> Unit,
    onAnnotateSelected: () -> Unit,
    onAnnotateAll: () -> Unit,
    onRequestChange: (String) -> Unit,
    onRun: () -> Unit,
    onPhoto: (PhotoCard) -> Unit,
) {
    val canSearch = gallery.search.enabled && !gallery.busy && !state.busy
    val thumbnailLoader = rememberPhotoThumbnailLoader()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Agent", modifier = Modifier.testTag("agent_title")) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("agent_content"),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = scaffoldPadding.calculateTopPadding() + 12.dp,
                bottom = scaffoldPadding.calculateBottomPadding() + 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                AnnotationActions(
                    busy = gallery.busy || state.busy,
                    selectedCount = gallery.selectedCount,
                    task = gallery.annotationTask,
                    onSelectPhotos = onSelectPhotos,
                    onAnnotateSelected = onAnnotateSelected,
                    onAnnotateAll = onAnnotateAll,
                )
            }
            if (gallery.status.isNotEmpty()) {
                item { StatusCard(gallery.status) }
            }
            item {
                OutlinedTextField(
                    value = state.request,
                    onValueChange = onRequestChange,
                    enabled = canSearch,
                    modifier = Modifier.fillMaxWidth().testTag("agent_request"),
                    label = { Text("搜索请求") },
                    placeholder = { Text("例如：找出夜晚有旺财在公园的照片") },
                    minLines = 2,
                    maxLines = 4,
                    shape = MaterialTheme.shapes.large,
                )
            }
            item {
                Button(
                    onClick = onRun,
                    enabled = canSearch && state.request.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(54.dp).testTag("agent_run"),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("让 Agent 搜索", fontWeight = FontWeight.SemiBold)
                }
            }
            if (state.busy) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().testTag("agent_status"),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            state.status.ifEmpty {
                                if (gallery.search.enabled) "Agent 会根据描述查询本地标注。"
                                else "先标注照片，即可用自然语言搜索。"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            state.result?.let { result ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(result.plan.summary, style = MaterialTheme.typography.titleMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(result.invocations) { invocation ->
                                AssistChip(
                                    onClick = {},
                                    label = { Text("${invocation.query} · ${invocation.resultCount}") },
                                )
                            }
                        }
                    }
                }
                if (result.photos.isEmpty()) {
                    item {
                        Text(
                            "没有匹配结果。可以减少条件，或先标注更多照片。",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(result.photos, key = PhotoCard::photoId) { photo ->
                        PhotoResultCard(
                            photo = photo,
                            annotating = photo.photoId in gallery.annotationTask?.activePhotoIds.orEmpty(),
                            loader = thumbnailLoader,
                            onClick = { onPhoto(photo) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnnotationActions(
    busy: Boolean,
    selectedCount: Int,
    task: AnnotationTaskProgress?,
    onSelectPhotos: () -> Unit,
    onAnnotateSelected: () -> Unit,
    onAnnotateAll: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FilledTonalButton(
            onClick = onSelectPhotos,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("select_annotation_photos"),
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("选择照片")
        }
        Button(
            onClick = onAnnotateSelected,
            enabled = !busy && selectedCount > 0,
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("annotate_selected"),
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("标注所选 ($selectedCount)")
        }
        AnnotationTaskCard(task, busy, onAnnotateAll)
    }
}

@Composable
private fun StatusCard(status: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("status_card"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
