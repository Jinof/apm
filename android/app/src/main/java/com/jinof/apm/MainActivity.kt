package com.jinof.apm

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

private enum class AnnotationScope(val label: String) {
    SELECTED("所选"),
    ALL("全量"),
}

private const val DEBUG_ANNOTATE_SELECTED_ACTION = "com.jinof.apm.debug.ANNOTATE_SELECTED"
private const val DEBUG_ANNOTATION_ALIAS = "com.jinof.apm.DebugAnnotationAlias"
private const val DEBUG_SEARCH_ACTION = "com.jinof.apm.debug.SEARCH"
private const val DEBUG_SEARCH_ALIAS = "com.jinof.apm.DebugSearchAlias"
private const val DEBUG_SEARCH_QUERY = "query"

class MainActivity : ComponentActivity() {
    private lateinit var database: ApmDatabase
    private lateinit var settingsStore: SettingsStore
    private lateinit var scanner: MediaStoreScanner
    private lateinit var agentController: AgentSearchController
    private lateinit var selectionSession: AnnotationSelectionSession
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var uiState by mutableStateOf(GalleryUiState())
    private var selectedPhotoRange by mutableStateOf<PhotoHeatmapSelection?>(null)
    private var requestedDockDestination by mutableStateOf(DockDestination.ALBUM)
    private var initialized = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (hasFullPhotoAccess() || hasPartialPhotoAccess()) {
            startAnnotation(AnnotationScope.ALL)
        } else {
            updateStatus("未获得照片读取权限。没有授权时不会扫描或标注。")
        }
    }

    private val selectedPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
        ::onPhotosSelected,
    )

    internal fun onPhotosSelected(uris: List<Uri>) {
        if (uris.isEmpty()) {
            updateStatus("没有选择新照片，当前所选范围保持不变。")
        } else {
            importSelectedPhotos(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedPhotoRange = intent.photoHeatmapSelection()
        requestedDockDestination = intent.dockDestination()
        enableEdgeToEdge()
        database = ApmDatabase(applicationContext)
        selectionSession = ViewModelProvider(this)[AnnotationSelectionSession::class.java]
        selectionSession.initialize(database)
        settingsStore = SettingsStore(applicationContext)
        scanner = MediaStoreScanner(applicationContext, database)
        agentController = AgentSearchController(this)
        initialized = true
        setContent {
            ApmTheme {
                AppDockPager(
                    requestedDestination = requestedDockDestination,
                    busy = uiState.busy || agentController.state.busy,
                    searchEnabled = uiState.search.enabled,
                    onExit = { finish() },
                    onDestinationSettled = { destination ->
                        requestedDockDestination = destination
                        when (destination) {
                            DockDestination.AGENT -> agentController.refreshAvailability()
                            else -> Unit
                        }
                    },
                ) { destination, navigate ->
                    when (destination) {
                        DockDestination.ALBUM -> GalleryScreen(
                            state = uiState,
                            onQueryChange = { query ->
                                val activeSearchQuery = uiState.activeSearchQuery
                                    ?.takeIf { it == query.trim() }
                                uiState = uiState.copy(
                                    query = query,
                                    activeSearchQuery = activeSearchQuery,
                                    results = if (activeSearchQuery == null) emptyList() else uiState.results,
                                )
                            },
                            onSearch = ::submitSearch,
                            onSuggestion = { suggestion ->
                                uiState = uiState.copy(query = suggestion)
                                submitSearch()
                            },
                            onSelectPhotos = ::selectPhotosForAnnotation,
                            onAnnotateSelected = { startAnnotation(AnnotationScope.SELECTED) },
                            onAnnotateAll = ::onAnnotateAllRequested,
                            onHeatmap = { navigate(DockDestination.HEATMAP) },
                            initialSelectedRange = selectedPhotoRange,
                            startupStoryEnabled = requestedDockDestination == DockDestination.ALBUM,
                            onPhoto = ::openPhoto,
                        )

                        DockDestination.HEATMAP -> PhotoHeatmapPage(
                            photos = uiState.galleryPhotos,
                            onViewPhotos = { selection ->
                                selectedPhotoRange = selection
                                navigate(DockDestination.ALBUM)
                            },
                        )

                        DockDestination.AGENT -> AgentSearchScreen(
                            state = agentController.state,
                            onRequestChange = agentController::changeRequest,
                            onRun = agentController::runAgent,
                            onPhoto = { photo ->
                                startActivity(PhotoViewerActivity.intent(this, photo.photoId, photo.uri))
                            },
                        )

                        DockDestination.SETTINGS -> ModelSettingsScreen(isActive = requestedDockDestination == DockDestination.SETTINGS)
                    }
                }
            }
        }
        refreshLibraryState("准备就绪。先选择照片，再让 VLM 建立可搜索标注。")
        when {
            isDebugRequest(DEBUG_ANNOTATE_SELECTED_ACTION, DEBUG_ANNOTATION_ALIAS) -> {
                intent.setAction(null)
                window.decorView.post { startAnnotation(AnnotationScope.SELECTED) }
            }
            isDebugRequest(DEBUG_SEARCH_ACTION, DEBUG_SEARCH_ALIAS) -> {
                val query = intent.getStringExtra(DEBUG_SEARCH_QUERY).orEmpty().trim()
                intent.setAction(null)
                window.decorView.post {
                    if (query.isEmpty() || !database.searchAvailability().enabled) {
                        updateStatus("USB 验收搜索不可用：查询为空或尚无标注。")
                    } else {
                        uiState = uiState.copy(query = query)
                        runSearch(query, acceptanceLog = true)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        selectedPhotoRange = intent.photoHeatmapSelection()
        requestedDockDestination = intent.dockDestination()
    }

    override fun onResume() {
        super.onResume()
        if (initialized && !busy.get()) {
            refreshLibraryState(null)
            agentController.refreshAvailability()
        }
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) selectionSession.closeSession()
        executor.shutdownNow()
        agentController.close()
        database.close()
        super.onDestroy()
    }

    private fun isDebugRequest(action: String, alias: String): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            intent.action == action &&
            intent.component?.className == alias

    private fun onAnnotateAllRequested() {
        if (validatedInferenceConfig() == null) return
        val action = PhotoAccessPolicy.nextScanAction(
            sdkInt = Build.VERSION.SDK_INT,
            hasFullAccess = hasFullPhotoAccess(),
            hasPartialAccess = hasPartialPhotoAccess(),
        )
        when (action) {
            PhotoAccessAction.SCAN_NOW -> startAnnotation(AnnotationScope.ALL)
            PhotoAccessAction.REQUEST_INITIAL_ACCESS,
            PhotoAccessAction.REQUEST_RESELECTION,
            -> {
                permissionLauncher.launch(requiredPhotoPermissions())
            }
        }
    }

    private fun selectPhotosForAnnotation() {
        if (busy.get()) {
            updateStatus("已有任务正在运行，请稍候。")
            return
        }
        selectedPhotoLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    private fun importSelectedPhotos(uris: List<Uri>) {
        if (!beginWork("正在读取所选 ${uris.size} 张照片…")) return
        val persistFailures = uris.count { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.isFailure
        }
        executor.execute {
            try {
                val result = scanner.scanSelected(uris) { processed, total, name ->
                    updateStatus("读取所选照片 $processed / $total · $name")
                }
                if (!selectionSession.replaceSelection(database, result.photoIds)) return@execute
                val report = result.scan
                val errorSuffix = if (report.errors.isEmpty()) {
                    ""
                } else {
                    " · 读取失败 ${report.errors.size}"
                }
                val persistSuffix = if (persistFailures == 0) {
                    ""
                } else {
                    " · ${persistFailures} 项授权仅在本次运行有效"
                }
                completeWork(
                    "已授权并选择 ${result.photoIds.size} 张 · 内容校验 ${report.hashed} 张$errorSuffix$persistSuffix",
                )
            } catch (error: Exception) {
                failWork("读取所选照片失败：${error.message}")
            }
        }
    }

    private fun requiredPhotoPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasFullPhotoAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= 33 ->
            checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        else ->
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasPartialPhotoAccess(): Boolean =
        Build.VERSION.SDK_INT >= 34 &&
            checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
            PackageManager.PERMISSION_GRANTED

    private fun validatedInferenceConfig(): InferenceConfig? {
        val config = try {
            EndpointPolicy.validate(settingsStore.load())
        } catch (error: Exception) {
            updateStatus("模型配置无效：${error.message}")
            requestedDockDestination = DockDestination.SETTINGS
            return null
        }
        return config
    }

    private fun publishAnnotationTask(task: AnnotationTaskProgress, photos: List<GalleryPhotoCard>? = null) {
        runOnUiThread {
            if (!isDestroyed && !isFinishing) {
                uiState = uiState.copy(annotationTask = task, galleryPhotos = photos ?: uiState.galleryPhotos)
            }
        }
    }

    private fun startAnnotation(scope: AnnotationScope) {
        if (scope == AnnotationScope.SELECTED && database.annotationSelectionCount() == 0) {
            updateStatus("请先通过“选择照片”授权并选择要标注的照片。")
            return
        }
        val config = validatedInferenceConfig() ?: return
        val initialStatus = if (scope == AnnotationScope.ALL) "正在扫描全量已授权照片…" else "准备标注所选照片…"
        if (!beginWork(initialStatus)) return
        var task = AnnotationTaskProgress(scope.label, SystemClock.elapsedRealtime())
            .log(initialStatus, SystemClock.elapsedRealtime())
        publishAnnotationTask(task)
        executor.execute {
            try {
                val scanReport = if (scope == AnnotationScope.ALL) {
                    task = task.copy(phase = "扫描中")
                    publishAnnotationTask(task)
                    scanner.scan { processed, total, _ ->
                        if (processed == total || processed % 10 == 0) {
                            task = task.log("扫描照片 $processed/$total", SystemClock.elapsedRealtime())
                            publishAnnotationTask(task)
                        }
                    }
                } else null
                val client = ApiVlmClient(contentResolver, config)
                val scopeCount = if (scope == AnnotationScope.SELECTED) database.annotationSelectionCount() else database.accessibleCount()
                val pending = if (scope == AnnotationScope.SELECTED) {
                    database.pendingSelectedPhotos(config.modelName, AnnotationContract.PROMPT_VERSION)
                } else database.pendingAllPhotos(config.modelName, AnnotationContract.PROMPT_VERSION)
                var gallery = database.galleryPhotos()
                task = task.copy(total = pending.size, skipped = (scopeCount - pending.size).coerceAtLeast(0), phase = "标注中")
                    .log("待标注 ${pending.size} 张，跳过 ${scopeCount - pending.size} 张", SystemClock.elapsedRealtime())
                publishAnnotationTask(task, gallery)
                for (photo in pending) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    task = task.startPhoto(photo, SystemClock.elapsedRealtime())
                    publishAnnotationTask(task)
                    try {
                        val annotation = client.annotate(Uri.parse(photo.uri)) { stage ->
                            task = task.log(stage, SystemClock.elapsedRealtime())
                            publishAnnotationTask(task)
                        }
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        task = task.log("写入本地标注", SystemClock.elapsedRealtime())
                        publishAnnotationTask(task)
                        database.insertAnnotation(photo.photoId, annotation, config.modelName, AnnotationContract.PROMPT_VERSION)
                        val annotatedAt = Instant.now().toString()
                        gallery = gallery.map { card ->
                            if (card.photoId != photo.photoId) card else card.copy(annotation = annotation,
                                modelName = config.modelName, promptVersion = AnnotationContract.PROMPT_VERSION, annotatedAt = annotatedAt)
                        }
                        runOnUiThread {
                            if (!isDestroyed && !isFinishing) uiState = uiState.copy(results = uiState.results.map { card ->
                                if (card.photoId != photo.photoId) card else card.copy(annotation = annotation, caption = annotation.caption,
                                    modelName = config.modelName, promptVersion = AnnotationContract.PROMPT_VERSION, annotatedAt = annotatedAt)
                            })
                        }
                        task = task.log("标注成功 · ${task.currentPhotoName}", SystemClock.elapsedRealtime())
                            .finishPhoto(true, SystemClock.elapsedRealtime())
                    } catch (error: Exception) {
                        if (error is InterruptedException || Thread.currentThread().isInterrupted) throw InterruptedException()
                        task = task.log("标注失败 · ${task.currentPhotoName} · ${annotationFailureMessage(error)}", SystemClock.elapsedRealtime())
                            .finishPhoto(false, SystemClock.elapsedRealtime())
                    }
                    publishAnnotationTask(task, gallery)
                }
                task = task.log(if (pending.isEmpty()) "无需重复标注" else "任务结束", SystemClock.elapsedRealtime())
                    .finish(SystemClock.elapsedRealtime())
                publishAnnotationTask(task, gallery)
                val scanSuffix = scanReport?.let { " · 扫描可见 ${it.visible} 张 · 读取失败 ${it.errors.size}" }.orEmpty()
                completeWork("${scope.label}范围 $scopeCount 张 · 成功 ${task.succeeded} · 失败 ${task.failed} · 跳过 ${task.skipped}$scanSuffix")
            } catch (error: Exception) {
                val message = annotationFailureMessage(error)
                task = task.log(message, SystemClock.elapsedRealtime()).finish(SystemClock.elapsedRealtime(), fatal = true)
                publishAnnotationTask(task)
                failWork("${scope.label}标注失败：$message")
            }
        }
    }

    private fun submitSearch() {
        val query = uiState.query.trim()
        if (!uiState.search.enabled || query.isEmpty()) return
        runSearch(query)
    }

    private fun runSearch(query: String, acceptanceLog: Boolean = false) {
        if (!beginWork("正在搜索“$query”…")) return
        executor.execute {
            try {
                val cards = database.search(query)
                if (acceptanceLog) {
                    Log.i("APM.Acceptance", "search query=$query results=${cards.size}")
                }
                val availability = database.searchAvailability()
                val galleryPhotos = database.galleryPhotos()
                val accessible = database.accessibleCount()
                val selected = database.annotationSelectionCount()
                runOnUiThread {
                    busy.set(false)
                    uiState = uiState.copy(
                        activeSearchQuery = query,
                        results = cards,
                        galleryPhotos = galleryPhotos,
                        search = availability,
                        accessibleCount = accessible,
                        selectedCount = selected,
                        busy = false,
                        status = "“$query” · ${cards.size} 个结果",
                    )
                }
            } catch (error: Exception) {
                failWork("搜索失败：${error.message}")
            }
        }
    }

    private fun refreshLibraryState(message: String?) {
        executor.execute {
            try {
                val availability = database.searchAvailability()
                val query = if (availability.enabled) uiState.query else ""
                val activeQuery = if (availability.enabled) uiState.activeSearchQuery else null
                val cards = activeQuery?.let(database::search).orEmpty()
                val galleryPhotos = database.galleryPhotos()
                val accessible = database.accessibleCount()
                val selected = database.annotationSelectionCount()
                runOnUiThread {
                    uiState = uiState.copy(
                        query = query,
                        activeSearchQuery = activeQuery,
                        results = cards,
                        galleryPhotos = galleryPhotos,
                        search = availability,
                        accessibleCount = accessible,
                        selectedCount = selected,
                        status = message ?: uiState.status,
                    )
                }
            } catch (error: Exception) {
                updateStatus("读取本地索引失败：${error.message}")
            }
        }
    }

    private fun beginWork(message: String): Boolean {
        if (!busy.compareAndSet(false, true)) {
            updateStatus("已有任务正在运行，请稍候。")
            return false
        }
        runOnUiThread { uiState = uiState.copy(busy = true, status = message) }
        return true
    }

    private fun completeWork(message: String) {
        val availability = database.searchAvailability()
        val query = if (availability.enabled) uiState.query else ""
        val activeQuery = if (availability.enabled) uiState.activeSearchQuery else null
        val cards = activeQuery?.let(database::search).orEmpty()
        val galleryPhotos = database.galleryPhotos()
        val accessible = database.accessibleCount()
        val selected = database.annotationSelectionCount()
        runOnUiThread {
            busy.set(false)
            uiState = uiState.copy(
                query = query,
                activeSearchQuery = activeQuery,
                results = cards,
                galleryPhotos = galleryPhotos,
                search = availability,
                accessibleCount = accessible,
                selectedCount = selected,
                busy = false,
                status = message,
            )
        }
    }

    private fun failWork(message: String) {
        runOnUiThread {
            busy.set(false)
            uiState = uiState.copy(busy = false, status = message)
        }
    }

    private fun updateStatus(message: String) {
        runOnUiThread { uiState = uiState.copy(status = message) }
    }

    private fun openPhoto(photoId: String, uri: String) {
        startActivity(PhotoViewerActivity.intent(this, photoId, uri))
    }


}

private const val ANNOTATION_LOG_TAG = "APM.Annotation"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryScreen(
    state: GalleryUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSuggestion: (String) -> Unit,
    onSelectPhotos: () -> Unit,
    onAnnotateSelected: () -> Unit,
    onAnnotateAll: () -> Unit,
    onHeatmap: () -> Unit,
    initialSelectedRange: PhotoHeatmapSelection?,
    startupStoryEnabled: Boolean,
    onPhoto: (String, String) -> Unit,
) {
    val zoneId = ZoneId.systemDefault()
    var displayMode by remember { mutableStateOf(PhotoWallDisplayMode.THUMBNAILS) }
    var selectedRange by remember(state.galleryPhotos, initialSelectedRange) {
        mutableStateOf(initialSelectedRange)
    }
    val dayGroups = remember(state.galleryPhotos, selectedRange, zoneId) {
        PhotoWallOrganizer.groupByDay(state.galleryPhotos, zoneId)
            .let { groups ->
                selectedRange?.let { range -> groups.filter { range.contains(it.date) } } ?: groups
            }
    }
    val searchActive = state.activeSearchQuery != null
    val density = LocalDensity.current
    var showStartupStory by rememberSaveable { mutableStateOf(true) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("APM", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "AI 相册",
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
        Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "照片墙，向右滑动打开年度照片热力图"
                }
                .testTag("photo_wall_gesture_surface"),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = scaffoldPadding.calculateTopPadding() + 8.dp,
                bottom = scaffoldPadding.calculateBottomPadding() + 28.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                AnnotationActions(
                    busy = state.busy,
                    selectedCount = state.selectedCount,
                    task = state.annotationTask,
                    onSelectPhotos = onSelectPhotos,
                    onAnnotateSelected = onAnnotateSelected,
                    onAnnotateAll = onAnnotateAll,
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                SearchPanel(
                    state = state,
                    onQueryChange = onQueryChange,
                    onSearch = onSearch,
                    onSuggestion = onSuggestion,
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                AnimatedVisibility(visible = state.busy) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                StatusCard(state.status)
            }
            if (searchActive) {
                if (state.results.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyState(
                            icon = Icons.Outlined.Search,
                            title = "没有找到匹配照片",
                            body = "换一个来自描述或标签的关键词试试，或清空输入返回照片墙。",
                        )
                    }
                } else {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionTitle("搜索结果", "${state.results.size} 张")
                    }
                    gridItems(
                        items = state.results,
                        key = { "search-${it.photoId}-${it.uri}" },
                        span = { GridItemSpan(maxLineSpan) },
                    ) { photo ->
                        PhotoResultCard(
                            photo = photo,
                            annotating = state.annotationTask?.currentPhotoId == photo.photoId,
                            onClick = { onPhoto(photo.photoId, photo.uri) },
                        )
                    }
                }
            } else if (state.galleryPhotos.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        icon = Icons.Outlined.PhotoLibrary,
                        title = "照片墙等待授权照片",
                        body = "先通过“选择照片”授权照片，或运行全量标注并授权相册范围。照片无需标注也会显示。",
                    )
                }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    PhotoWallControls(
                        photoCount = state.galleryPhotos.size,
                        displayMode = displayMode,
                        selectedRange = selectedRange,
                        onDisplayMode = { displayMode = it },
                        onClearRange = { selectedRange = null },
                    )
                }
                dayGroups.forEach { group ->
                    item(
                        key = "day-${group.date ?: "unknown"}",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        PhotoDayHeader(group)
                    }
                    if (displayMode == PhotoWallDisplayMode.THUMBNAILS) {
                        gridItems(
                            items = group.photos,
                            key = { "wall-thumb-${it.photoId}" },
                        ) { photo ->
                            PhotoWallThumbnail(
                                photo = photo,
                                annotating = state.annotationTask?.currentPhotoId == photo.photoId,
                                onClick = { onPhoto(photo.photoId, photo.uri) },
                            )
                        }
                    } else {
                        gridItems(
                            items = group.photos,
                            key = { "wall-detail-${it.photoId}" },
                            span = { GridItemSpan(maxLineSpan) },
                        ) { photo ->
                            PhotoWallDetailCard(
                                photo = photo,
                                annotating = state.annotationTask?.currentPhotoId == photo.photoId,
                                onClick = { onPhoto(photo.photoId, photo.uri) },
                            )
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                PrivacyFooter()
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(32.dp)
                .pointerInput(onHeatmap) {
                    val thresholdPx = with(density) { 24.dp.toPx() }
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        var distanceX = 0f
                        var distanceY = 0f
                        var shouldOpenHeatmap = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            distanceX += change.position.x - change.previousPosition.x
                            distanceY += change.position.y - change.previousPosition.y
                            if (!shouldOpenHeatmap &&
                                distanceX >= thresholdPx &&
                                distanceX > abs(distanceY)
                            ) {
                                shouldOpenHeatmap = true
                            }
                        }
                        if (shouldOpenHeatmap) onHeatmap()
                    }
                }
                .semantics {
                    contentDescription = "从左边缘向右滑动打开年度照片热力图"
                }
                .testTag("photo_wall_gesture_edge"),
        )
        }
    }
        if (startupStoryEnabled && showStartupStory) {
        StartupStoryDialog(onDismiss = { showStartupStory = false })
    }
}

