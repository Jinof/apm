package com.jinof.apm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private data class AgentUiState(
    val request: String = "",
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val status: String = "标注照片后，Agent 才能调用本地搜索。",
    val result: AgentSearchResult? = null,
)

class AgentSearchActivity : ComponentActivity() {
    private lateinit var database: ApmDatabase
    private lateinit var settingsStore: SettingsStore
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var state by mutableStateOf(AgentUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        database = ApmDatabase(applicationContext)
        settingsStore = SettingsStore(applicationContext)
        setContent {
            ApmTheme {
                AgentSearchScreen(
                    state = state,
                    onBack = { finish() },
                    onRequestChange = { state = state.copy(request = it) },
                    onRun = ::runAgent,
                    onPhoto = ::openPhoto,
                )
            }
        }
        refreshAvailability()
    }

    override fun onResume() {
        super.onResume()
        if (::database.isInitialized && !busy.get()) refreshAvailability()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        database.close()
        super.onDestroy()
    }

    private fun refreshAvailability() {
        executor.execute {
            val enabled = database.annotationCount() > 0
            runOnUiThread {
                state = state.copy(
                    enabled = enabled,
                    status = if (enabled) {
                        "Agent 已就绪。它只有 search_photos，只读查询本地索引。"
                    } else {
                        "先返回相册完成一次标注，再使用 Agent 搜索。"
                    },
                )
            }
        }
    }

    private fun runAgent() {
        val request = state.request.trim()
        if (!state.enabled || request.isEmpty() || !busy.compareAndSet(false, true)) return
        state = state.copy(busy = true, status = "Agent 正在规划本地搜索…", result = null)
        executor.execute {
            try {
                val identities = database.localIdentitySummaries()
                val planner = OllamaSearchPlanner(
                    candidateConfig = settingsStore.load(),
                    profile = RecognitionProfile(
                        personNames = identities.filter { it.kind == LocalIdentityKind.PERSON }.map { it.name },
                        petNames = identities.filter { it.kind == LocalIdentityKind.PET }.map { it.name },
                    ),
                )
                val result = SearchAgent(planner, DatabasePhotoSearchSkill(database)).run(request)
                runOnUiThread {
                    busy.set(false)
                    state = state.copy(
                        busy = false,
                        result = result,
                        status = "完成 ${result.invocations.size} 次只读搜索 · 找到 ${result.photos.size} 张",
                    )
                }
            } catch (error: Exception) {
                runOnUiThread {
                    busy.set(false)
                    state = state.copy(
                        busy = false,
                        status = "Agent 搜索失败：${error.message ?: error.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    private fun openPhoto(photo: PhotoCard) {
        startActivity(PhotoViewerActivity.intent(this, photo.photoId, photo.uri))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentSearchScreen(
    state: AgentUiState,
    onBack: () -> Unit,
    onRequestChange: (String) -> Unit,
    onRun: () -> Unit,
    onPhoto: (PhotoCard) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("agent_back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回相册")
                    }
                },
                title = {
                    Column {
                        Text("Agent 搜索", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "一项只读 SKILL",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { scaffoldPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = scaffoldPadding.calculateTopPadding() + 12.dp,
                bottom = scaffoldPadding.calculateBottomPadding() + 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.Top) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.tertiary,
                        ) {
                            Icon(
                                Icons.Outlined.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiary,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                "描述你想找的照片",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            Spacer(Modifier.height(5.dp))
                            Text(
                                "Agent 会规划 1–4 个关键词，并自主调用本地 search_photos；没有删除、移动或标注工具。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.78f),
                            )
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = state.request,
                    onValueChange = onRequestChange,
                    enabled = state.enabled && !state.busy,
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
                    enabled = state.enabled && state.request.isNotBlank() && !state.busy,
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
                            state.status,
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
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth().clickable { onPhoto(photo) },
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    photo.caption,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    buildList {
                                        addAll(photo.annotation.recognizedSubjects.map { it.name })
                                        addAll(photo.annotation.tags.take(5))
                                    }.distinct().joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    photo.displayName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
