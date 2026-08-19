package com.jinof.apm

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

data class DetectedObject(
    val category: String,
    val box: FaceBox,
    val score: Float,
)

class LocalObjectDetector(context: Context) : AutoCloseable {
    private val detector = Interpreter(
        context.applicationContext.assets.open(DETECTOR_ASSET).use { input ->
            val bytes = input.readBytes()
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes)
                rewind()
            }
        },
        Interpreter.Options().setNumThreads(INFERENCE_THREADS),
    )

    fun detectGeneralSubjects(bitmap: Bitmap): List<DetectedObject> {
        require(bitmap.width > 0 && bitmap.height > 0)
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        return try {
            val boxes = Array(1) { Array(MAX_RESULTS) { FloatArray(4) } }
            val classes = Array(1) { FloatArray(MAX_RESULTS) }
            val scores = Array(1) { FloatArray(MAX_RESULTS) }
            val count = FloatArray(1)
            detector.runForMultipleInputsOutputs(
                arrayOf(bitmapToUint8Input(scaled)),
                mapOf(0 to boxes, 1 to classes, 2 to scores, 3 to count),
            )
            val resultCount = count[0].roundToInt().coerceIn(0, MAX_RESULTS)
            (0 until resultCount).mapNotNull { index ->
                val classIndex = classes[0][index].roundToInt()
                val category = COCO_CATEGORIES.getOrNull(classIndex) ?: return@mapNotNull null
                val score = scores[0][index]
                if (
                    category == UNKNOWN_CATEGORY || category in EXCLUDED_CATEGORIES ||
                    !score.isFinite() || score < SCORE_THRESHOLD
                ) return@mapNotNull null
                val raw = boxes[0][index]
                val top = raw[0].coerceIn(0f, 1f)
                val left = raw[1].coerceIn(0f, 1f)
                val bottom = raw[2].coerceIn(0f, 1f)
                val right = raw[3].coerceIn(0f, 1f)
                if (right <= left || bottom <= top || (right - left) * (bottom - top) < MIN_AREA) {
                    return@mapNotNull null
                }
                DetectedObject(category, FaceBox(left, top, right, bottom), score)
            }.sortedByDescending(DetectedObject::score).take(MAX_GENERAL_SUBJECTS)
                .sortedWith(compareBy({ it.box.top }, { it.box.left }))
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun bitmapToUint8Input(bitmap: Bitmap): ByteBuffer {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return ByteBuffer.allocateDirect(pixels.size * 3).order(ByteOrder.nativeOrder()).apply {
            pixels.forEach { pixel ->
                put(((pixel shr 16) and 0xff).toByte())
                put(((pixel shr 8) and 0xff).toByte())
                put((pixel and 0xff).toByte())
            }
            rewind()
        }
    }

    override fun close() = detector.close()

    companion object {
        const val DETECTOR_NAME = "litert-ssd-mobilenet-v1-uint8-coco"
        private const val DETECTOR_ASSET = "models/pet_detection_ssd_mobilenet_v1_uint8.tflite"
        private const val INPUT_SIZE = 300
        private const val MAX_RESULTS = 10
        private const val MAX_GENERAL_SUBJECTS = 6
        private const val INFERENCE_THREADS = 4
        private const val SCORE_THRESHOLD = 0.35f
        private const val MIN_AREA = 0.01f
        private const val UNKNOWN_CATEGORY = "???"
        private val EXCLUDED_CATEGORIES = setOf("person", "cat", "dog")

        // TensorFlow Lite COCO SSD index order. Placeholder entries preserve class indices.
        private val COCO_CATEGORIES = listOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "???", "stop sign", "parking meter", "bench", "bird",
            "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "???",
            "backpack", "umbrella", "???", "???", "handbag", "tie", "suitcase", "frisbee", "skis",
            "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard",
            "surfboard", "tennis racket", "bottle", "???", "wine glass", "cup", "fork", "knife",
            "spoon", "bowl", "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog",
            "pizza", "donut", "cake", "chair", "couch", "potted plant", "bed", "???", "dining table",
            "???", "???", "toilet", "???", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone",
            "microwave", "oven", "toaster", "sink", "refrigerator", "???", "book", "clock", "vase",
            "scissors", "teddy bear", "hair drier", "toothbrush",
        )
    }
}
