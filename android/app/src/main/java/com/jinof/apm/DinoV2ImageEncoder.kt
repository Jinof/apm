package com.jinof.apm

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.util.Size
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.math.min

data class DinoV2FeatureOutput(
    val globalEmbedding: FloatArray,
    val patchGrid: List<FloatArray>,
)

interface DinoV2ImageEncoder : AutoCloseable {
    val modelName: String
    val pipelineVersion: String
    fun encode(bitmap: Bitmap): DinoV2FeatureOutput
}

class OnnxRuntimeDinoV2ImageEncoder private constructor(
    private val model: InstalledModel,
) : DinoV2ImageEncoder {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    constructor(context: Context) : this(installOrOpenModel(context.applicationContext ?: context))

    internal constructor(modelFile: File, expectedSha256: String) : this(
        validatedModel(modelFile, expectedSha256),
    )

    override val modelName: String = "dinov2-vits14-reg@${model.sha256.take(12)}"
    override val pipelineVersion: String = SimilarityScorer.PIPELINE_VERSION

    init {
        session = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(1)
            environment.createSession(model.file.absolutePath, options)
        }
        check(session.inputNames == setOf(INPUT_NAME)) {
            "DINOv2 ONNX 输入必须为 $INPUT_NAME，实际为 ${session.inputNames.sorted()}"
        }
        check(OUTPUT_NAME in session.outputNames) {
            "DINOv2 ONNX 缺少 $OUTPUT_NAME 输出"
        }
    }

    @Synchronized
    override fun encode(bitmap: Bitmap): DinoV2FeatureOutput {
        require(bitmap.width > 0 && bitmap.height > 0) { "照片尺寸不合法" }
        val inputBitmap = letterbox(bitmap)
        val inputValues = normalizedNchwValues(inputBitmap)
        try {
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(inputValues),
                longArrayOf(1, CHANNELS.toLong(), INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
            ).use { inputTensor ->
                session.run(mapOf(INPUT_NAME to inputTensor), setOf(OUTPUT_NAME)).use { result ->
                    val output = result.get(OUTPUT_NAME).orElseThrow {
                        IllegalStateException("DINOv2 ONNX 未返回 $OUTPUT_NAME")
                    }.value
                    @Suppress("UNCHECKED_CAST")
                    val batches = output as? Array<Array<FloatArray>>
                        ?: error("DINOv2 ONNX 必须输出 float32 [1, tokens, dimension]")
                    return decodeOutput(batches)
                }
            }
        } finally {
            inputBitmap.recycle()
        }
    }

    override fun close() {
        session.close()
    }

    private fun letterbox(source: Bitmap): Bitmap {
        val target = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        canvas.drawColor(Color.rgb(124, 116, 104))
        val scale = min(INPUT_SIZE.toFloat() / source.width, INPUT_SIZE.toFloat() / source.height)
        val width = source.width * scale
        val height = source.height * scale
        val left = (INPUT_SIZE - width) / 2f
        val top = (INPUT_SIZE - height) / 2f
        canvas.drawBitmap(
            source,
            null,
            RectF(left, top, left + width, top + height),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        return target
    }

    private fun normalizedNchwValues(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        val values = FloatArray(CHANNELS * pixels.size)
        pixels.forEachIndexed { index, pixel ->
            values[index] = (((pixel shr 16) and 0xff) / 255f - MEAN[0]) / STD[0]
            values[pixels.size + index] = (((pixel shr 8) and 0xff) / 255f - MEAN[1]) / STD[1]
            values[pixels.size * 2 + index] = ((pixel and 0xff) / 255f - MEAN[2]) / STD[2]
        }
        return values
    }

    private fun decodeOutput(batches: Array<Array<FloatArray>>): DinoV2FeatureOutput {
        check(batches.size == 1) {
            "DINOv2 ONNX 必须输出 [1, tokens, dimension] 的 $OUTPUT_NAME"
        }
        val tokens = batches.single()
        val tokenCount = tokens.size
        val dimension = tokens.firstOrNull()?.size ?: 0
        check(tokens.all { it.size == dimension }) { "DINOv2 token 维度不一致" }
        val patchCount = PATCHES_PER_SIDE * PATCHES_PER_SIDE
        check(tokenCount == EXPECTED_TOKEN_COUNT && dimension == EXPECTED_DIMENSION) {
            "DINOv2 ViT-S/14 registers 输出必须为 [1, $EXPECTED_TOKEN_COUNT, $EXPECTED_DIMENSION]，实际为 [1, $tokenCount, $dimension]"
        }
        val registerCount = tokenCount - patchCount - 1
        val global = FaceMatcher.normalize(tokens[0].copyOf())
        val patchVectors = List(patchCount) { patchIndex ->
            FaceMatcher.normalize(tokens[1 + registerCount + patchIndex].copyOf())
        }
        val grid = List(PhotoVisualFeature.PATCH_COUNT) { cellIndex ->
            val cellRow = cellIndex / PhotoVisualFeature.GRID_SIZE
            val cellColumn = cellIndex % PhotoVisualFeature.GRID_SIZE
            val members = patchVectors.indices.filter { patchIndex ->
                val patchRow = patchIndex / PATCHES_PER_SIDE
                val patchColumn = patchIndex % PATCHES_PER_SIDE
                patchRow * PhotoVisualFeature.GRID_SIZE / PATCHES_PER_SIDE == cellRow &&
                    patchColumn * PhotoVisualFeature.GRID_SIZE / PATCHES_PER_SIDE == cellColumn
            }
            val mean = FloatArray(dimension)
            members.forEach { patchIndex ->
                patchVectors[patchIndex].forEachIndexed { index, value -> mean[index] += value }
            }
            FaceMatcher.normalize(mean)
        }
        return DinoV2FeatureOutput(global, grid)
    }

    private data class InstalledModel(val file: File, val sha256: String)

    companion object {
        const val ASSET_MODEL = "models/dinov2_vits14_reg.onnx"
        const val ASSET_SHA256 = "models/dinov2_vits14_reg.onnx.sha256"
        private const val INPUT_SIZE = 224
        private const val PATCH_SIZE = 14
        private const val PATCHES_PER_SIDE = INPUT_SIZE / PATCH_SIZE
        private const val CHANNELS = 3
        private const val EXPECTED_TOKEN_COUNT = 261
        private const val EXPECTED_DIMENSION = 384
        private const val INPUT_NAME = "pixel_values"
        private const val OUTPUT_NAME = "last_hidden_state"
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        fun availability(context: Context): String? = runCatching {
            val appContext = context.applicationContext ?: context
            val directory = File(appContext.noBackupFilesDir, "visual-models")
            val destination = File(directory, MODEL_FILENAME)
            val checksumFile = File(directory, CHECKSUM_FILENAME)
            if (destination.exists() || checksumFile.exists()) {
                validatedModel(destination, checksumFile.readText())
            } else {
                appContext.assets.open(ASSET_MODEL).use { }
                appContext.assets.open(ASSET_SHA256).bufferedReader().use { parseSha256(it.readText()) }
            }
            null
        }.getOrElse { error -> error.message ?: "DINOv2 模型不可用" }

        private fun installOrOpenModel(context: Context): InstalledModel {
            val directory = File(context.noBackupFilesDir, "visual-models").apply { mkdirs() }
            val destination = File(directory, MODEL_FILENAME)
            val checksumFile = File(directory, CHECKSUM_FILENAME)
            if (destination.exists() || checksumFile.exists()) {
                check(destination.isFile && checksumFile.isFile) { "DINOv2 本地模型不完整；为保护现有文件，未自动覆盖" }
                val expected = parseSha256(checksumFile.readText())
                val actual = sha256(destination)
                check(actual == expected) { "DINOv2 本地模型校验失败；为保护现有文件，未自动覆盖" }
                return InstalledModel(destination, actual)
            }

            val expected = try {
                context.assets.open(ASSET_SHA256).bufferedReader().use { parseSha256(it.readText()) }
            } catch (_: Exception) {
                throw IllegalStateException("DINOv2 模型资源缺失：需要 $MODEL_FILENAME 与 SHA-256")
            }
            val temporary = File(directory, "$MODEL_FILENAME.${UUID.randomUUID()}.tmp")
            try {
                context.assets.open(ASSET_MODEL).use { input ->
                    FileOutputStream(temporary).use { output -> input.copyTo(output) }
                }
            } catch (error: Exception) {
                throw IllegalStateException("DINOv2 模型资源缺失：${error.message}")
            }
            val actual = sha256(temporary)
            check(actual == expected) { "DINOv2 APK 模型校验失败" }
            check(temporary.renameTo(destination)) { "无法安装 DINOv2 本地模型" }
            checksumFile.writeText("$expected\n")
            return InstalledModel(destination, actual)
        }

        private fun validatedModel(file: File, expectedSha256: String): InstalledModel {
            val expected = parseSha256(expectedSha256)
            check(file.isFile) { "DINOv2 ONNX 文件不存在" }
            val actual = sha256(file)
            check(actual == expected) { "DINOv2 ONNX 文件校验失败" }
            return InstalledModel(file, actual)
        }

        private fun parseSha256(value: String): String {
            val checksum = value.trim().substringBefore(' ').lowercase()
            require(checksum.matches(Regex("[0-9a-f]{64}"))) { "DINOv2 SHA-256 格式不合法" }
            return checksum
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private const val MODEL_FILENAME = "dinov2_vits14_reg.onnx"
        private const val CHECKSUM_FILENAME = "dinov2_vits14_reg.onnx.sha256"
    }
}

data class VisualIndexReport(
    val consideredPhotos: Int,
    val indexedPhotos: Int,
    val generalSubjects: Int,
    val excludedWithoutCaptureTime: Int,
    val errors: List<String>,
)

class VisualSimilarityIndexer(
    context: Context,
    private val database: ApmDatabase,
    providedEncoder: DinoV2ImageEncoder? = null,
    providedDetector: LocalObjectDetector? = null,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = context.contentResolver
    private val encoder = providedEncoder ?: OnnxRuntimeDinoV2ImageEncoder(context)
    private val detector = providedDetector ?: LocalObjectDetector(context)
    private val ownsEncoder = providedEncoder == null
    private val ownsDetector = providedDetector == null

    fun indexPending(
        selectedOnly: Boolean = false,
        limit: Int? = null,
        onProgress: (processed: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
    ): VisualIndexReport {
        return indexSelection(
            VisualCheckSelection(
                database.pendingVisualPhotos(
                    modelName = encoder.modelName,
                    pipelineVersion = encoder.pipelineVersion,
                    limit = limit,
                    selectedOnly = selectedOnly,
                ),
            ),
            onProgress,
        )
    }

    fun check(
        request: SimilarityCheckRequest,
        onProgress: (processed: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
    ): VisualIndexReport = indexSelection(
        database.visualPhotosForCheck(
            request = request,
            modelName = encoder.modelName,
            pipelineVersion = encoder.pipelineVersion,
        ),
        onProgress,
    )

    private fun indexSelection(
        selection: VisualCheckSelection,
        onProgress: (processed: Int, total: Int, name: String) -> Unit,
    ): VisualIndexReport {
        val photos = selection.photos
        var indexedCount = 0
        var subjectCount = 0
        val errors = mutableListOf<String>()
        photos.forEachIndexed { index, photo ->
            onProgress(index + 1, photos.size, photo.displayName)
            try {
                val bitmap = resolver.loadThumbnail(Uri.parse(photo.uri), Size(MAX_IMAGE_EDGE, MAX_IMAGE_EDGE), null)
                    .copy(Bitmap.Config.ARGB_8888, false)
                try {
                    val output = encoder.encode(bitmap)
                    val subjects = detector.detectGeneralSubjects(bitmap).mapIndexedNotNull { subjectIndex, detected ->
                        runCatching {
                            val crop = crop(bitmap, detected.box)
                            try {
                                GeneralSubjectObservation(
                                    subjectIndex = subjectIndex,
                                    category = detected.category,
                                    box = detected.box,
                                    detectorScore = detected.score,
                                    embedding = encoder.encode(crop).globalEmbedding,
                                    detectorName = LocalObjectDetector.DETECTOR_NAME,
                                    modelName = encoder.modelName,
                                    pipelineVersion = encoder.pipelineVersion,
                                )
                            } finally {
                                crop.recycle()
                            }
                        }.getOrNull()
                    }
                    database.replaceVisualFeature(
                        PhotoVisualFeature(
                            photoId = photo.photoId,
                            globalEmbedding = output.globalEmbedding,
                            patchEmbeddings = output.patchGrid,
                            modelName = encoder.modelName,
                            pipelineVersion = encoder.pipelineVersion,
                            indexedAt = Instant.now().toString(),
                        ),
                        subjects,
                    )
                    indexedCount += 1
                    subjectCount += subjects.size
                } finally {
                    bitmap.recycle()
                }
            } catch (error: Exception) {
                errors += "${photo.displayName}：${error.message ?: error.javaClass.simpleName}"
            }
        }
        return VisualIndexReport(
            consideredPhotos = photos.size,
            indexedPhotos = indexedCount,
            generalSubjects = subjectCount,
            excludedWithoutCaptureTime = selection.excludedWithoutCaptureTime,
            errors = errors,
        )
    }

    private fun crop(bitmap: Bitmap, box: FaceBox): Bitmap {
        val left = (box.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val top = (box.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val right = (box.right * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (box.bottom * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
        require(right - left >= MIN_CROP_EDGE && bottom - top >= MIN_CROP_EDGE) { "通用物体区域过小" }
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    override fun close() {
        if (ownsDetector) detector.close()
        if (ownsEncoder) encoder.close()
    }

    companion object {
        private const val MAX_IMAGE_EDGE = 1_600
        private const val MIN_CROP_EDGE = 32
    }
}
