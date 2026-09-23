package com.jinof.apm

import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExternalApiClientTest {
    @Test fun annotationUsesStandardImageAndStrictSchemaWithoutIdentityNames() {
        val request = AnnotationRequestFactory.create("vision", "IMAGE_BYTES")
        val content = request.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertEquals("data:image/jpeg;base64,IMAGE_BYTES", content.getJSONObject(1).getJSONObject("image_url").getString("url"))
        val format = request.getJSONObject("response_format")
        assertEquals("json_schema", format.getString("type"))
        assertTrue(format.getJSONObject("json_schema").getBoolean("strict"))
        assertEquals("photo_annotation", format.getJSONObject("json_schema").getString("name"))
        assertFalse(request.has("format"))
        assertFalse(request.has("options"))
        assertFalse(request.toString().contains("subject_mentions"))
        assertFalse(request.toString().contains("recognized_subjects"))
        assertStrictSchema(format.getJSONObject("json_schema").getJSONObject("schema"))
        assertStrictSchema(AnnotationContract.schema())
    }

    @Test fun sendsAuthAndUsesConfiguredBasePath() {
        val seen = AtomicReference<String>()
        withServer(200, completion("{\"ok\":true}").toString(), seen = seen) { config ->
            assertTrue(ExternalApiClient(config).complete(JSONObject()).getBoolean("ok"))
        }
        assertTrue(seen.get().startsWith("POST /v1/chat/completions HTTP/1.1"))
        assertTrue(seen.get().contains("Authorization: Bearer test-secret"))
    }

    @Test fun rejectsRefusalTruncationMissingAndMalformedContent() {
        val valid = completion("{\"ok\":true}")
        assertTrue(ExternalApiClient.structuredContent(valid).getBoolean("ok"))
        for (candidate in listOf(
            completion("{\"ok\":true}", "length"),
            completion("{\"ok\":true}", "content_filter"),
            completion("{\"ok\":true}", refusal = "private refusal text"),
            completion("```json\n{}\n```"), completion("{} trailing"), completion("[]"),
            JSONObject().put("choices", JSONArray()),
            JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("content", 42)))),
        )) {
            val error = assertThrows(IllegalStateException::class.java) { ExternalApiClient.structuredContent(candidate) }
            assertFalse(error.message.orEmpty().contains("private refusal text"))
            assertNull(error.cause)
        }
    }

    @Test fun errorsDoNotExposeRawBodiesAndContextErrorsRemainRetryable() {
        for ((status, body) in listOf(401 to "private-key", 503 to "<html>private request text</html>",
            400 to "{\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"private request text\"}}",
            413 to "{\"error\":{\"message\":\"context is too large; private request text\"}}")) {
            withServer(status, body) { config ->
                val error = assertThrows(ApiRequestException::class.java) { ExternalApiClient(config).complete(JSONObject()) }
                assertEquals(status in setOf(400, 413), error.contextOverflow)
                assertFalse(error.message.orEmpty().contains("private"))
                assertNull(error.cause)
            }
        }
    }

    @Test fun redirectsAreRejectedWithoutForwardingCredentials() {
        withServer(302, "", headers = "Location: http://127.0.0.1:1/credential-trap\r\n") { config ->
            val error = assertThrows(ApiRequestException::class.java) { ExternalApiClient(config).complete(JSONObject()) }
            assertEquals(302, error.status)
        }
    }

    @Test fun contextRetryIsLimitedToTheFirstBadRequestOrPayloadTooLargeResponse() {
        for (status in listOf(400, 413)) {
            assertTrue(VlmInferencePolicy.shouldRetryContextOverflow(0, status, "context length exceeded"))
            assertFalse(VlmInferencePolicy.shouldRetryContextOverflow(1, status, "context length exceeded"))
            assertFalse(VlmInferencePolicy.shouldRetryContextOverflow(0, status, "authentication failed"))
        }
        assertFalse(VlmInferencePolicy.shouldRetryContextOverflow(0, 500, "context length exceeded"))
    }

    @Test fun responseSizeIsBoundedBeforeParsing() {
        withServer(200, "x".repeat(ExternalApiClient.MAX_RESPONSE_BYTES + 1)) { config ->
            val error = assertThrows(IllegalStateException::class.java) { ExternalApiClient(config).complete(JSONObject()) }
            assertEquals("API 响应过大", error.message)
        }
    }

    @Test fun plannerUsesTheSameApiAndRejectsUnexpectedFields() {
        val seen = AtomicReference<String>()
        withServer(200, completion("{\"summary\":\"pets\",\"queries\":[\"cat\"],\"match\":\"all\"}").toString(), seen = seen) { config ->
            val plan = ApiSearchPlanner(config).plan("cats")
            assertEquals(listOf("cat"), plan.queries)
        }
        assertTrue(seen.get().contains("photo_search_plan"))
        val plannedRequest = JSONObject(seen.get().substringAfter("\r\n\r\n"))
        assertStrictSchema(plannedRequest.getJSONObject("response_format").getJSONObject("json_schema").getJSONObject("schema"))
        withServer(200, completion("{\"summary\":\"pets\",\"queries\":[\"cat\"],\"match\":\"all\",\"extra\":\"secret\"}").toString()) { config ->
            val error = assertThrows(IllegalStateException::class.java) { ApiSearchPlanner(config).plan("cats") }
            assertEquals("API 返回的搜索计划不符合约定", error.message)
        }
    }

    private fun assertStrictSchema(schema: JSONObject) {
        assertTrue("Schema must declare a type: $schema", schema.has("type"))
        when (schema.getString("type")) {
            "object" -> {
                assertFalse(schema.getBoolean("additionalProperties"))
                val properties = schema.getJSONObject("properties")
                val required = schema.getJSONArray("required")
                assertEquals(properties.keys().asSequence().toSet(), (0 until required.length()).map { required.getString(it) }.toSet())
                properties.keys().forEach { key -> assertStrictSchema(properties.getJSONObject(key)) }
            }
            "array" -> assertStrictSchema(schema.getJSONObject("items"))
            else -> Unit
        }
    }

    private fun completion(content: String, finish: String = "stop", refusal: String? = null): JSONObject =
        JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", finish)
            .put("message", JSONObject().put("role", "assistant").put("content", content).put("refusal", refusal))))

    private fun withServer(status: Int, body: String, headers: String = "", seen: AtomicReference<String> = AtomicReference(), block: (InferenceConfig) -> Unit) {
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            val worker = thread(isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream()
                        val headerBytes = java.io.ByteArrayOutputStream()
                        var tail = ""
                        while (!tail.endsWith("\r\n\r\n")) {
                            val next = input.read()
                            if (next < 0) break
                            headerBytes.write(next)
                            tail = (tail + next.toChar()).takeLast(4)
                        }
                        val header = headerBytes.toString("UTF-8")
                        val count = header.lineSequence().firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                        val content = ByteArray(count)
                        var read = 0
                        while (read < count) { val n = input.read(content, read, count - read); if (n < 0) break; read += n }
                        seen.set(header + String(content, 0, read, Charsets.UTF_8))
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().write("HTTP/1.1 $status Response\r\n${headers}Content-Length: ${bytes.size}\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n".toByteArray())
                        socket.getOutputStream().write(bytes)
                    }
                }
            }
            try { block(InferenceConfig("http://127.0.0.1:${server.localPort}/v1", "vision", true, "test-secret")) }
            finally { worker.join(5000) }
        }
    }
}
