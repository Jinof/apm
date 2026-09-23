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
) {
    private val api = ExternalApiClient(candidateConfig)
    val config: InferenceConfig = api.config

    fun annotate(uri: Uri): PhotoAnnotation {
        // Validate the destination and consent before loading photo bytes.
        var contextOverflow: ApiRequestException? = null
        for ((attemptIndex, maxEdge) in VlmInferencePolicy.maxEdgeAttempts.withIndex()) {
            val image = resizedJpegBase64(uri, maxEdge)
            Log.i(VLM_LOG_TAG, "request attempt=${attemptIndex + 1} thumbnail=${image.width}x${image.height}")
            val parsed = try {
                api.complete(AnnotationRequestFactory.create(config.modelName, image.base64))
            } catch (error: ApiRequestException) {
                if (attemptIndex != 0 || error.status !in setOf(400, 413) || !error.contextOverflow) throw error
                contextOverflow = error
                continue
            }
            return try {
                AnnotationContract.parseVlm(parsed)
            } catch (_: Exception) {
                throw IllegalStateException("API 返回的照片标注不符合约定，原有数据已保留")
            }
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
    fun create(modelName: String, imageBase64: String): JSONObject =
        ExternalApiClient.request(
            modelName,
            JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray()
                .put(JSONObject().put("type", "text").put("text", AnnotationContract.prompt()))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject()
                    .put("url", "data:image/jpeg;base64,$imageBase64"))))),
            "photo_annotation",
            AnnotationContract.schema(),
        )
}
