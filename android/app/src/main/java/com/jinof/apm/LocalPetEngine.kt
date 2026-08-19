package com.jinof.apm

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class LocalPetEngine(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val detector = Interpreter(
        loadModel(DETECTOR_ASSET),
        Interpreter.Options().setNumThreads(INFERENCE_THREADS),
    )
    private val embedder = Interpreter(
        loadModel(EMBEDDER_ASSET),
        Interpreter.Options().setNumThreads(INFERENCE_THREADS),
    )

    fun loadBitmap(uri: Uri): Bitmap = appContext.contentResolver.loadThumbnail(
        uri,
        Size(MAX_IMAGE_EDGE, MAX_IMAGE_EDGE),
        null,
    ).copy(Bitmap.Config.ARGB_8888, false)

    fun analyze(uri: Uri): List<PetSample> {
        val bitmap = loadBitmap(uri)
        return try {
            analyze(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    fun analyze(bitmap: Bitmap): List<PetSample> {
        require(bitmap.width > 0 && bitmap.height > 0) { "宠物参考图尺寸不合法" }
        val ordered = detect(bitmap).sortedWith(compareBy<DetectionCandidate>({ it.box.top }, { it.box.left }))

        return ordered.mapIndexed { index, candidate ->
            PetSample(
                petIndex = index,
                box = candidate.box,
                species = candidate.species,
                detectorScore = candidate.score,
                embedding = embed(bitmap, candidate.box),
            )
        }
    }

    internal fun embed(bitmap: Bitmap, box: FaceBox): FloatArray {
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val boxWidth = (box.right - box.left) * width
        val boxHeight = (box.bottom - box.top) * height
        val padX = boxWidth * CROP_PADDING
        val padY = boxHeight * CROP_PADDING
        val left = max(0, (box.left * width - padX).toInt())
        val top = max(0, (box.top * height - padY).toInt())
        val right = min(bitmap.width, (box.right * width + padX).toInt())
        val bottom = min(bitmap.height, (box.bottom * height + padY).toInt())
        require(right - left >= MIN_CROP_EDGE && bottom - top >= MIN_CROP_EDGE) {
            "宠物区域过小，无法提取稳定特征"
        }
        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        return try {
            val scaled = Bitmap.createScaledBitmap(crop, EMBEDDER_INPUT_SIZE, EMBEDDER_INPUT_SIZE, true)
            try {
                val input = bitmapToFloatInput(scaled)
                val output = Array(1) { FloatArray(EMBEDDING_DIMENSION) }
                embedder.run(input, output)
                FaceMatcher.normalize(output[0])
            } finally {
                if (scaled !== crop) scaled.recycle()
            }
        } finally {
            if (crop !== bitmap) crop.recycle()
        }
    }

    private fun detect(bitmap: Bitmap): List<DetectionCandidate> {
        val scaled = Bitmap.createScaledBitmap(bitmap, DETECTOR_INPUT_SIZE, DETECTOR_INPUT_SIZE, true)
        return try {
            val boxes = Array(1) { Array(DETECTOR_MAX_RESULTS) { FloatArray(4) } }
            val classes = Array(1) { FloatArray(DETECTOR_MAX_RESULTS) }
            val scores = Array(1) { FloatArray(DETECTOR_MAX_RESULTS) }
            val count = FloatArray(1)
            detector.runForMultipleInputsOutputs(
                arrayOf(bitmapToUint8Input(scaled)),
                mapOf(
                    0 to boxes,
                    1 to classes,
                    2 to scores,
                    3 to count,
                ),
            )
            val resultCount = count[0].roundToInt().coerceIn(0, DETECTOR_MAX_RESULTS)
            (0 until resultCount).mapNotNull { index ->
                val score = scores[0][index]
                if (!score.isFinite() || score < DETECTOR_THRESHOLD) return@mapNotNull null
                val species = when (classes[0][index].roundToInt()) {
                    COCO_CAT_INDEX -> "cat"
                    COCO_DOG_INDEX -> "dog"
                    else -> return@mapNotNull null
                }
                val raw = boxes[0][index]
                val top = raw[0].coerceIn(0f, 1f)
                val left = raw[1].coerceIn(0f, 1f)
                val bottom = raw[2].coerceIn(0f, 1f)
                val right = raw[3].coerceIn(0f, 1f)
                if (right <= left || bottom <= top) return@mapNotNull null
                DetectionCandidate(FaceBox(left, top, right, bottom), species, score)
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun bitmapToUint8Input(bitmap: Bitmap): ByteBuffer {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return ByteBuffer.allocateDirect(pixels.size * RGB_CHANNELS)
            .order(ByteOrder.nativeOrder())
            .apply {
                pixels.forEach { pixel ->
                    put(((pixel shr 16) and 0xff).toByte())
                    put(((pixel shr 8) and 0xff).toByte())
                    put((pixel and 0xff).toByte())
                }
                rewind()
            }
    }

    private fun bitmapToFloatInput(bitmap: Bitmap): ByteBuffer {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return ByteBuffer.allocateDirect(pixels.size * RGB_CHANNELS * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply {
                pixels.forEach { pixel ->
                    putFloat(((pixel shr 16) and 0xff) / 255f)
                    putFloat(((pixel shr 8) and 0xff) / 255f)
                    putFloat((pixel and 0xff) / 255f)
                }
                rewind()
            }
    }

    private fun loadModel(assetPath: String): ByteBuffer {
        val bytes = appContext.assets.open(assetPath).use { it.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size)
            .order(ByteOrder.nativeOrder())
            .apply {
                put(bytes)
                rewind()
            }
    }

    override fun close() {
        detector.close()
        embedder.close()
    }

    private data class DetectionCandidate(
        val box: FaceBox,
        val species: String,
        val score: Float,
    )

    companion object {
        const val DETECTOR_NAME = "litert-ssd-mobilenet-v1-uint8-coco"
        const val EMBEDDING_MODEL_NAME = "litert-mobilenet-v3-small-float32-imagenet"
        const val PET_INDEX_VERSION = "ssd-mobilenet-v1-uint8+mobilenet-v3-small-v2"
        private const val DETECTOR_ASSET = "models/pet_detection_ssd_mobilenet_v1_uint8.tflite"
        private const val EMBEDDER_ASSET = "models/pet_embedding_mobilenet_v3_small_float32.tflite"
        private const val MAX_IMAGE_EDGE = 1600
        private const val DETECTOR_INPUT_SIZE = 300
        private const val DETECTOR_MAX_RESULTS = 10
        private const val EMBEDDER_INPUT_SIZE = 224
        private const val EMBEDDING_DIMENSION = 1024
        private const val RGB_CHANNELS = 3
        private const val COCO_CAT_INDEX = 16
        private const val COCO_DOG_INDEX = 17
        private const val INFERENCE_THREADS = 4
        private const val DETECTOR_THRESHOLD = 0.35f
        private const val CROP_PADDING = 0.12f
        private const val MIN_CROP_EDGE = 32
    }
}

class PetIndexer(
    context: Context,
    private val database: ApmDatabase,
    providedEngine: LocalPetEngine? = null,
) : AutoCloseable {
    private val engine = providedEngine ?: LocalPetEngine(context)
    private val ownsEngine = providedEngine == null

    fun indexPending(
        limit: Int? = 500,
        selectedOnly: Boolean = false,
        onProgress: (processed: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
    ): PetIndexReport {
        val pending = database.pendingPetPhotos(
            indexVersion = LocalPetEngine.PET_INDEX_VERSION,
            limit = limit,
            selectedOnly = selectedOnly,
        )
        val templates = database.petTemplates(LocalPetEngine.EMBEDDING_MODEL_NAME)
        var pets = 0
        var matched = 0
        val errors = mutableListOf<String>()
        pending.forEachIndexed { index, photo ->
            onProgress(index + 1, pending.size, photo.displayName)
            try {
                val samples = engine.analyze(Uri.parse(photo.uri))
                val decisions = samples.map { sample ->
                    PetMatcher.decide(sample.embedding, sample.species, templates)
                }
                database.replacePetObservations(
                    photoId = photo.photoId,
                    samples = samples,
                    decisions = decisions,
                    modelName = LocalPetEngine.EMBEDDING_MODEL_NAME,
                    detectorName = LocalPetEngine.DETECTOR_NAME,
                    indexVersion = LocalPetEngine.PET_INDEX_VERSION,
                )
                pets += samples.size
                matched += decisions.count { it.state == PetMatcher.MATCHED }
            } catch (error: Exception) {
                errors += "${photo.displayName}：${error.message ?: error.javaClass.simpleName}"
            }
        }
        return PetIndexReport(
            photos = pending.size,
            pets = pets,
            matchedPets = matched,
            unknownPets = pets - matched,
            errors = errors,
        )
    }

    fun rematchStored(): Int = database.rematchPetObservations(LocalPetEngine.EMBEDDING_MODEL_NAME)

    override fun close() {
        if (ownsEngine) engine.close()
    }
}
