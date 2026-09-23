package com.jinof.apm

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal class ApiRequestException(
    val status: Int,
    val contextOverflow: Boolean,
) : IllegalStateException(when {
    status == 401 || status == 403 -> "API 鉴权失败，请检查密钥和服务权限"
    status == 429 -> "API 请求过于频繁，请稍后重试"
    contextOverflow -> "API 上下文容量不足，请调整模型或请求"
    else -> "API 请求失败（HTTP $status）"
})

/** Shared OpenAI-compatible transport. Server bodies and credentials never become UI errors. */
internal class ExternalApiClient(
    candidateConfig: InferenceConfig,
    private val readTimeoutMillis: Int = 180_000,
) {
    val config = EndpointPolicy.validate(candidateConfig)

    init { require(readTimeoutMillis in 1..180_000) }

    fun complete(body: JSONObject): JSONObject {
        val url = URL("${config.endpoint}/chat/completions")
        val connection = (if (EndpointPolicy.isLocalNetwork(config)) {
            url.openConnection(Proxy.NO_PROXY)
        } else {
            url.openConnection()
        }) as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (config.apiKey.isNotEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }
            val request = body.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(request.size)
            connection.outputStream.use { it.write(request) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= MAX_RESPONSE_BYTES) { "API 响应过大" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: byteArrayOf()
            val response = runCatching { parseObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
            if (status !in 200..299) {
                val error = response?.optJSONObject("error")
                val overflow = error?.let {
                    val code = it.optString("code")
                    code in setOf("context_length_exceeded", "context_window_exceeded", "context_limit_exceeded") ||
                        VlmInferencePolicy.isContextOverflow(it.optString("message"))
                } ?: false
                throw ApiRequestException(status, overflow)
            }
            check(response != null) { "API 返回了无效的 JSON 响应" }
            check(!response.has("error")) { "API 返回错误，未生成结果" }
            return structuredContent(response)
        } catch (error: IllegalStateException) {
            throw error
        } catch (_: java.net.SocketTimeoutException) {
            throw IllegalStateException("API 请求超时，请稍后重试")
        } catch (_: Exception) {
            throw IllegalStateException("无法连接 API，请检查网络和设置")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        internal const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

        internal fun structuredContent(response: JSONObject): JSONObject {
            try {
                val choices = response.getJSONArray("choices")
                check(choices.length() == 1) { "API 返回的候选结果数量无效" }
                val choice = choices.getJSONObject(0)
                val message = choice.getJSONObject("message")
                check(message.isNull("refusal") || message.optString("refusal").isEmpty()) { "API 拒绝了此请求" }
                check(choice.getString("finish_reason") == "stop") { "API 返回的内容未完整生成" }
                val content = message.get("content")
                check(content is String && content.isNotBlank()) { "API 返回的内容为空或格式无效" }
                return parseObject(content)
            } catch (error: IllegalStateException) {
                throw error
            } catch (_: Exception) {
                throw IllegalStateException("API 返回的结构化内容无效")
            }
        }

        private fun parseObject(text: String): JSONObject {
            val tokens = JSONTokener(text)
            val value = tokens.nextValue()
            check(value is JSONObject && tokens.nextClean() == '\u0000') { "API 返回的结构化内容无效" }
            return value
        }

        internal fun request(model: String, messages: JSONArray, schemaName: String, schema: JSONObject): JSONObject =
            JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("stream", false)
                .put("response_format", JSONObject()
                    .put("type", "json_schema")
                    .put("json_schema", JSONObject().put("name", schemaName).put("strict", true).put("schema", schema)))
    }
}
