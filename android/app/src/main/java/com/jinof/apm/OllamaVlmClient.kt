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
import java.net.Proxy
import java.net.URL
import kotlin.math.roundToInt

internal object VlmInferencePolicy {
    const val PRIMARY_MAX_EDGE = 1024
    const val RETRY_MAX_EDGE = 768
    const val CONTEXT_TOKENS = 8192
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
            status == HttpURLConnection.HTTP_BAD_REQUEST &&
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
        markers: List<LocalSubjectMarker>,
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
        val marked = try {
            SubjectMarkerPipeline.draw(bounded, markers)
        } finally {
            if (bounded !== source) bounded.recycle()
        }
        try {
            check(maxOf(marked.width, marked.height) <= maxEdge) {
                "推理缩略图超过 ${maxEdge}px 上限"
            }
            val output = ByteArrayOutputStream()
            if (!marked.compress(Bitmap.CompressFormat.JPEG, VlmInferencePolicy.JPEG_QUALITY, output)) {
                throw IllegalStateException("无法生成只读推理缩略图")
            }
            return EncodedVlmImage(
                base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP),
                width = marked.width,
                height = marked.height,
            )
        } finally {
            marked.recycle()
        }
    }
}

internal class OllamaRequestException(
    val status: Int,
    val serverMessage: String,
) : IllegalStateException("Ollama 请求失败：$serverMessage")

class OllamaVlmClient(
    private val resolver: ContentResolver,
    candidateConfig: InferenceConfig,
) {
    val config: InferenceConfig = EndpointPolicy.validate(candidateConfig)

    fun ensureAvailable() {
        val response = request("GET", "/api/tags", null)
        val models = response.optJSONArray("models") ?: JSONArray()
        val available = buildSet {
            for (index in 0 until models.length()) {
                val item = models.optJSONObject(index) ?: continue
                item.optString("name").takeIf(String::isNotBlank)?.let(::add)
                item.optString("model").takeIf(String::isNotBlank)?.let(::add)
            }
        }
        if (config.modelName !in available) {
            throw IllegalStateException("Ollama 中没有模型 ${config.modelName}，请先在模型主机执行 ollama pull ${config.modelName}")
        }
    }

    fun annotate(uri: Uri, markers: List<LocalSubjectMarker> = emptyList()): PhotoAnnotation {
        // EndpointPolicy validation above intentionally happens before photo bytes are loaded.
        var contextOverflow: OllamaRequestException? = null
        for ((attemptIndex, maxEdge) in VlmInferencePolicy.maxEdgeAttempts.withIndex()) {
            val image = resizedJpegBase64(uri, markers, maxEdge)
            Log.i(
                VLM_LOG_TAG,
                "request attempt=${attemptIndex + 1} thumbnail=${image.width}x${image.height} markers=${markers.size}",
            )
            val payload = AnnotationRequestFactory.create(config.modelName, image.base64, markers)
            val response = try {
                request("POST", "/api/chat", payload)
            } catch (error: OllamaRequestException) {
                val canRetry = VlmInferencePolicy.shouldRetryContextOverflow(
                    attemptIndex = attemptIndex,
                    status = error.status,
                    message = error.serverMessage,
                )
                if (!canRetry) throw error
                contextOverflow = error
                continue
            }
            val content = response.optJSONObject("message")?.optString("content")
                ?.takeIf(String::isNotBlank)
                ?: throw IllegalStateException("Ollama 响应缺少 message.content")
            val parsed = try {
                JSONObject(content)
            } catch (error: Exception) {
                throw IllegalStateException("Ollama 返回的标注不是 JSON 对象", error)
            }
            return try {
                AnnotationContract.parseVlm(parsed, markers)
            } catch (error: Exception) {
                throw IllegalStateException("Ollama 返回的结构化标注不合法：${error.message}", error)
            }
        }
        throw checkNotNull(contextOverflow)
    }

    private fun resizedJpegBase64(
        uri: Uri,
        markers: List<LocalSubjectMarker>,
        maxEdge: Int,
    ): EncodedVlmImage {
        val thumbnail: Bitmap = resolver.loadThumbnail(uri, Size(maxEdge, maxEdge), null)
        return try {
            VlmThumbnailEncoder.encode(thumbnail, markers, maxEdge)
        } finally {
            thumbnail.recycle()
        }
    }

    private fun request(method: String, path: String, body: JSONObject?): JSONObject {
        val url = URL("${config.endpoint}$path")
        val connection = if (EndpointPolicy.isLocalNetwork(config)) {
            url.openConnection(Proxy.NO_PROXY)
        } else {
            url.openConnection()
        } as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 180_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val parsed = try {
                JSONObject(text)
            } catch (error: Exception) {
                throw IllegalStateException("Ollama 返回了非 JSON 响应（HTTP $status）", error)
            }
            if (status !in 200..299 || parsed.has("error")) {
                val message = parsed.optString("error").ifBlank { "HTTP $status" }
                throw OllamaRequestException(status, message)
            }
            return parsed
        } catch (error: IllegalStateException) {
            throw error
        } catch (error: Exception) {
            throw IllegalStateException("无法连接 ${config.endpoint}：${error.message}", error)
        } finally {
            connection.disconnect()
        }
    }
}

private const val VLM_LOG_TAG = "APM.VLM"

object AnnotationRequestFactory {
    fun create(modelName: String, imageBase64: String, markers: List<LocalSubjectMarker>): JSONObject =
        JSONObject()
            .put("model", modelName)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", AnnotationContract.prompt(markers))
                        .put("images", JSONArray().put(imageBase64)),
                ),
            )
            .put("format", AnnotationContract.schema(markers))
            .put("stream", false)
            .put(
                "options",
                JSONObject()
                    .put("temperature", 0)
                    .put("num_ctx", VlmInferencePolicy.CONTEXT_TOKENS),
            )
}
