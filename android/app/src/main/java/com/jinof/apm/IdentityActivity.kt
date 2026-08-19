package com.jinof.apm

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private data class ReferenceSubject(
    val box: FaceBox,
    val face: FaceSample? = null,
    val pet: PetSample? = null,
) {
    init {
        require((face == null) != (pet == null))
    }
}

private data class IdentityUiState(
    val kind: LocalIdentityKind = LocalIdentityKind.PERSON,
    val uri: String? = null,
    val bitmap: Bitmap? = null,
    val subjects: List<ReferenceSubject> = emptyList(),
    val selectedSubject: Int? = null,
    val name: String = "",
    val identities: List<LocalIdentitySummary> = emptyList(),
    val busy: Boolean = false,
    val status: String = "选择身份类型和一张清晰参考照片，然后点选要命名的主体。",
)

class IdentityActivity : ComponentActivity() {
    private lateinit var database: ApmDatabase
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var faceEngine: LocalFaceEngine? = null
    private var petEngine: LocalPetEngine? = null
    private var state by mutableStateOf(IdentityUiState())

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) analyzeReference(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        database = ApmDatabase(applicationContext)
        setContent {
            ApmTheme {
                IdentityScreen(
                    state = state,
                    onBack = { finish() },
                    onKindChange = ::changeKind,
                    onPick = ::pickReference,
                    onSelectSubject = { index -> state = state.copy(selectedSubject = index) },
                    onNameChange = { value -> state = state.copy(name = value.take(40)) },
                    onRegister = ::registerSelectedSubject,
                    onDelete = ::deleteIdentity,
                )
            }
        }
        refreshIdentities()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        faceEngine?.close()
        petEngine?.close()
        state.bitmap?.recycle()
        database.close()
        super.onDestroy()
    }

    private fun changeKind(kind: LocalIdentityKind) {
        if (busy.get() || state.kind == kind) return
        state.bitmap?.recycle()
        state = IdentityUiState(
            kind = kind,
            identities = state.identities,
            status = if (kind == LocalIdentityKind.PERSON) {
                "选择一张清晰参考照片，然后点选要命名的人脸。"
            } else {
                "当前本地宠物检测支持猫和狗。选择参考照片后点选要命名的宠物。"
            },
        )
    }

    private fun pickReference() {
        if (busy.get()) return
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun analyzeReference(uri: Uri) {
        if (!busy.compareAndSet(false, true)) return
        val kind = state.kind
        state = state.copy(
            busy = true,
            status = if (kind == LocalIdentityKind.PERSON) {
                "正在手机本地检测并提取人脸特征…"
            } else {
                "正在手机本地检测猫狗并提取视觉特征…"
            },
        )
        executor.execute {
            try {
                val bitmap: Bitmap
                val subjects: List<ReferenceSubject>
                if (kind == LocalIdentityKind.PERSON) {
                    bitmap = faceEngine().loadBitmap(uri)
                    subjects = faceEngine().analyze(bitmap).map { ReferenceSubject(it.box, face = it) }
                } else {
                    bitmap = petEngine().loadBitmap(uri)
                    subjects = petEngine().analyze(bitmap).map { ReferenceSubject(it.box, pet = it) }
                }
                runOnUiThread {
                    state.bitmap?.recycle()
                    busy.set(false)
                    state = state.copy(
                        uri = uri.toString(),
                        bitmap = bitmap,
                        subjects = subjects,
                        selectedSubject = null,
                        busy = false,
                        status = when {
                            subjects.isEmpty() && kind == LocalIdentityKind.PERSON ->
                                "多尺度检测后仍没有找到五官完整的清晰人脸。"
                            subjects.isEmpty() ->
                                "没有检测到清晰的猫或狗；当前宠物身份流水线暂不支持其他物种。"
                            subjects.size == 1 ->
                                "检测到 1 个主体，请点击框选区域确认，再输入名字保存。"
                            else ->
                                "检测到 ${subjects.size} 个主体，请点选其中一个后输入名字。"
                        },
                    )
                }
            } catch (error: Exception) {
                finishWork("参考照片处理失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun registerSelectedSubject() {
        val subject = state.selectedSubject?.let(state.subjects::getOrNull) ?: return
        val name = state.name.trim()
        val uriText = state.uri ?: return
        val kind = state.kind
        if (name.isEmpty() || !busy.compareAndSet(false, true)) return
        state = state.copy(busy = true, status = "正在保存本地 embedding 并重新匹配已扫描照片…")
        executor.execute {
            try {
                val status = if (kind == LocalIdentityKind.PERSON) {
                    val sample = requireNotNull(subject.face)
                    val identity = database.registerFaceTemplate(
                        name = name,
                        sample = sample,
                        modelName = LocalFaceEngine.EMBEDDING_MODEL_NAME,
                        sourcePhotoId = database.knownLocation(uriText)?.photoId,
                    )
                    val rematched = database.rematchFaceObservations(LocalFaceEngine.EMBEDDING_MODEL_NAME)
                    val report = FaceIndexer(applicationContext, database, faceEngine()).use { indexer ->
                        indexer.indexPending { processed, total, photoName ->
                            runOnUiThread {
                                state = state.copy(status = "本地人物识别 $processed / $total · $photoName")
                            }
                        }
                    }
                    "${identity.name} 已有 ${identity.templateCount} 个参考模板 · 扫描 ${report.photos} 张、匹配 ${report.matchedFaces} 张人脸 · 历史匹配 $rematched 张"
                } else {
                    val sample = requireNotNull(subject.pet)
                    val identity = database.registerPetTemplate(
                        name = name,
                        sample = sample,
                        modelName = LocalPetEngine.EMBEDDING_MODEL_NAME,
                        sourcePhotoId = database.knownLocation(uriText)?.photoId,
                    )
                    val rematched = database.rematchPetObservations(LocalPetEngine.EMBEDDING_MODEL_NAME)
                    val report = PetIndexer(applicationContext, database, petEngine()).use { indexer ->
                        indexer.indexPending { processed, total, photoName ->
                            runOnUiThread {
                                state = state.copy(status = "本地宠物识别 $processed / $total · $photoName")
                            }
                        }
                    }
                    "${identity.name} 已有 ${identity.templateCount} 个${speciesName(identity.species)}参考模板 · 扫描 ${report.photos} 张、匹配 ${report.matchedPets} 只宠物 · 历史匹配 $rematched 只"
                }
                val identities = database.localIdentitySummaries()
                runOnUiThread {
                    busy.set(false)
                    state = state.copy(name = "", identities = identities, busy = false, status = status)
                }
            } catch (error: Exception) {
                finishWork("注册失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun deleteIdentity(identity: LocalIdentitySummary) {
        if (!busy.compareAndSet(false, true)) return
        state = state.copy(busy = true, status = "正在删除 ${identity.name} 的本地模板并重新计算匹配…")
        executor.execute {
            try {
                when (identity.kind) {
                    LocalIdentityKind.PERSON ->
                        database.deleteIdentity(identity.id, LocalFaceEngine.EMBEDDING_MODEL_NAME)
                    LocalIdentityKind.PET ->
                        database.deletePetIdentity(identity.id, LocalPetEngine.EMBEDDING_MODEL_NAME)
                }
                val identities = database.localIdentitySummaries()
                runOnUiThread {
                    busy.set(false)
                    state = state.copy(
                        identities = identities,
                        busy = false,
                        status = "已删除 ${identity.name} 的本地身份和模板；照片原图未改变。",
                    )
                }
            } catch (error: Exception) {
                finishWork("删除失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun refreshIdentities() {
        executor.execute {
            val identities = database.localIdentitySummaries()
            runOnUiThread { state = state.copy(identities = identities) }
        }
    }

    private fun faceEngine(): LocalFaceEngine = faceEngine ?: LocalFaceEngine(applicationContext).also {
        faceEngine = it
    }

    private fun petEngine(): LocalPetEngine = petEngine ?: LocalPetEngine(applicationContext).also {
        petEngine = it
    }

    private fun finishWork(message: String) {
        runOnUiThread {
            busy.set(false)
            state = state.copy(busy = false, status = message)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdentityScreen(
    state: IdentityUiState,
    onBack: () -> Unit,
    onKindChange: (LocalIdentityKind) -> Unit,
    onPick: () -> Unit,
    onSelectSubject: (Int) -> Unit,
    onNameChange: (String) -> Unit,
    onRegister: () -> Unit,
    onDelete: (LocalIdentitySummary) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("identity_back")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回相册")
                    }
                },
                title = {
                    Column {
                        Text("本地身份识别", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "人物与宠物姓名不发送给 VLM",
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
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.Shield, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("仅保存派生特征", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "参考图只在内存中检测和裁剪。APM 不保存参考图或裁剪；归一化 embedding 与匹配分数只存于禁止备份的应用私有数据库。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
                            )
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = state.kind == LocalIdentityKind.PERSON,
                        onClick = { onKindChange(LocalIdentityKind.PERSON) },
                        enabled = !state.busy,
                        label = { Text("人物") },
                        leadingIcon = { Icon(Icons.Outlined.Face, contentDescription = null) },
                        modifier = Modifier.testTag("identity_kind_person"),
                    )
                    FilterChip(
                        selected = state.kind == LocalIdentityKind.PET,
                        onClick = { onKindChange(LocalIdentityKind.PET) },
                        enabled = !state.busy,
                        label = { Text("宠物（猫 / 狗）") },
                        leadingIcon = { Icon(Icons.Outlined.Pets, contentDescription = null) },
                        modifier = Modifier.testTag("identity_kind_pet"),
                    )
                }
            }
            item {
                Button(
                    onClick = onPick,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().height(54.dp).testTag("pick_reference_photo"),
                ) {
                    Icon(Icons.Outlined.AddAPhoto, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("选择参考照片")
                }
            }
            state.bitmap?.let { bitmap ->
                item {
                    SubjectSelectionImage(
                        bitmap = bitmap,
                        subjects = state.subjects,
                        selectedSubject = state.selectedSubject,
                        onSelectSubject = onSelectSubject,
                    )
                }
                item {
                    OutlinedTextField(
                        value = state.name,
                        onValueChange = onNameChange,
                        enabled = !state.busy && state.selectedSubject != null,
                        modifier = Modifier.fillMaxWidth().testTag("identity_name"),
                        singleLine = true,
                        label = { Text(if (state.kind == LocalIdentityKind.PERSON) "人物名字" else "宠物名字") },
                        placeholder = { Text(if (state.kind == LocalIdentityKind.PERSON) "例如：小明" else "例如：旺财") },
                        leadingIcon = {
                            Icon(
                                if (state.kind == LocalIdentityKind.PERSON) Icons.Outlined.Face else Icons.Outlined.Pets,
                                contentDescription = null,
                            )
                        },
                        supportingText = {
                            Text(
                                if (state.selectedSubject == null) "请先点击照片中的框选主体"
                                else "同名再次保存会为该身份增加一个参考模板",
                            )
                        },
                    )
                }
                item {
                    Button(
                        onClick = onRegister,
                        enabled = !state.busy && state.selectedSubject != null && state.name.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(54.dp).testTag("register_identity"),
                    ) {
                        Icon(Icons.Outlined.PersonAdd, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("保存并匹配相册", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth().testTag("identity_status"),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                ) {
                    Text(
                        state.status,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Text("已注册身份 · ${state.identities.size}", style = MaterialTheme.typography.titleMedium)
            }
            if (state.identities.isEmpty()) {
                item {
                    Text(
                        "还没有本地身份模板。人物请选择清晰正脸；宠物请选择主体完整、清晰的猫或狗。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(state.identities, key = { "${it.kind.storageValue}:${it.id}" }) { identity ->
                    IdentityCard(identity, state.busy, onDelete)
                }
            }
        }
    }
}

@Composable
private fun IdentityCard(
    identity: LocalIdentitySummary,
    busy: Boolean,
    onDelete: (LocalIdentitySummary) -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    if (identity.kind == LocalIdentityKind.PERSON) Icons.Outlined.Face else Icons.Outlined.Pets,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp).size(24.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(identity.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        append(identity.kind.displayName)
                        identity.species?.takeIf(String::isNotBlank)?.let { append(" · ${speciesName(it)}") }
                        append(" · ${identity.templateCount} 个参考模板")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = { onDelete(identity) },
                enabled = !busy,
                modifier = Modifier.testTag("delete_identity_${identity.kind.storageValue}_${identity.id}"),
            ) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除 ${identity.name}")
            }
        }
    }
}

@Composable
private fun SubjectSelectionImage(
    bitmap: Bitmap,
    subjects: List<ReferenceSubject>,
    selectedSubject: Int?,
    onSelectSubject: (Int) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag("reference_subject_canvas"),
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "用于本地身份注册的参考照片",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(subjects) {
                    detectTapGestures { offset ->
                        val x = offset.x / size.width
                        val y = offset.y / size.height
                        subjects.indexOfFirst { subject ->
                            x >= subject.box.left && x <= subject.box.right &&
                                y >= subject.box.top && y <= subject.box.bottom
                        }.takeIf { it >= 0 }?.let(onSelectSubject)
                    }
                },
        ) {
            subjects.forEachIndexed { index, subject ->
                val color = if (selectedSubject == index) Color(0xFF00C853) else Color(0xFFFFB300)
                drawRect(
                    color = color,
                    topLeft = Offset(subject.box.left * size.width, subject.box.top * size.height),
                    size = Size(
                        (subject.box.right - subject.box.left) * size.width,
                        (subject.box.bottom - subject.box.top) * size.height,
                    ),
                    style = Stroke(width = if (selectedSubject == index) 7f else 4f),
                )
            }
        }
    }
}

private fun speciesName(species: String): String = when (species) {
    "cat" -> "猫"
    "dog" -> "狗"
    else -> species
}
