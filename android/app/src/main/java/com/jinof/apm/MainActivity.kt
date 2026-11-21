package com.jinof.apm

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
private const val ALBUM_COLUMNS = 5

class MainActivity : ComponentActivity() {
    private lateinit var database: ApmDatabase
    private lateinit var settingsStore: SettingsStore
    private lateinit var scanner: MediaStoreScanner
    private lateinit var agentController: AgentSearchController
    private lateinit var selectionSession: AnnotationSelectionSession
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val annotationLifetime = Any()
    private var annotationDisposed = false
    private var annotationClient: ApiVlmClient? = null
    private var uiState by mutableStateOf(GalleryUiState())
    private lateinit var startup: AppStartup
    private var selectedPhotoRange by mutableStateOf<PhotoHeatmapSelection?>(null)
    private var requestedDockDestination by mutableStateOf(DockDestination.ALBUM)
    private var firstResume = true

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
        val navigation = savedInstanceState?.getBundle("navigation")?.let { Intent().putExtras(it) } ?: intent
        selectedPhotoRange = navigation.photoHeatmapSelection()
        requestedDockDestination = navigation.dockDestination()
        enableEdgeToEdge()
        database = ApmDatabase(applicationContext)
        selectionSession = ViewModelProvider(this)[AnnotationSelectionSession::class.java]
        settingsStore = SettingsStore(applicationContext)
        scanner = MediaStoreScanner(applicationContext, database)
        agentController = AgentSearchController(this)
        startup = AppStartup(this) {
            ApmTheme {
                AppDockPager(
                    requestedDestination = requestedDockDestination,
                    onExit = { finish() },
                    onDestinationSettled = { requestedDockDestination = it },
                ) { destination, navigate ->
                    when (destination) {
                        DockDestination.ALBUM -> GalleryScreen(
                            state = uiState,
                            onQueryChange = { query ->
                                val activeSearchQuery = uiState.activeSearchQuery
                                    ?.takeIf { it == query.trim() }
                                uiState = uiState.copy(
                                    query = query,
                                    searchError = null,
                                    activeSearchQuery = activeSearchQuery,
                                    results = if (activeSearchQuery == null) emptyList() else uiState.results,
                                )
                            },
                            onSearch = ::submitSearch,
                            onSuggestion = { suggestion ->
                                uiState = uiState.copy(query = suggestion)
                                submitSearch()
                            },
                            onHeatmap = { navigate(DockDestination.HEATMAP) },
                            selectedRange = selectedPhotoRange,
                            onClearRange = { selectedPhotoRange = null },
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
                            gallery = uiState,
                            onSelectPhotos = ::selectPhotosForAnnotation,
                            onAnnotateSelected = { startAnnotation(AnnotationScope.SELECTED) },
                            onAnnotateAll = ::onAnnotateAllRequested,
                            onRequestChange = agentController::changeRequest,
                            onRun = agentController::runAgent,
                            onPhoto = { photo -> openPhoto(photo.photoId, photo.uri) },
                        )

                        DockDestination.SETTINGS -> ModelSettingsScreen(isActive = requestedDockDestination == DockDestination.SETTINGS)
                    }
                }
            }
        }
        refreshLibraryState()
    }

    private fun handleStartupRequest() {
        when {
            isDebugRequest(DEBUG_ANNOTATE_SELECTED_ACTION, DEBUG_ANNOTATION_ALIAS) -> {
                intent.setAction(null)
                startAnnotation(AnnotationScope.SELECTED)
            }
            isDebugRequest(DEBUG_SEARCH_ACTION, DEBUG_SEARCH_ALIAS) -> {
                val query = intent.getStringExtra(DEBUG_SEARCH_QUERY).orEmpty().trim()
                intent.setAction(null)
                if (query.isEmpty() || !uiState.search.enabled) {
                    updateStatus("USB 验收搜索不可用：查询为空或尚无标注。")
                } else {
                    uiState = uiState.copy(query = query)
                    runSearch(query, acceptanceLog = true)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val navigation = Intent().putDockDestination(requestedDockDestination)
        selectedPhotoRange?.let(navigation::putPhotoHeatmapSelection)
        outState.putBundle("navigation", navigation.extras)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        selectedPhotoRange = intent.photoHeatmapSelection()
        requestedDockDestination = intent.dockDestination()
    }

    override fun onResume() {
        super.onResume()
        if (!firstResume && !busy.get()) refreshLibraryState()
        firstResume = false
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) selectionSession.closeSession()
        agentController.close()
        synchronized(annotationLifetime) {
            annotationDisposed = true
            executor.shutdownNow()
            annotationClient?.close()
            database.close()
        }
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
                publishLibraryState(
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
        if (scope == AnnotationScope.SELECTED && uiState.selectedCount == 0) {
            updateStatus("请先通过“选择照片”授权并选择要标注的照片。")
            return
        }
        val config = validatedInferenceConfig() ?: return
        val initialStatus = if (scope == AnnotationScope.ALL) "正在扫描全量已授权照片…" else "准备标注所选照片…"
        if (!beginWork(initialStatus)) return
        var task = AnnotationTaskProgress(scope.label, SystemClock.elapsedRealtime(), concurrency = config.concurrency, mergeCount = config.mergeCount)
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
                val client = synchronized(annotationLifetime) {
                    if (annotationDisposed || Thread.currentThread().isInterrupted) throw InterruptedException()
                    ApiVlmClient(contentResolver, config).also { annotationClient = it }
                }
                val promptVersion = if (config.mergeCount == 1) AnnotationContract.PROMPT_VERSION else BatchAnnotationContract.PROMPT_VERSION
                val scopeCount = if (scope == AnnotationScope.SELECTED) database.annotationSelectionCount() else database.accessibleCount()
                val pending = if (scope == AnnotationScope.SELECTED) {
                    database.pendingSelectedPhotos(config.modelName, BatchAnnotationContract.compatiblePromptVersions)
                } else database.pendingAllPhotos(config.modelName, BatchAnnotationContract.compatiblePromptVersions)
                var gallery = database.galleryPhotos()
                task = task.copy(total = pending.size, skipped = (scopeCount - pending.size).coerceAtLeast(0), phase = "标注中")
                    .log("待标注 ${pending.size} 张，跳过 ${scopeCount - pending.size} 张", SystemClock.elapsedRealtime())
                publishAnnotationTask(task, gallery)
                ParallelAnnotationRunner(config.concurrency).run(
                    items = pending.chunked(config.mergeCount),
                    onStart = { group ->
                        task = task.startBatch(group, SystemClock.elapsedRealtime())
                        publishAnnotationTask(task)
                    },
                    work = { group, onStage -> client.annotateGroup(group.map { Uri.parse(it.uri) }, onStage) },
                    onStage = { group, stage ->
                        val name = task.activePhotos[group.first().photoId]?.name.orEmpty()
                        task = task.log("$name · ${group.size} 张 · $stage", SystemClock.elapsedRealtime())
                        publishAnnotationTask(task)
                    },
                    onComplete = { group, result ->
                        synchronized(annotationLifetime) {
                            if (annotationDisposed || Thread.currentThread().isInterrupted) throw InterruptedException()
                            val batchId = group.first().photoId
                            try {
                                val annotations = result.getOrThrow()
                                check(annotations.size == group.size)
                                val byId = group.mapIndexed { index, photo -> photo.photoId to annotations[index] }.toMap()
                                task = task.log("写入本地标注 · ${group.size} 张", SystemClock.elapsedRealtime())
                                database.insertAnnotationGroup(byId.toList(), config.modelName, promptVersion)
                                val annotatedAt = Instant.now().toString()
                                gallery = gallery.map { card ->
                                    byId[card.photoId]?.let { annotation -> card.copy(annotation = annotation,
                                        modelName = config.modelName, promptVersion = promptVersion, annotatedAt = annotatedAt)
                                    } ?: card
                                }
                                runOnUiThread {
                                    if (!isDestroyed && !isFinishing) uiState = uiState.copy(results = uiState.results.map { card ->
                                        byId[card.photoId]?.let { annotation -> card.copy(annotation = annotation, caption = annotation.caption,
                                            modelName = config.modelName, promptVersion = promptVersion, annotatedAt = annotatedAt)
                                        } ?: card
                                    })
                                }
                                task = task.log("标注成功 · ${group.size} 张", SystemClock.elapsedRealtime())
                                    .finishBatch(batchId, true, SystemClock.elapsedRealtime())
                            } catch (error: Exception) {
                                if (error is InterruptedException || Thread.currentThread().isInterrupted) throw InterruptedException()
                                task = task.log("标注失败 · ${group.size} 张 · ${annotationFailureMessage(error)}", SystemClock.elapsedRealtime())
                                    .finishBatch(batchId, false, SystemClock.elapsedRealtime())
                            }
                            publishAnnotationTask(task, gallery)
                        }
                    },
                )
                task = task.log(if (pending.isEmpty()) "无需重复标注" else "任务结束", SystemClock.elapsedRealtime())
                    .finish(SystemClock.elapsedRealtime())
                publishAnnotationTask(task, gallery)
                val scanSuffix = scanReport?.let { " · 扫描可见 ${it.visible} 张 · 读取失败 ${it.errors.size}" }.orEmpty()
                publishLibraryState("${scope.label}范围 $scopeCount 张 · 成功 ${task.succeeded} · 失败 ${task.failed} · 跳过 ${task.skipped}$scanSuffix")
            } catch (error: Exception) {
                val message = annotationFailureMessage(error)
                task = task.log(message, SystemClock.elapsedRealtime()).finish(SystemClock.elapsedRealtime(), fatal = true)
                publishAnnotationTask(task)
                failWork("${scope.label}标注失败：$message")
            } finally {
                synchronized(annotationLifetime) {
                    annotationClient?.close()
                    annotationClient = null
                }
            }
        }
    }

    private fun submitSearch() {
        val query = uiState.query.trim()
        if (!uiState.search.enabled || query.isEmpty()) return
        runSearch(query)
    }

    private fun runSearch(query: String, acceptanceLog: Boolean = false) {
        if (!beginWork()) return
        uiState = uiState.copy(searchError = null)
        executor.execute {
            try {
                val cards = database.search(query)
                if (acceptanceLog) {
                    Log.i("APM.Acceptance", "search query=$query results=${cards.size}")
                }
                runOnUiThread {
                    busy.set(false)
                    if (!isDestroyed && !isFinishing) {
                        uiState = uiState.copy(
                            activeSearchQuery = query,
                            results = cards,
                            busy = false,
                        )
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    busy.set(false)
                    uiState = uiState.copy(busy = false, searchError = "搜索失败：${error.message}")
                }
            }
        }
    }

    private fun refreshLibraryState() {
        executor.execute {
            try {
                selectionSession.initialize(database)
                publishLibraryState()
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) {
                        val message = "读取本地索引失败：${error.message}"
                        uiState = uiState.copy(status = message, catalogError = message)
                        startup.onCatalogReady()
                    }
                }
            }
        }
    }

    // Called on the serial catalog worker. Search suggestions reuse the wall's decoded annotations.
    private fun publishLibraryState(completionMessage: String? = null) {
        val photos = database.galleryPhotos()
        val availability = SearchAvailability.fromGallery(photos)
        val selected = database.annotationSelectionCount()
        val query = uiState.activeSearchQuery?.takeIf { availability.enabled }
        val cards = query?.let(database::search).orEmpty()
        runOnUiThread {
            if (isDestroyed || isFinishing) return@runOnUiThread
            if (completionMessage != null) busy.set(false)
            val current = uiState
            val activeQuery = current.activeSearchQuery?.takeIf { availability.enabled }
            uiState = current.copy(
                query = if (availability.enabled) current.query else "",
                activeSearchQuery = activeQuery,
                results = if (activeQuery == query) cards else current.results,
                galleryPhotos = photos,
                search = availability,
                selectedCount = selected,
                busy = busy.get(),
                status = completionMessage ?: current.status,
                catalogError = null,
            )
            startup.onCatalogReady()
            handleStartupRequest()
        }
    }

    private fun beginWork(message: String? = null): Boolean {
        if (!busy.compareAndSet(false, true)) {
            updateStatus("已有任务正在运行，请稍候。")
            return false
        }
        runOnUiThread { uiState = uiState.copy(busy = true, status = message ?: uiState.status) }
        return true
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
internal fun GalleryScreen(
    state: GalleryUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSuggestion: (String) -> Unit,
    onHeatmap: () -> Unit,
    selectedRange: PhotoHeatmapSelection?,
    onClearRange: () -> Unit,
    onPhoto: (String, String) -> Unit,
    gridState: LazyGridState = rememberLazyGridState(),
    thumbnailLoader: PhotoThumbnailLoader = rememberPhotoThumbnailLoader(),
) {
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val photos = remember(state.galleryPhotos, selectedRange, zone) {
        if (selectedRange == null) state.galleryPhotos else state.galleryPhotos.filter { photo ->
            selectedRange.contains(photo.dateTakenMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() })
        }
    }
    val loadableKeys by remember(gridState) {
        derivedStateOf {
            if (gridState.isScrollInProgress) emptySet() else {
                val layout = gridState.layoutInfo
                layout.visibleItemsInfo.asSequence().filter {
                    it.offset.y < layout.viewportEndOffset && it.offset.y + it.size.height > layout.viewportStartOffset
                }.mapTo(mutableSetOf()) { it.key }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(
        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ).pointerInput(onHeatmap) {
        val edge = 32.dp.toPx()
        val threshold = 24.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.position.x <= edge) {
                do {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                        ?: break
                    val offset = change.position - down.position
                    if (!change.pressed && offset.x >= threshold && offset.x > abs(offset.y)) onHeatmap()
                } while (change.pressed)
            }
        }
    }) {
        val gap = 2.dp
        val requestSize = with(LocalDensity.current) {
            ((maxWidth - gap * (ALBUM_COLUMNS - 1)) / ALBUM_COLUMNS).roundToPx().coerceAtLeast(1)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(ALBUM_COLUMNS),
            state = gridState,
            modifier = Modifier.fillMaxSize().testTag("photo_wall_gesture_surface").semantics {
                contentDescription = "照片墙，从左边缘向右滑动打开年度照片热力图"
            },
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            item(key = "album_header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                Column(Modifier.fillMaxWidth()) {
                    if (state.catalogError == null || state.galleryPhotos.isNotEmpty()) {
                        Text(
                            "${state.galleryPhotos.size} 张照片",
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp)
                                .testTag("album_total"),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    if (selectedRange != null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("只看 ${heatmapSelectionLabel(selectedRange)} · ${selectedRange.count} 张照片",
                                Modifier.weight(1f).testTag("selected_photo_range_summary"))
                            TextButton(onClick = onClearRange, modifier = Modifier.testTag("clear_photo_range")) {
                                Text("显示全部时间")
                            }
                        }
                    }
                    state.catalogError?.let { error ->
                        Text(error, Modifier.padding(16.dp).testTag("album_read_error"))
                    }
                    photos.firstOrNull()?.let { latest ->
                        Text(
                            formatCaptureDate(latest.dateTakenMillis, zone),
                            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 6.dp)
                                .testTag("album_latest_date"),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            gridItems(photos, key = { it.photoId }) { photo ->
                PhotoWallThumbnail(
                    photo = photo,
                    requestSize = requestSize,
                    loader = thumbnailLoader,
                    canLoad = { photo.photoId in loadableKeys },
                    annotating = state.annotationTask?.activePhotoIds?.contains(photo.photoId) == true,
                    onClick = { onPhoto(photo.photoId, photo.uri) },
                )
            }
        }
        FloatingPhotoDate(gridState, photos, zone, Modifier.align(Alignment.TopStart))
        if (photos.isEmpty() && state.catalogError == null && selectedRange == null) {
            Box(Modifier.align(Alignment.Center).padding(horizontal = 48.dp)) {
                EmptyState(Icons.Outlined.PhotoLibrary, "相册里还没有照片", "在底部 Agent 中点“选择照片”，所选照片就会显示在这里。")
            }
        }
        FilledTonalIconButton(onClick = { searchOpen = true },
            modifier = Modifier.align(AbsoluteAlignment.CenterRight).padding(horizontal = 4.dp)
                .size(48.dp).testTag("open_album_search")) {
            Icon(Icons.Outlined.Search, contentDescription = "搜索照片")
        }
    }
    if (searchOpen) {
        ModalBottomSheet(
            onDismissRequest = { searchOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            val focus = LocalFocusManager.current
            LazyColumn(
                modifier = Modifier.fillMaxWidth().imePadding().testTag("album_search"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("搜索照片", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = { searchOpen = false }, modifier = Modifier.testTag("close_album_search")) {
                            Icon(Icons.Outlined.Close, contentDescription = "关闭搜索")
                        }
                    }
                    SearchPanel(state, onQueryChange, onSearch, onSuggestion)
                }
                if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (state.activeSearchQuery != null) {
                    item { SectionTitle("搜索结果", "${state.results.size} 张") }
                    if (state.results.isEmpty()) item {
                        Text("没有找到匹配照片，试试其他关键词。")
                    }
                    items(state.results, key = { it.photoId }) { photo ->
                        PhotoResultCard(
                            photo = photo,
                            annotating = state.annotationTask?.activePhotoIds?.contains(photo.photoId) == true,
                            loader = thumbnailLoader,
                        ) {
                            focus.clearFocus()
                            onPhoto(photo.photoId, photo.uri)
                        }
                    }
                }
                state.searchError?.let { message -> item { Text(message) } }
            }
        }
    }
}

@Composable
private fun FloatingPhotoDate(
    gridState: LazyGridState,
    photos: List<GalleryPhotoCard>,
    zone: ZoneId,
    modifier: Modifier,
) {
    val firstPhoto by remember(gridState, photos) {
        derivedStateOf {
            val layout = gridState.layoutInfo
            val first = layout.visibleItemsInfo.firstOrNull { it.offset.y + it.size.height > layout.viewportStartOffset }
            // The single full-span header is item zero, even in a filtered range.
            first?.let { item -> photos.getOrNull(item.index - 1)?.takeIf { it.photoId == item.key } }
        }
    }
    firstPhoto?.let { photo ->
        Text(
            remember(photo.dateTakenMillis, zone) { formatCaptureDate(photo.dateTakenMillis, zone) },
            modifier = modifier.padding(start = 8.dp, top = 4.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp).testTag("album_floating_date"),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SearchPanel(
    state: GalleryUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSuggestion: (String) -> Unit,
) {
    val focus = LocalFocusManager.current
    var tagsOpen by rememberSaveable { mutableStateOf(false) }
    val submit = {
        focus.clearFocus()
        onSearch()
    }
    Column(Modifier.fillMaxWidth()) {
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
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            focus.clearFocus()
                            tagsOpen = true
                        },
                        modifier = Modifier.testTag("all_tags_button"),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("全部标签") }
                    IconButton(
                        onClick = submit,
                        enabled = state.search.enabled && state.query.isNotBlank() && !state.busy,
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = "提交搜索")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
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
                        onClick = {
                            focus.clearFocus()
                            onSuggestion(suggestion)
                        },
                        enabled = !state.busy,
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
    if (tagsOpen) {
        AlbumTagDialog(
            photos = state.galleryPhotos,
            canSearch = state.search.enabled && !state.busy,
            onTag = { tag ->
                tagsOpen = false
                onSuggestion(tag)
            },
            onDismiss = { tagsOpen = false },
        )
    }
}


@Composable
internal fun PhotoWallThumbnail(
    photo: GalleryPhotoCard,
    annotating: Boolean = false,
    requestSize: Int = 180,
    loader: PhotoThumbnailLoader = rememberPhotoThumbnailLoader(),
    canLoad: () -> Boolean = { true },
    onClick: () -> Unit,
) {
    Thumbnail(
        key = PhotoThumbnailKey(photo.photoId, photo.uri, requestSize),
        loader = loader,
        canLoad = canLoad,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f)
            .testTag("photo_wall_thumbnail_${photo.photoId}"),
        details = photo.savedDetails(),
        annotating = annotating,
        imageDescription = photo.displayName,
        onPhotoClick = onClick,
    )
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
internal fun PhotoResultCard(
    photo: PhotoCard,
    annotating: Boolean = false,
    loader: PhotoThumbnailLoader = rememberPhotoThumbnailLoader(),
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().testTag("search_result_${photo.photoId}").clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val thumbnailSize = 104.dp
            Thumbnail(
                key = PhotoThumbnailKey(photo.photoId, photo.uri, with(LocalDensity.current) { thumbnailSize.roundToPx() }),
                loader = loader,
                modifier = Modifier.size(thumbnailSize).testTag("result_thumbnail_${photo.photoId}"),
                imageDescription = photo.displayName,
                details = photo.savedDetails(),
                annotating = annotating,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ResultField("标签", photo.annotation.tags.joinToString("、"))
                ResultField("分类", facetSummary(photo.annotation.facets))
            }
        }
    }
}

@Composable
private fun ResultField(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value.ifEmpty { "暂无" }, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Thumbnail(
    key: PhotoThumbnailKey,
    loader: PhotoThumbnailLoader,
    modifier: Modifier,
    canLoad: () -> Boolean = { true },
    imageDescription: String = "照片缩略图",
    details: StoredAnnotationDetails? = null,
    annotating: Boolean = false,
    onPhotoClick: (() -> Unit)? = null,
) {
    val compact = onPhotoClick != null
    var detailsOpen by remember(key.photoId) { mutableStateOf(false) }
    val bitmap = loader.rememberBitmap(key, canLoad)
    Box(
        modifier = modifier
            .then(if (compact) Modifier else Modifier.clip(MaterialTheme.shapes.medium))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null && !compact) {
            Icon(
                Icons.Outlined.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        } else if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = if (compact) null else imageDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Make the photo and badge sibling hit targets. A nested 48dp touch target
        // would otherwise claim the center of a compact tile.
        if (onPhotoClick != null) {
            Box(Modifier.matchParentSize().clickable(onClick = onPhotoClick).semantics {
                contentDescription = imageDescription
            })
        }
        if (annotating) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.BottomEnd).padding(if (compact) 2.dp else 6.dp).size(if (compact) 20.dp else 32.dp)
                    .background(Color.White, CircleShape).padding(3.dp)
                    .semantics { this.contentDescription = "正在标注" }
                    .testTag("photo_annotation_running"),
                color = Color(0xFF15803D), strokeWidth = 3.dp,
            )
        } else if (details != null) {
            IconButton(onClick = { detailsOpen = true }, modifier = Modifier.align(Alignment.BottomEnd)
                .padding(2.dp).size(if (compact) 26.dp else 40.dp).semantics { this.contentDescription = "已标注，查看详情" }
                .testTag("photo_annotation_badge")) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF15803D),
                    modifier = Modifier.size(if (compact) 20.dp else 32.dp).background(Color.White, CircleShape))
            }
        }
    }
    if (detailsOpen && details != null) PhotoAnnotationDetailsDialog(details) { detailsOpen = false }
}

private fun facetSummary(facets: PhotoFacets): String = buildList {
    fun category(label: String, values: List<String>) {
        if (values.isNotEmpty()) add("$label · ${values.joinToString("、")}")
    }
    category("天色", listOf(facets.daylight))
    category("天空", facets.sky)
    category("物体", facets.objects.map { "${it.name} ×${it.count}" })
    category("人物外观", facets.people.map { "${it.presentation} ×${it.count}" })
    category("动作", facets.actions)
    category("场景", facets.scenes)
    category("天气", facets.weather)
}.joinToString("\n")

private fun formatCaptureDate(millis: Long?, zone: ZoneId = ZoneId.systemDefault()): String {
    if (millis == null) return "拍摄时间未记录"
    return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
}