@Composable
private fun StartupStoryDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("startup_story_dialog"),
        icon = {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
        },
        title = { Text("让照片，自己说出故事") },
        text = {
            Text("VLM 在授权范围内理解画面，描述与标签只存放在你的设备。")
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.testTag("startup_story_continue"),
            ) {
                Text("开始探索")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("startup_story_skip"),
            ) {
                Text("稍后再看")
            }
        },
    )
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
private fun SearchPanel(
    state: GalleryUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSuggestion: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        SectionTitle("搜索", if (state.search.enabled) "来自实际标注" else "标注后开启")
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            enabled = state.search.enabled && !state.busy,
            modifier = Modifier.fillMaxWidth().testTag("search_input"),
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            placeholder = {
                Text(if (state.search.enabled) "搜索描述、物体、动作或场景" else "完成一次标注后即可搜索")
            },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                IconButton(
                    onClick = onSearch,
                    enabled = state.search.enabled && state.query.isNotBlank() && !state.busy,
                ) {
                    Icon(Icons.Outlined.Search, contentDescription = "提交搜索")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            supportingText = if (!state.search.enabled) {
                { Text("当前没有可搜索标注，也不会显示示例或预设词。") }
            } else {
                null
            },
        )
        if (state.search.suggestions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.search.suggestions) { suggestion ->
                    AssistChip(
                        onClick = { onSuggestion(suggestion) },
                        label = { Text(suggestion) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}


@Composable
private fun PhotoWallControls(
    photoCount: Int,
    displayMode: PhotoWallDisplayMode,
    selectedRange: PhotoHeatmapSelection?,
    onDisplayMode: (PhotoWallDisplayMode) -> Unit,
    onClearRange: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("photo_wall_controls"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("照片墙", "$photoCount 张 · 按拍摄日")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PhotoWallDisplayMode.entries.forEach { mode ->
                FilterChip(
                    selected = displayMode == mode,
                    onClick = { onDisplayMode(mode) },
                    label = { Text(mode.label) },
                    leadingIcon = {
                        Icon(
                            if (mode == PhotoWallDisplayMode.THUMBNAILS) {
                                Icons.Outlined.GridView
                            } else {
                                Icons.Outlined.ViewAgenda
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    modifier = Modifier.testTag("photo_wall_mode_${mode.name.lowercase()}"),
                )
            }
        }
        if (selectedRange != null) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    "只看 ${heatmapSelectionLabel(selectedRange)} · ${selectedRange.count} 张照片",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("selected_photo_range_summary"),
                )
                TextButton(
                    onClick = onClearRange,
                    modifier = Modifier.testTag("clear_photo_range"),
                ) {
                    Text("显示全部时间")
                }
            }
        }
    }
}


@Composable
private fun PhotoDayHeader(group: PhotoDayGroup) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            group.date?.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA))
                ?: "拍摄时间未记录",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "${group.photos.size} 张",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
