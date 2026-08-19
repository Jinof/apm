package com.jinof.apm

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size as AndroidSize
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN
import org.opencv.objdetect.FaceRecognizerSF
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class LocalFaceEngine(
    context: Context,
    private val resolver: ContentResolver = context.contentResolver,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val detector: FaceDetectorYN
    private val recognizer: FaceRecognizerSF

    init {
        check(ensureOpenCv()) { "无法加载本地 OpenCV 运行时" }
        val detectorPath = installModel(
            assetName = DETECTOR_ASSET,
            fileName = "face_detection_yunet_2023mar.onnx",
            expectedSha256 = DETECTOR_SHA256,
        )
        val recognizerPath = installModel(
            assetName = RECOGNIZER_ASSET,
            fileName = "face_recognition_sface_2021dec.onnx",
            expectedSha256 = RECOGNIZER_SHA256,
        )
        detector = FaceDetectorYN.create(
            detectorPath.absolutePath,
            "",
            Size(320.0, 320.0),
            DETECTION_THRESHOLD,
            NMS_THRESHOLD,
            5_000,
        )
        recognizer = FaceRecognizerSF.create(recognizerPath.absolutePath, "")
    }

    fun loadBitmap(uri: Uri): Bitmap = resolver.loadThumbnail(uri, AndroidSize(MAX_IMAGE_EDGE, MAX_IMAGE_EDGE), null)

    fun analyze(uri: Uri): List<FaceSample> {
        val bitmap = loadBitmap(uri)
        return try {
            analyze(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    @Synchronized
    fun analyze(bitmap: Bitmap): List<FaceSample> {
        require(bitmap.width > 0 && bitmap.height > 0) { "照片尺寸不合法" }
        val rgba = Mat()
        val bgr = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            val detections = detectAcrossScales(bgr)
            if (detections.isEmpty()) return emptyList()
            return buildList {
                detections.forEachIndexed { index, detection ->
                    val values = detection.values
                    val score = values[14]
                    val left = (values[0] / bitmap.width).coerceIn(0f, 1f)
                    val top = (values[1] / bitmap.height).coerceIn(0f, 1f)
                    val right = ((values[0] + values[2]) / bitmap.width).coerceIn(0f, 1f)
                    val bottom = ((values[1] + values[3]) / bitmap.height).coerceIn(0f, 1f)
                    if (right <= left || bottom <= top) return@forEachIndexed
                    val faceRow = Mat(1, FACE_DETECTION_FIELDS, CvType.CV_32F)
                    val aligned = Mat()
                    val feature = Mat()
                    try {
                        check(faceRow.put(0, 0, values) == values.size * Float.SIZE_BYTES) {
                            "YuNet 人脸关键点写入不完整"
                        }
                        recognizer.alignCrop(bgr, faceRow, aligned)
                        recognizer.feature(aligned, feature)
                        val dimension = (feature.total() * feature.channels()).toInt()
                        if (dimension <= 0) return@forEachIndexed
                        val embedding = FloatArray(dimension)
                        check(feature.get(0, 0, embedding) == embedding.size * Float.SIZE_BYTES) {
                            "SFace embedding 读取不完整"
                        }
                        add(
                            FaceSample(
                                faceIndex = index,
                                box = FaceBox(left, top, right, bottom),
                                detectorScore = score,
                                embedding = FaceMatcher.normalize(embedding),
                            ),
                        )
                    } finally {
                        faceRow.release()
                        aligned.release()
                        feature.release()
                    }
                }
            }
        } finally {
            bgr.release()
            rgba.release()
        }
    }

    private fun detectAcrossScales(image: Mat): List<Detection> {
        val sourceWidth = image.cols()
        val sourceHeight = image.rows()
        val sourceLongEdge = max(sourceWidth, sourceHeight)
        val longEdges = DETECTION_LONG_EDGES
            .map { min(it, sourceLongEdge) }
            .distinct()
        val candidates = mutableListOf<Detection>()
        longEdges.forEach { targetLongEdge ->
            val scale = min(1.0, targetLongEdge.toDouble() / sourceLongEdge.toDouble())
            val targetWidth = max(1, (sourceWidth * scale).roundToInt())
            val targetHeight = max(1, (sourceHeight * scale).roundToInt())
            val scaled = if (targetWidth == sourceWidth && targetHeight == sourceHeight) {
                image
            } else {
                Mat().also {
                    Imgproc.resize(image, it, Size(targetWidth.toDouble(), targetHeight.toDouble()))
                }
            }
            val faces = Mat()
            try {
                detector.setInputSize(Size(scaled.cols().toDouble(), scaled.rows().toDouble()))
                detector.detect(scaled, faces)
                val scaleX = sourceWidth.toFloat() / scaled.cols().toFloat()
                val scaleY = sourceHeight.toFloat() / scaled.rows().toFloat()
                for (row in 0 until faces.rows()) {
                    val values = FloatArray(FACE_DETECTION_FIELDS)
                    if (faces.get(row, 0, values) != values.size * Float.SIZE_BYTES) continue
                    mapDetectionToSource(values, scaleX, scaleY)
                    if (
                        values[14] >= DETECTION_THRESHOLD &&
                        values[2] >= MIN_FACE_PIXELS &&
                        values[3] >= MIN_FACE_PIXELS
                    ) {
                        candidates += Detection(values)
                    }
                }
            } finally {
                faces.release()
                if (scaled !== image) scaled.release()
            }
        }
        return deduplicate(candidates)
    }

    private fun mapDetectionToSource(values: FloatArray, scaleX: Float, scaleY: Float) {
        values[0] *= scaleX
        values[1] *= scaleY
        values[2] *= scaleX
        values[3] *= scaleY
        for (index in 4 until 14 step 2) {
            values[index] *= scaleX
            values[index + 1] *= scaleY
        }
    }

    private fun deduplicate(candidates: List<Detection>): List<Detection> {
        val kept = mutableListOf<Detection>()
        candidates.sortedByDescending(Detection::score).forEach { candidate ->
            if (kept.none { existing -> intersectionOverUnion(candidate, existing) >= CROSS_SCALE_NMS_THRESHOLD }) {
                kept += candidate
            }
        }
        return kept.sortedWith(compareBy({ it.top }, { it.left }))
    }

    private fun intersectionOverUnion(first: Detection, second: Detection): Float {
        val intersectionLeft = max(first.left, second.left)
        val intersectionTop = max(first.top, second.top)
        val intersectionRight = min(first.right, second.right)
        val intersectionBottom = min(first.bottom, second.bottom)
        val intersectionWidth = max(0f, intersectionRight - intersectionLeft)
        val intersectionHeight = max(0f, intersectionBottom - intersectionTop)
        val intersection = intersectionWidth * intersectionHeight
        if (intersection <= 0f) return 0f
        val union = first.area + second.area - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    override fun close() {
        // OpenCV Java wrappers own native cleanup. This class intentionally retains one detector and recognizer per worker.
    }

    private fun installModel(assetName: String, fileName: String, expectedSha256: String): File {
        val directory = File(appContext.noBackupFilesDir, "face-models").apply { mkdirs() }
        val destination = File(directory, fileName)
        if (!destination.isFile || sha256(destination) != expectedSha256) {
            val temporary = File(directory, "$fileName.tmp")
            appContext.assets.open(assetName).use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output) }
            }
            check(sha256(temporary) == expectedSha256) { "本地人脸模型校验失败：$fileName" }
            check(!destination.exists() || destination.delete()) { "无法替换损坏的本地人脸模型：$fileName" }
            check(temporary.renameTo(destination)) { "无法安装本地人脸模型：$fileName" }
        }
        return destination
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

    companion object {
        const val DETECTOR_NAME = "opencv-yunet-2023mar@8f2383e4dd3c+multiscale-v2"
        const val EMBEDDING_MODEL_NAME = "opencv-sface-2021dec@0ba9fbfa01b5"
        const val FACE_INDEX_VERSION = "yunet-2023mar-multiscale-v2+sface-2021dec"
        private const val DETECTOR_ASSET = "models/face_detection_yunet_2023mar.onnx"
        private const val RECOGNIZER_ASSET = "models/face_recognition_sface_2021dec.onnx"
        private const val DETECTOR_SHA256 = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"
        private const val RECOGNIZER_SHA256 = "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79"
        private const val MAX_IMAGE_EDGE = 1_600
        private const val FACE_DETECTION_FIELDS = 15
        private const val MIN_FACE_PIXELS = 100f
        private const val DETECTION_THRESHOLD = 0.9f
        private const val NMS_THRESHOLD = 0.3f
        private const val CROSS_SCALE_NMS_THRESHOLD = 0.35f
        private val DETECTION_LONG_EDGES = listOf(256, 512, 1_024)

        @Volatile
        private var openCvReady = false

        private fun ensureOpenCv(): Boolean {
            if (openCvReady) return true
            synchronized(LocalFaceEngine::class.java) {
                if (!openCvReady) openCvReady = OpenCVLoader.initLocal()
                return openCvReady
            }
        }
    }

    private data class Detection(val values: FloatArray) {
        val left: Float get() = values[0]
        val top: Float get() = values[1]
        val right: Float get() = values[0] + values[2]
        val bottom: Float get() = values[1] + values[3]
        val area: Float get() = values[2] * values[3]
        val score: Float get() = values[14]
    }
}

class FaceIndexer(
    context: Context,
    private val database: ApmDatabase,
    providedEngine: LocalFaceEngine? = null,
) : AutoCloseable {
    private val engine = providedEngine ?: LocalFaceEngine(context)
    private val ownsEngine = providedEngine == null

    fun indexPending(
        limit: Int? = 500,
        selectedOnly: Boolean = false,
        onProgress: (processed: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
    ): FaceIndexReport {
        val pending = database.pendingFacePhotos(
            modelName = LocalFaceEngine.FACE_INDEX_VERSION,
            limit = limit,
            selectedOnly = selectedOnly,
        )
        val templates = database.faceTemplates(LocalFaceEngine.EMBEDDING_MODEL_NAME)
        var faces = 0
        var matched = 0
        val errors = mutableListOf<String>()
        pending.forEachIndexed { index, photo ->
            onProgress(index + 1, pending.size, photo.displayName)
            try {
                val samples = engine.analyze(Uri.parse(photo.uri))
                val decisions = samples.map { sample -> FaceMatcher.decide(sample.embedding, templates) }
                database.replaceFaceObservations(
                    photoId = photo.photoId,
                    samples = samples,
                    decisions = decisions,
                    modelName = LocalFaceEngine.EMBEDDING_MODEL_NAME,
                    detectorName = LocalFaceEngine.DETECTOR_NAME,
                    indexVersion = LocalFaceEngine.FACE_INDEX_VERSION,
                )
                faces += samples.size
                matched += decisions.count { it.state == FaceMatcher.MATCHED }
            } catch (error: Exception) {
                errors += "${photo.displayName}：${error.message ?: error.javaClass.simpleName}"
            }
        }
        return FaceIndexReport(
            photos = pending.size,
            faces = faces,
            matchedFaces = matched,
            unknownFaces = faces - matched,
            errors = errors,
        )
    }

    fun rematchStored(): Int = database.rematchFaceObservations(LocalFaceEngine.EMBEDDING_MODEL_NAME)

    override fun close() {
        if (ownsEngine) engine.close()
    }
}
