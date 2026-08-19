package com.jinof.apm

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private enum class AnnotationScope(val label: String) {
    SELECTED("所选"),
    ALL("全量"),
}

private enum class PendingPhotoPermissionAction {
    ANNOTATE_ALL,
    CHECK_SIMILAR,
}

private enum class GalleryContentTab(val label: String) {
    PHOTOS("照片"),
    HEATMAP("热力图"),
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
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var uiState by mutableStateOf(GalleryUiState())
    private var initialized = false
    private var pendingPermissionAction = PendingPhotoPermissionAction.ANNOTATE_ALL
    private var pendingSimilarityCheck: SimilarityCheckRequest? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (hasFullPhotoAccess() || hasPartialPhotoAccess()) {
            when (pendingPermissionAction) {
                PendingPhotoPermissionAction.ANNOTATE_ALL -> startAnnotation(AnnotationScope.ALL)
                PendingPhotoPermissionAction.CHECK_SIMILAR -> {
                    val request = pendingSimilarityCheck
                    pendingSimilarityCheck = null
                    if (request == null) {
                        updateStatus("相似检查范围已失效，请重新选择。")
                    } else {
                        startSimilarityCheck(request)
                    }
                }
            }
        } else {
            if (pendingPermissionAction == PendingPhotoPermissionAction.CHECK_SIMILAR) {
                pendingSimilarityCheck = null
            }
            updateStatus("未获得照片读取权限。没有授权时不会扫描、标注或建立相似索引。")
        }
    }

    private val selectedPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        if (uris.isEmpty()) {
            updateStatus("没有选择新照片，当前所选范围保持不变。")
        } else {
            importSelectedPhotos(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        database = ApmDatabase(applicationContext)
        settingsStore = SettingsStore(applicationContext)
        scanner = MediaStoreScanner(applicationContext, database)
        initialized = true
        setContent {
            ApmTheme {
                GalleryScreen(
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
                    onCheckSimilar = ::onSimilarityCheckRequested,
                    onAgent = ::openAgent,
                    onIdentity = ::openIdentity,
                    onSettings = ::openSettings,
                    onPhoto = ::openPhoto,
                    onSimilar = ::openSimilar,
                )
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

    override fun onResume() {
        super.onResume()
        if (initialized && !busy.get()) refreshLibraryState(null)
    }

    override fun onDestroy() {
        executor.shutdownNow()
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
                pendingPermissionAction = PendingPhotoPermissionAction.ANNOTATE_ALL
                permissionLauncher.launch(requiredPhotoPermissions())
            }
        }
    }

    private fun onSimilarityCheckRequested(request: SimilarityCheckRequest) {
        val modelIssue = OnnxRuntimeDinoV2ImageEncoder.availability(applicationContext)
        if (modelIssue != null) {
            updateStatus(modelIssue)
            return
        }
        val action = PhotoAccessPolicy.nextScanAction(
            sdkInt = Build.VERSION.SDK_INT,
            hasFullAccess = hasFullPhotoAccess(),
            hasPartialAccess = hasPartialPhotoAccess(),
        )
        when (action) {
            PhotoAccessAction.SCAN_NOW -> startSimilarityCheck(request)
            PhotoAccessAction.REQUEST_INITIAL_ACCESS,
            PhotoAccessAction.REQUEST_RESELECTION,
            -> {
                pendingSimilarityCheck = request
                pendingPermissionAction = PendingPhotoPermissionAction.CHECK_SIMILAR
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
                database.replaceAnnotationSelection(result.photoIds)
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
            openSettings()
            return null
        }
        return config
    }

    private fun startAnnotation(scope: AnnotationScope) {
        if (scope == AnnotationScope.SELECTED && database.annotationSelectionCount() == 0) {
            updateStatus("请先通过“选择照片”授权并选择要标注的照片。")
            return
        }
        val config = validatedInferenceConfig() ?: return
        val initialStatus = if (scope == AnnotationScope.ALL) {
            "正在扫描全量已授权照片…"
        } else {
            "正在连接 ${config.modelName}，准备标注所选照片…"
        }
        if (!beginWork(initialStatus)) return
        executor.execute {
            try {
                val scanReport = if (scope == AnnotationScope.ALL) {
                    scanner.scan { processed, total, name ->
                        if (processed == total || processed % 10 == 0) {
                            updateStatus("全量扫描 $processed / $total · $name")
                        }
                    }
                } else {
                    null
                }
                val client = OllamaVlmClient(contentResolver, config)
                updateStatus("${scope.label}标注 · 正在连接 ${config.modelName}…")
                client.ensureAvailable()
                val faceReport = FaceIndexer(applicationContext, database).use { indexer ->
                    indexer.indexPending(
                        limit = null,
                        selectedOnly = scope == AnnotationScope.SELECTED,
                    ) { processed, total, name ->
                        updateStatus("${scope.label}标注 · 本地人脸识别 $processed / $total · $name")
                    }
                }
                val petReport = PetIndexer(applicationContext, database).use { indexer ->
                    indexer.indexPending(
                        limit = null,
                        selectedOnly = scope == AnnotationScope.SELECTED,
                    ) { processed, total, name ->
                        updateStatus("${scope.label}标注 · 本地宠物识别 $processed / $total · $name")
                    }
                }
                val scopeCount = if (scope == AnnotationScope.SELECTED) {
                    database.annotationSelectionCount()
                } else {
                    database.accessibleCount()
                }
                val pending = if (scope == AnnotationScope.SELECTED) {
                    database.pendingSelectedPhotos(config.modelName, AnnotationContract.PROMPT_VERSION)
                } else {
                    database.pendingAllPhotos(config.modelName, AnnotationContract.PROMPT_VERSION)
                }
                if (pending.isEmpty()) {
                    completeWork("${scope.label}范围 $scopeCount 张照片均已有当前模型标注。")
                    return@execute
                }
                val errors = mutableListOf<String>()
                var completed = 0
                pending.forEachIndexed { index, photo ->
                    updateStatus("${scope.label}标注 ${index + 1} / ${pending.size} · ${photo.displayName}")
                    try {
                        database.insertAnnotation(
                            photoId = photo.photoId,
                            annotation = client.annotate(
                                Uri.parse(photo.uri),
                                database.subjectMarkers(photo.photoId),
                            ),
                            modelName = config.modelName,
                            promptVersion = AnnotationContract.PROMPT_VERSION,
                        )
                        completed += 1
                    } catch (error: Exception) {
                        Log.e(
                            ANNOTATION_LOG_TAG,
                            "${scope.label} photo ${index + 1}/${pending.size} failed",
                            error,
                        )
                        errors += "${photo.displayName}：${error.message ?: error.javaClass.simpleName}"
                    }
                }
                val details = if (errors.isEmpty()) "" else " · 失败 ${errors.size} · ${errors.first()}"
                val scanSuffix = scanReport?.let {
                    " · 扫描可见 ${it.visible} 张${if (it.errors.isEmpty()) "" else "、读取失败 ${it.errors.size}"}"
                }.orEmpty()
                val faceSuffix = " · 本地人脸 ${faceReport.faces} 张、匹配 ${faceReport.matchedFaces} 张"
                val petSuffix = " · 本地宠物 ${petReport.pets} 只、匹配 ${petReport.matchedPets} 只"
                completeWork(
                    "${scope.label}范围 $scopeCount 张 · 待处理 ${pending.size} 张 · 成功标注 $completed$scanSuffix$faceSuffix$petSuffix$details",
                )
            } catch (error: Exception) {
                Log.e(ANNOTATION_LOG_TAG, "${scope.label} annotation pipeline failed", error)
                failWork("${scope.label}标注失败：${error.message}")
            }
        }
    }

    private fun startSimilarityCheck(request: SimilarityCheckRequest) {
        val modelIssue = OnnxRuntimeDinoV2ImageEncoder.availability(applicationContext)
        if (modelIssue != null) {
            updateStatus(modelIssue)
            return
        }
        if (!beginWork("${request.displayName} · 正在扫描已授权照片…")) return
        executor.execute {
            try {
                val scanReport = scanner.scan { processed, total, name ->
                    if (processed == total || processed % 10 == 0) {
                        updateStatus("${request.displayName} · 扫描 $processed / $total · $name")
                    }
                }
                val report = VisualSimilarityIndexer(applicationContext, database).use { indexer ->
                    indexer.check(request) { processed, total, name ->
                        updateStatus("${request.displayName} · DINOv2 $processed / $total · $name")
                    }
                }
                val firstError = (scanReport.errors + report.errors).firstOrNull()?.let { " · $it" }.orEmpty()
                val noCaptureTime = if (report.excludedWithoutCaptureTime == 0) {
                    ""
                } else {
                    " · 无拍摄时间排除 ${report.excludedWithoutCaptureTime} 张"
                }
                completeWork(
                    "${request.displayName}完成 · 范围 ${report.consideredPhotos} 张 · 成功 ${report.indexedPhotos} 张" +
                        " · 通用主体 ${report.generalSubjects} 个 · 扫描可见 ${scanReport.visible} 张" +
                        "$noCaptureTime · 扫描失败 ${scanReport.errors.size} · 特征失败 ${report.errors.size}$firstError",
                )
            } catch (error: Exception) {
                Log.e("APM.Similarity", "${request.displayName} failed", error)
                failWork("${request.displayName}失败：${error.message}")
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
                val identities = database.identityCount()
                val visualIndexed = database.visualFeatureCount(SimilarityScorer.PIPELINE_VERSION)
                val visualModelIssue = OnnxRuntimeDinoV2ImageEncoder.availability(applicationContext)
                runOnUiThread {
                    busy.set(false)
                    uiState = uiState.copy(
                        activeSearchQuery = query,
                        results = cards,
                        galleryPhotos = galleryPhotos,
                        search = availability,
                        accessibleCount = accessible,
                        selectedCount = selected,
                        identityCount = identities,
                        visualIndexedCount = visualIndexed,
                        visualModelIssue = visualModelIssue,
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
                val identities = database.identityCount()
                val visualIndexed = database.visualFeatureCount(SimilarityScorer.PIPELINE_VERSION)
                val visualModelIssue = OnnxRuntimeDinoV2ImageEncoder.availability(applicationContext)
                runOnUiThread {
                    uiState = uiState.copy(
                        query = query,
                        activeSearchQuery = activeQuery,
                        results = cards,
                        galleryPhotos = galleryPhotos,
                        search = availability,
                        accessibleCount = accessible,
                        selectedCount = selected,
                        identityCount = identities,
                        visualIndexedCount = visualIndexed,
                        visualModelIssue = visualModelIssue,
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
        val identities = database.identityCount()
        val visualIndexed = database.visualFeatureCount(SimilarityScorer.PIPELINE_VERSION)
        val visualModelIssue = OnnxRuntimeDinoV2ImageEncoder.availability(applicationContext)
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
                identityCount = identities,
                visualIndexedCount = visualIndexed,
                visualModelIssue = visualModelIssue,
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

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun openAgent() {
        startActivity(Intent(this, AgentSearchActivity::class.java))
    }

    private fun openIdentity() {
        startActivity(Intent(this, IdentityActivity::class.java))
    }

    private fun openPhoto(photoId: String, uri: String) {
        startActivity(PhotoViewerActivity.intent(this, photoId, uri))
    }

    private fun openSimilar(photoId: String, uri: String, displayName: String) {
        startActivity(
            Intent(this, SimilarityActivity::class.java)
                .putExtra(SimilarityActivity.EXTRA_PHOTO_ID, photoId)
                .putExtra(SimilarityActivity.EXTRA_URI, uri)
                .putExtra(SimilarityActivity.EXTRA_DISPLAY_NAME, displayName),
        )
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
    onCheckSimilar: (SimilarityCheckRequest) -> Unit,
    onAgent: () -> Unit,
    onIdentity: () -> Unit,
    onSettings: () -> Unit,
    onPhoto: (String, String) -> Unit,
    onSimilar: (String, String, String) -> Unit,
) {
    val zoneId = ZoneId.systemDefault()
    val initialYear = remember(state.galleryPhotos, zoneId) {
        PhotoWallOrganizer.initialYear(state.galleryPhotos, zoneId)
    }
    var contentTab by remember { mutableStateOf(GalleryContentTab.PHOTOS) }
    var displayMode by remember { mutableStateOf(PhotoWallDisplayMode.THUMBNAILS) }
    var displayedYear by remember(initialYear) { mutableStateOf(initialYear) }
    var heatmapGranularity by remember { mutableStateOf(PhotoHeatmapGranularity.DAY) }
    var selectedRange by remember(state.galleryPhotos) {
        mutableStateOf<PhotoHeatmapSelection?>(null)
    }
    val dayGroups = remember(state.galleryPhotos, selectedRange, zoneId) {
        PhotoWallOrganizer.groupByDay(state.galleryPhotos, zoneId)
            .let { groups ->
                selectedRange?.let { range -> groups.filter { range.contains(it.date) } } ?: groups
            }
    }
    val heatmap = remember(displayedYear, state.galleryPhotos, zoneId) {
        PhotoWallOrganizer.heatmap(displayedYear, state.galleryPhotos, zoneId)
    }
    val searchActive = state.activeSearchQuery != null

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
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
                actions = {
                    IconButton(
                        onClick = onIdentity,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("open_identity"),
                    ) {
                        Icon(Icons.Outlined.Face, contentDescription = "管理本地人物与宠物识别")
                    }
                    IconButton(
                        onClick = onAgent,
                        enabled = state.search.enabled && !state.busy,
                        modifier = Modifier.testTag("open_agent"),
                    ) {
                        Icon(Icons.Outlined.SmartToy, contentDescription = "打开 Agent 搜索")
                    }
                    IconButton(
                        onClick = onSettings,
                        modifier = Modifier.testTag("open_settings"),
                    ) {
                        Icon(Icons.Outlined.Settings, contentDescription = "打开模型设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { scaffoldPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
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
                HeroCard(state)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                AnnotationActions(
                    busy = state.busy,
                    selectedCount = state.selectedCount,
                    onSelectPhotos = onSelectPhotos,
                    onAnnotateSelected = onAnnotateSelected,
                    onAnnotateAll = onAnnotateAll,
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                VisualSimilarityActions(
                    state = state,
                    onCheckSimilar = onCheckSimilar,
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
                            onClick = { onPhoto(photo.photoId, photo.uri) },
                            onSimilar = {
                                onSimilar(photo.photoId, photo.uri, photo.displayName)
                            },
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
                    GalleryContentTabs(
                        selectedTab = contentTab,
                        onSelectTab = { contentTab = it },
                    )
                }
                when (contentTab) {
                    GalleryContentTab.PHOTOS -> {
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
                                        onClick = { onPhoto(photo.photoId, photo.uri) },
                                        onSimilar = {
                                            onSimilar(photo.photoId, photo.uri, photo.displayName)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    GalleryContentTab.HEATMAP -> {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            HeatmapTabContent(
                                heatmap = heatmap,
                                heatmapGranularity = heatmapGranularity,
                                selectedRange = selectedRange,
                                onHeatmapGranularity = { granularity ->
                                    heatmapGranularity = granularity
                                    selectedRange = null
                                },
                                onPreviousYear = {
                                    displayedYear -= 1
                                    selectedRange = null
                                },
                                onNextYear = {
                                    displayedYear += 1
                                    selectedRange = null
                                },
                                onSelectRange = { selectedRange = it },
                                onClearRange = { selectedRange = null },
                                onViewPhotos = { contentTab = GalleryContentTab.PHOTOS },
                            )
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                PrivacyFooter()
            }
        }
    }
}

@Composable
private fun HeroCard(state: GalleryUiState) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(24.dp)) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primary,
            ) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(12.dp).size(28.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "让照片，自己说出故事",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "VLM 在授权范围内理解画面，描述与标签只存放在你的设备。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
            )
            Spacer(Modifier.height(20.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MetricPill("${state.accessibleCount}", "可访问") }
                item { MetricPill("${state.search.annotationCount}", "已标注") }
                item { MetricPill("${state.identityCount}", "身份") }
                item { MetricPill("${state.visualIndexedCount}", "相似索引") }
            }
        }
    }
}

@Composable
private fun VisualSimilarityActions(
    state: GalleryUiState,
    onCheckSimilar: (SimilarityCheckRequest) -> Unit,
) {
    var showCheckDialog by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionTitle("相似照片", "DINOv2 · 本机")
        Text(
            "检查新增、近期或全部已授权照片，识别连拍、同场景、构图相似和主体相似。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(
            onClick = { showCheckDialog = true },
            enabled = !state.busy && state.visualModelIssue == null,
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("check_similar"),
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(Icons.Outlined.ImageSearch, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("检查相似")
        }
        if (state.visualModelIssue != null) {
            Text(
                state.visualModelIssue,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("similarity_model_issue"),
            )
        }
    }
    if (showCheckDialog) {
        SimilarityCheckDialog(
            onDismiss = { showCheckDialog = false },
            onConfirm = { request ->
                showCheckDialog = false
                onCheckSimilar(request)
            },
        )
    }
}

@Composable
private fun SimilarityCheckDialog(
    onDismiss: () -> Unit,
    onConfirm: (SimilarityCheckRequest) -> Unit,
) {
    var mode by remember { mutableStateOf(SimilarityCheckMode.INCREMENTAL) }
    var recentAmountText by remember { mutableStateOf("7") }
    var recentUnit by remember { mutableStateOf(SimilarityRecentUnit.DAYS) }
    val recentAmount = recentAmountText.toIntOrNull()
    val recentValid = recentAmount != null &&
        recentAmount in SimilarityCheckRequest.MIN_RECENT_AMOUNT..SimilarityCheckRequest.MAX_RECENT_AMOUNT

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("similarity_check_dialog"),
        title = { Text("检查相似") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "选择本次检查范围。照片只在本机生成 DINOv2 特征。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SimilarityCheckMode.entries.forEach { choice ->
                        FilterChip(
                            selected = mode == choice,
                            onClick = { mode = choice },
                            label = { Text(choice.displayName) },
                            modifier = Modifier.testTag("similarity_mode_${choice.name.lowercase()}"),
                        )
                    }
                }
                Text(
                    when (mode) {
                        SimilarityCheckMode.INCREMENTAL ->
                            "只处理新增或尚未完成当前 DINOv2 检查的照片。"
                        SimilarityCheckMode.RECENT ->
                            "重新检查真实拍摄时间落在所选范围内的照片。"
                        SimilarityCheckMode.FULL ->
                            "重新检查当前所有已授权照片，耗时可能较长。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (mode == SimilarityCheckMode.RECENT) {
                    OutlinedTextField(
                        value = recentAmountText,
                        onValueChange = { value ->
                            if (value.all(Char::isDigit) && value.length <= 4) recentAmountText = value
                        },
                        modifier = Modifier.fillMaxWidth().testTag("similarity_recent_amount"),
                        label = { Text("最近多少时间") },
                        singleLine = true,
                        isError = recentAmountText.isNotEmpty() && !recentValid,
                        supportingText = if (!recentValid) {
                            { Text("请输入 1–9999") }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SimilarityRecentUnit.entries.forEach { unit ->
                            FilterChip(
                                selected = recentUnit == unit,
                                onClick = { recentUnit = unit },
                                label = { Text(unit.displayName) },
                                modifier = Modifier.testTag("similarity_unit_${unit.name.lowercase()}"),
                            )
                        }
                    }
                    Text(
                        "没有拍摄时间的照片会被排除并计数，不会用修改时间猜测。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val requestedAtMillis = System.currentTimeMillis()
                    val request = when (mode) {
                        SimilarityCheckMode.INCREMENTAL ->
                            SimilarityCheckRequest.incremental(requestedAtMillis)
                        SimilarityCheckMode.RECENT ->
                            SimilarityCheckRequest.recent(
                                amount = requireNotNull(recentAmount),
                                unit = recentUnit,
                                requestedAtMillis = requestedAtMillis,
                            )
                        SimilarityCheckMode.FULL -> SimilarityCheckRequest.full(requestedAtMillis)
                    }
                    onConfirm(request)
                },
                enabled = mode != SimilarityCheckMode.RECENT || recentValid,
                modifier = Modifier.testTag("confirm_similarity_check"),
            ) {
                Text("开始检查")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun MetricPill(value: String, label: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AnnotationActions(
    busy: Boolean,
    selectedCount: Int,
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onAnnotateSelected,
                enabled = !busy && selectedCount > 0,
                modifier = Modifier.weight(1f).height(52.dp).testTag("annotate_selected"),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("标注所选 ($selectedCount)")
            }
            FilledTonalButton(
                onClick = onAnnotateAll,
                enabled = !busy,
                modifier = Modifier.weight(1f).height(52.dp).testTag("annotate_all"),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("全量标注")
            }
        }
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
private fun GalleryContentTabs(
    selectedTab: GalleryContentTab,
    onSelectTab: (GalleryContentTab) -> Unit,
) {
    TabRow(
        selectedTabIndex = selectedTab.ordinal,
        modifier = Modifier.fillMaxWidth().testTag("gallery_content_tabs"),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        GalleryContentTab.entries.forEach { tab ->
            Tab(
                selected = selectedTab == tab,
                onClick = { onSelectTab(tab) },
                text = { Text(tab.label) },
                modifier = Modifier.testTag("gallery_tab_${tab.name.lowercase()}"),
            )
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
private fun HeatmapTabContent(
    heatmap: PhotoHeatmapYear,
    heatmapGranularity: PhotoHeatmapGranularity,
    selectedRange: PhotoHeatmapSelection?,
    onHeatmapGranularity: (PhotoHeatmapGranularity) -> Unit,
    onPreviousYear: () -> Unit,
    onNextYear: () -> Unit,
    onSelectRange: (PhotoHeatmapSelection) -> Unit,
    onClearRange: () -> Unit,
    onViewPhotos: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("heatmap_tab_content"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("照片热力图", "年度 · ${heatmapGranularity.label}")
        AnnualPhotoCountHeatmap(
            heatmap = heatmap,
            granularity = heatmapGranularity,
            selectedRange = selectedRange,
            onGranularity = onHeatmapGranularity,
            onPreviousYear = onPreviousYear,
            onNextYear = onNextYear,
            onSelectRange = onSelectRange,
        )
        if (selectedRange != null) {
            Text(
                "已选 ${heatmapSelectionLabel(selectedRange)} · ${selectedRange.count} 张照片",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("selected_photo_range_summary"),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onClearRange,
                    modifier = Modifier.testTag("clear_photo_range"),
                ) {
                    Text("清除选择")
                }
                FilledTonalButton(
                    onClick = onViewPhotos,
                    modifier = Modifier.testTag("view_selected_photos"),
                ) {
                    Text("去照片中查看")
                }
            }
        }
    }
}

@Composable
private fun AnnualPhotoCountHeatmap(
    heatmap: PhotoHeatmapYear,
    granularity: PhotoHeatmapGranularity,
    selectedRange: PhotoHeatmapSelection?,
    onGranularity: (PhotoHeatmapGranularity) -> Unit,
    onPreviousYear: () -> Unit,
    onNextYear: () -> Unit,
    onSelectRange: (PhotoHeatmapSelection) -> Unit,
) {
    val today = remember { LocalDate.now() }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().testTag("photo_wall_heatmap"),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onPreviousYear, modifier = Modifier.testTag("heatmap_previous_year")) {
                    Icon(Icons.Outlined.ChevronLeft, contentDescription = "上一年")
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${heatmap.year}年",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("heatmap_year_label"),
                    )
                    Text(
                        "全年 ${heatmap.totalCount} 张 · ${heatmapUnitLabel(granularity)}最多 ${heatmap.maxCount(granularity)} 张",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onNextYear, modifier = Modifier.testTag("heatmap_next_year")) {
                    Icon(Icons.Outlined.ChevronRight, contentDescription = "下一年")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                PhotoHeatmapGranularity.entries.forEach { option ->
                    FilterChip(
                        selected = granularity == option,
                        onClick = { onGranularity(option) },
                        label = { Text(option.label) },
                        modifier = Modifier.testTag("heatmap_granularity_${option.name.lowercase()}"),
                    )
                }
            }
            Text(
                "时间从上到下排列",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            when (granularity) {
                PhotoHeatmapGranularity.DAY -> GitHubAnnualDayHeatmap(
                    heatmap = heatmap,
                    selectedRange = selectedRange,
                    today = today,
                    onSelectRange = onSelectRange,
                )
                PhotoHeatmapGranularity.WEEK -> GitHubAnnualWeekHeatmap(
                    heatmap = heatmap,
                    selectedRange = selectedRange,
                    onSelectRange = onSelectRange,
                )
                PhotoHeatmapGranularity.MONTH -> GitHubAnnualMonthHeatmap(
                    heatmap = heatmap,
                    selectedRange = selectedRange,
                    onSelectRange = onSelectRange,
                )
            }
            GitHubHeatmapGuide()
        }
    }
}

@Composable
private fun GitHubAnnualDayHeatmap(
    heatmap: PhotoHeatmapYear,
    selectedRange: PhotoHeatmapSelection?,
    today: LocalDate,
    onSelectRange: (PhotoHeatmapSelection) -> Unit,
) {
    val cellSize = 18.dp
    val gap = 4.dp
    val monthByWeek = heatmap.monthLabels.associateBy(PhotoHeatmapMonthLabel::startWeekIndex)
    Column(
        modifier = Modifier.fillMaxWidth().testTag("heatmap_day_vertical"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            Spacer(Modifier.width(32.dp))
            listOf("日", "一", "二", "三", "四", "五", "六").forEach { weekday ->
                Box(
                    modifier = Modifier.size(cellSize),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        weekday,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        heatmap.dayCells.chunked(7).forEachIndexed { weekIndex, week ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.width(32.dp).height(cellSize),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    monthByWeek[weekIndex]?.let { label ->
                        Text(
                            "${label.month.monthValue}月",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                week.forEach { cell ->
                    val selected = selectedRange?.let { selection ->
                        selection.granularity == PhotoHeatmapGranularity.DAY &&
                            selection.startDate == cell.date
                    } == true
                    val isToday = cell.date == today
                    var modifier = Modifier.size(cellSize)
                    if (cell.inYear) {
                        val description = buildString {
                            append(cell.date.format(DateTimeFormatter.ofPattern("yyyy年M月d日")))
                            append("，${cell.count}张照片，热度${cell.level}级")
                            if (isToday) append("，今天")
                            if (selected) append("，已选择")
                        }
                        modifier = modifier
                            .testTag("heatmap_day_${cell.date}")
                            .then(
                                if (cell.count > 0) {
                                    Modifier.clickable {
                                        onSelectRange(
                                            PhotoHeatmapSelection(
                                                granularity = PhotoHeatmapGranularity.DAY,
                                                startDate = cell.date,
                                                endDate = cell.date,
                                                count = cell.count,
                                            ),
                                        )
                                    }
                                } else {
                                    Modifier
                                },
                            )
                            .semantics {
                                contentDescription = description
                                this.selected = selected
                            }
                    }
                    Surface(
                        modifier = modifier,
                        color = if (cell.inYear) githubHeatmapColor(cell.level) else Color.Transparent,
                        border = if (cell.inYear) githubHeatmapBorder(selected, isToday) else null,
                        shape = RoundedCornerShape(3.dp),
                    ) {}
                }
            }
        }
    }
}

@Composable
private fun GitHubAnnualWeekHeatmap(
    heatmap: PhotoHeatmapYear,
    selectedRange: PhotoHeatmapSelection?,
    onSelectRange: (PhotoHeatmapSelection) -> Unit,
) {
    val cellSize = 18.dp
    val monthByWeek = heatmap.monthLabels.associateBy(PhotoHeatmapMonthLabel::startWeekIndex)
    Column(
        modifier = Modifier.fillMaxWidth().testTag("heatmap_week_vertical"),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        heatmap.weekCells.forEachIndexed { weekIndex, cell ->
            val selected = selectedRange?.let { selection ->
                selection.granularity == PhotoHeatmapGranularity.WEEK &&
                    selection.startDate == cell.startDate &&
                    selection.endDate == cell.endDate
            } == true
            val description = buildString {
                append(heatmapRangeLabel(cell.startDate, cell.endDate))
                append("，${cell.count}张照片，热度${cell.level}级")
                if (selected) append("，已选择")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.width(32.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    monthByWeek[weekIndex]?.let { label ->
                        Text(
                            "${label.month.monthValue}月",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Surface(
                    modifier = Modifier
                        .size(cellSize)
                        .testTag("heatmap_week_${cell.startDate}")
                        .then(
                            if (cell.count > 0) {
                                Modifier.clickable {
                                    onSelectRange(
                                        PhotoHeatmapSelection(
                                            granularity = PhotoHeatmapGranularity.WEEK,
                                            startDate = cell.startDate,
                                            endDate = cell.endDate,
                                            count = cell.count,
                                        ),
                                    )
                                }
                            } else {
                                Modifier
                            },
                        )
                        .semantics {
                            contentDescription = description
                            this.selected = selected
                        },
                    color = githubHeatmapColor(cell.level),
                    border = githubHeatmapBorder(selected, false),
                    shape = RoundedCornerShape(3.dp),
                ) {}
                Spacer(Modifier.width(8.dp))
                Text(
                    heatmapRangeLabel(cell.startDate, cell.endDate),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${cell.count} 张",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GitHubAnnualMonthHeatmap(
    heatmap: PhotoHeatmapYear,
    selectedRange: PhotoHeatmapSelection?,
    onSelectRange: (PhotoHeatmapSelection) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("heatmap_month_vertical"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        heatmap.monthCells.forEachIndexed { index, cell ->
            val selected = selectedRange?.let { selection ->
                selection.granularity == PhotoHeatmapGranularity.MONTH &&
                    selection.startDate == cell.startDate
            } == true
            val description = buildString {
                append(cell.startDate.format(DateTimeFormatter.ofPattern("yyyy年M月")))
                append("，${cell.count}张照片，热度${cell.level}级")
                if (selected) append("，已选择")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${index + 1}月",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(40.dp),
                )
                Surface(
                    modifier = Modifier
                        .size(22.dp)
                        .testTag("heatmap_month_${(index + 1).toString().padStart(2, '0')}")
                        .then(
                            if (cell.count > 0) {
                                Modifier.clickable {
                                    onSelectRange(
                                        PhotoHeatmapSelection(
                                            granularity = PhotoHeatmapGranularity.MONTH,
                                            startDate = cell.startDate,
                                            endDate = cell.endDate,
                                            count = cell.count,
                                        ),
                                    )
                                }
                            } else {
                                Modifier
                            },
                        )
                        .semantics {
                            contentDescription = description
                            this.selected = selected
                        },
                    color = githubHeatmapColor(cell.level),
                    border = githubHeatmapBorder(selected, false),
                    shape = RoundedCornerShape(3.dp),
                ) {}
                Spacer(Modifier.width(10.dp))
                Text(
                    heatmapSelectionLabel(
                        PhotoHeatmapSelection(
                            granularity = PhotoHeatmapGranularity.MONTH,
                            startDate = cell.startDate,
                            endDate = cell.endDate,
                            count = cell.count,
                        ),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${cell.count} 张",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GitHubHeatmapGuide() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, end = 20.dp, bottom = 4.dp)
            .testTag("heatmap_guide"),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("少", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(5.dp))
        (0..PhotoWallOrganizer.MAX_HEAT_LEVEL).forEach { level ->
            Surface(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(11.dp)
                    .semantics {
                        contentDescription = if (level == 0) "热度0级，无照片" else "热度${level}级"
                    }
                    .testTag("heatmap_guide_level_$level"),
                color = githubHeatmapColor(level),
                border = githubHeatmapBorder(false, false),
                shape = RoundedCornerShape(2.dp),
            ) {}
        }
        Spacer(Modifier.width(5.dp))
        Text("多", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun githubHeatmapColor(level: Int): Color {
    val dark = isSystemInDarkTheme()
    return if (dark) {
        when (level) {
            0 -> Color(0xFF161B22)
            1 -> Color(0xFF0E4429)
            2 -> Color(0xFF006D32)
            3 -> Color(0xFF26A641)
            else -> Color(0xFF39D353)
        }
    } else {
        when (level) {
            0 -> Color(0xFFEBEDF0)
            1 -> Color(0xFF9BE9A8)
            2 -> Color(0xFF40C463)
            3 -> Color(0xFF30A14E)
            else -> Color(0xFF216E39)
        }
    }
}

@Composable
private fun githubHeatmapBorder(selected: Boolean, today: Boolean): BorderStroke = when {
    selected -> BorderStroke(
        2.dp,
        if (isSystemInDarkTheme()) Color(0xFF58A6FF) else Color(0xFF0969DA),
    )
    today -> BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface)
    isSystemInDarkTheme() -> BorderStroke(1.dp, Color(0x1AFFFFFF))
    else -> BorderStroke(1.dp, Color(0x0F1B1F23))
}

private fun heatmapUnitLabel(granularity: PhotoHeatmapGranularity): String = when (granularity) {
    PhotoHeatmapGranularity.DAY -> "单日"
    PhotoHeatmapGranularity.WEEK -> "单周"
    PhotoHeatmapGranularity.MONTH -> "单月"
}

private fun heatmapSelectionLabel(selection: PhotoHeatmapSelection): String = when (selection.granularity) {
    PhotoHeatmapGranularity.DAY ->
        selection.startDate.format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA))
    PhotoHeatmapGranularity.WEEK -> heatmapRangeLabel(selection.startDate, selection.endDate)
    PhotoHeatmapGranularity.MONTH ->
        selection.startDate.format(DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINA))
}

private fun heatmapRangeLabel(startDate: LocalDate, endDate: LocalDate): String = when {
    startDate == endDate -> startDate.format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA))
    startDate.year == endDate.year && startDate.month == endDate.month ->
        "${startDate.format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA))}–${endDate.dayOfMonth}日"
    startDate.year == endDate.year ->
        "${startDate.format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA))}–${endDate.format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA))}"
    else ->
        "${startDate.format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA))}–${endDate.format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA))}"
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
private fun PhotoWallThumbnail(photo: GalleryPhotoCard, onClick: () -> Unit) {
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
        )
    }
}

@Composable
private fun PhotoWallDetailCard(
    photo: GalleryPhotoCard,
    onClick: () -> Unit,
    onSimilar: () -> Unit,
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
            Thumbnail(photo.uri, contentDescription = photo.displayName)
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
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = onSimilar,
                    modifier = Modifier.testTag("wall_similar_${photo.photoId}"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Outlined.ImageSearch, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("查看相似照片")
                }
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
private fun PhotoResultCard(photo: PhotoCard, onClick: () -> Unit, onSimilar: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.padding(12.dp)) {
            Thumbnail(photo.uri, contentDescription = photo.displayName)
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
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = onSimilar,
                    modifier = Modifier.testTag("similar_${photo.photoId}"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        Icons.Outlined.ImageSearch,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("查看相似照片")
                }
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
) {
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
    }
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