internal fun PhotoWallThumbnail(photo: GalleryPhotoCard, annotating: Boolean = false, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(onClick = onClick)
            .testTag("photo_wall_thumbnail_${photo.photoId}"),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Thumbnail(
            uriText = photo.uri,
            modifier = Modifier.fillMaxSize(),
            requestSize = 300,
            contentDescription = photo.displayName,
            details = photo.savedDetails(),
            annotating = annotating,
        )
    }
}

@Composable
internal fun PhotoWallDetailCard(
    photo: GalleryPhotoCard,
    annotating: Boolean = false,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("photo_wall_detail_${photo.photoId}"),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.padding(12.dp)) {
            Thumbnail(photo.uri, contentDescription = photo.displayName, details = photo.savedDetails(), annotating = annotating)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                val annotation = photo.annotation
                Text(
                    annotation?.caption ?: "尚未标注",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (annotation == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (annotation != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        facetSummary(annotation.facets),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (annotation.tags.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            annotation.tags.take(5).joinToString(" · "),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "${photo.displayName} · ${formatCaptureDate(photo.dateTakenMillis)}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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

@Composable
private fun SectionTitle(title: String, supporting: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            supporting,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(18.dp).size(34.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun PhotoResultCard(photo: PhotoCard, annotating: Boolean = false, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.padding(12.dp)) {
            Thumbnail(photo.uri, contentDescription = photo.displayName, details = photo.savedDetails(), annotating = annotating)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    photo.caption,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    facetSummary(photo.annotation.facets),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (photo.annotation.recognizedSubjects.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        photo.annotation.recognizedSubjects.joinToString(" · ") {
                            "${it.kind} · ${it.name}"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (photo.annotation.tags.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        photo.annotation.tags.take(5).joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "${photo.displayName} · ${formatDate(photo)}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Thumbnail(
    uriText: String,
    modifier: Modifier = Modifier.size(116.dp),
    requestSize: Int = 360,
    contentDescription: String = "照片缩略图",
    details: StoredAnnotationDetails? = null,
    annotating: Boolean = false,
) {
    var detailsOpen by remember(uriText) { mutableStateOf(false) }
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = uriText, key2 = requestSize) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(
                    Uri.parse(uriText),
                    Size(requestSize, requestSize),
                    null,
                )
            }.getOrNull()
        }
    }
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            Icon(
                Icons.Outlined.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        } else {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (annotating) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(32.dp)
                    .background(Color.White, CircleShape).padding(3.dp)
                    .semantics { this.contentDescription = "正在标注" }
                    .testTag("photo_annotation_running"),
                color = Color(0xFF15803D), strokeWidth = 3.dp,
            )
        } else if (details != null) {
            IconButton(onClick = { detailsOpen = true }, modifier = Modifier.align(Alignment.BottomEnd)
                .padding(2.dp).size(40.dp).semantics { this.contentDescription = "已标注，查看详情" }
                .testTag("photo_annotation_badge")) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF15803D),
                    modifier = Modifier.size(32.dp).background(Color.White, CircleShape))
            }
        }
    }
    if (detailsOpen && details != null) PhotoAnnotationDetailsDialog(details) { detailsOpen = false }
}

@Composable
private fun PrivacyFooter() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.Shield,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "原图只读 · 标注存于本机 · 远程推理需授权",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

private fun facetSummary(facets: PhotoFacets): String = buildList {
    add(facets.daylight)
    facets.objects.forEach { add("${it.name} ×${it.count}") }
    facets.people.forEach { add("${it.presentation} ×${it.count}") }
    addAll(facets.actions)
    addAll(facets.scenes)
}.filter { it.isNotBlank() && it != "不确定" }.joinToString(" · ")

private fun formatDate(photo: PhotoCard): String {
    return formatCaptureDate(photo.dateTakenMillis)
}

private fun formatCaptureDate(millis: Long?): String {
    if (millis == null) return "拍摄时间未记录"
    return DateTimeFormatter.ofPattern("yyyy-MM-dd")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(millis))
}
