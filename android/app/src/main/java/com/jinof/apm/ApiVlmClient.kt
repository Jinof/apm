package com.jinof.apm

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import kotlin.math.roundToInt

internal object VlmInferencePolicy {
    const val PRIMARY_MAX_EDGE = 1024
    const val RETRY_MAX_EDGE = 768
    const val JPEG_QUALITY = 82

    val maxEdgeAttempts = intArrayOf(PRIMARY_MAX_EDGE, RETRY_MAX_EDGE)

    fun isContextOverflow(message: String): Boolean {
        val normalized = message.lowercase()
        return "context" in normalized && (
            "exceed" in normalized ||
                "too large" in normalized ||
                "too long" in normalized
            )
    }

    fun shouldRetryContextOverflow(attemptIndex: Int, status: Int, message: String): Boolean =
        attemptIndex == 0 &&
            status in setOf(HttpURLConnection.HTTP_BAD_REQUEST, HttpURLConnection.HTTP_ENTITY_TOO_LARGE) &&
            isContextOverflow(message)
}

internal data class EncodedVlmImage(
    val base64: String,
    val width: Int,
    val height: Int,
)

internal object VlmThumbnailEncoder {
    fun encode(
        source: Bitmap,
        maxEdge: Int,
    ): EncodedVlmImage {
        require(maxEdge > 0) { "推理缩略图边长必须为正数" }
        val sourceMaxEdge = maxOf(source.width, source.height)
        val bounded = if (sourceMaxEdge <= maxEdge) {
            source
        } else {
            val scale = maxEdge.toFloat() / sourceMaxEdge
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).roundToInt().coerceAtLeast(1),
                (source.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        }
        try {
            check(maxOf(bounded.width, bounded.height) <= maxEdge) {
                "推理缩略图超过 ${maxEdge}px 上限"
            }
            val output = ByteArrayOutputStream()
            if (!bounded.compress(Bitmap.CompressFormat.JPEG, VlmInferencePolicy.JPEG_QUALITY, output)) {
                throw IllegalStateException("无法生成只读推理缩略图")
            }
            return EncodedVlmImage(
                base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP),
                width = bounded.width,
                height = bounded.height,
            )
        } finally {
            if (bounded !== source) bounded.recycle()
        }
    }
}

class ApiVlmClient(
    private val resolver: ContentResolver,
    candidateConfig: InferenceConfig,
) : java.io.Closeable {
    private val api = ExternalApiClient(candidateConfig)
    val config: InferenceConfig = api.config

    override fun close() = api.close()

    fun annotate(uri: Uri, onStage: (String) -> Unit = {}): PhotoAnnotation {
        // Validate the destination and consent before loading photo bytes.
        var contextOverflow: ApiRequestException? = null
        for ((attemptIndex, maxEdge) in VlmInferencePolicy.maxEdgeAttempts.withIndex()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            onStage(if (attemptIndex == 0) "准备图片缩略图" else "缩小图片后重试")
            val image = resizedJpegBase64(uri, maxEdge)
            Log.i(VLM_LOG_TAG, "request attempt=${attemptIndex + 1} thumbnail=${image.width}x${image.height}")
            val parsed = try {
                onStage("请求模型，等待响应")
                api.complete(AnnotationRequestFactory.create(config.modelName, image.base64))
            } catch (error: ApiRequestException) {
                if (attemptIndex != 0 || error.status !in setOf(400, 413) || !error.contextOverflow) throw error
                contextOverflow = error
                continue
            }
            return try {
                onStage("校验标注结构")
                AnnotationContract.parseVlm(parsed)
            } catch (_: Exception) {
                throw IllegalStateException("API 返回的照片标注不符合约定，原有数据已保留")
            }
        }
        throw checkNotNull(contextOverflow)
    }

    fun annotateGroup(uris: List<Uri>, onStage: (String) -> Unit = {}): List<PhotoAnnotation> {
        require(uris.size in 1..config.mergeCount)
        if (config.mergeCount == 1) return listOf(annotate(uris.single(), onStage))
        var contextOverflow: ApiRequestException? = null
        for ((attempt, maxEdge) in VlmInferencePolicy.maxEdgeAttempts.withIndex()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            onStage(if (attempt == 0) "准备 ${uris.size} 张编号合图" else "缩小合图后重试")
            val image = NumberedCollage.encode(uris.size, maxEdge) { index, edge ->
                NumberedCollage.loadPhoto(resolver, uris[index], edge)
            }
            val response = try {
                onStage("请求模型，等待整组响应")
                api.complete(AnnotationRequestFactory.createBatch(config.modelName, image.base64, uris.size))
            } catch (error: ApiRequestException) {
                if (attempt != 0 || error.status !in setOf(400, 413) || !error.contextOverflow) throw error
                contextOverflow = error
                continue
            }
            onStage("校验整组编号与标注结构")
            return try { BatchAnnotationContract.parse(response, uris.size) }
            catch (_: Exception) { throw IllegalStateException("合图标注缺失、编号重复或结构无效，本组原有数据已保留") }
        }
        throw checkNotNull(contextOverflow)
    }

    private fun resizedJpegBase64(uri: Uri, maxEdge: Int): EncodedVlmImage {
        val thumbnail: Bitmap = resolver.loadThumbnail(uri, Size(maxEdge, maxEdge), null)
        return try {
            VlmThumbnailEncoder.encode(thumbnail, maxEdge)
        } finally {
            thumbnail.recycle()
        }
    }
}

private const val VLM_LOG_TAG = "APM.VLM"

object AnnotationRequestFactory {
    fun create(modelName: String, imageBase64: String): JSONObject = ExternalApiClient.request(
        modelName, imageMessages(AnnotationContract.prompt(), imageBase64), "photo_annotation", AnnotationContract.schema(),
    )

    fun createBatch(modelName: String, imageBase64: String, count: Int): JSONObject = ExternalApiClient.request(
        modelName, imageMessages(BatchAnnotationContract.prompt(count), imageBase64),
        "photo_annotation_batch", BatchAnnotationContract.schema(count),
    ).put("max_tokens", 1536 * count)

    private fun imageMessages(prompt: String, imageBase64: String) = JSONArray().put(JSONObject().put("role", "user")
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject()
                .put("url", "data:image/jpeg;base64,$imageBase64")))))
}
