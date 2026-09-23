package com.jinof.apm

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ApiConnectionSettingsTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun testsUnsavedDraftWithGeneratedImageAndBlocksDuplicateRequests() = withSettings { saved ->
        MockChatConnectionServer(holdResponse = true).use { server ->
            ActivityScenario.launch(SettingsActivity::class.java).use {
                fillDraft(server)
                assertEquals(0, server.requests.size)
                reveal("test_model_api").performClick()
                rule.waitUntil(5_000) { server.requests.size == 1 }
                rule.onNodeWithTag("test_model_api").assertIsNotEnabled()
                reveal("model_input").assertIsNotEnabled()
                server.release()
                awaitResult("连接成功：draft-model")
                assertEquals(saved, SettingsStore(context).load())
                assertEquals(1, server.requests.size)
                val request = server.requests.single()
                assertTrue(request.header.startsWith("POST /v1/chat/completions "))
                assertTrue(request.header.contains("Authorization: Bearer draft-test-key"))
                val payload = JSONObject(request.body)
                assertEquals("draft-model", payload.getString("model"))
                val content = payload.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
                val url = content.getJSONObject(1).getJSONObject("image_url").getString("url")
                assertTrue(url.startsWith("data:image/jpeg;base64,"))
                val bytes = Base64.decode(url.substringAfter(','), Base64.NO_WRAP)
                val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                try { assertEquals(64, image.width); assertEquals(64, image.height) } finally { image.recycle() }
                val format = payload.getJSONObject("response_format").getJSONObject("json_schema")
                assertEquals("photo_annotation", format.getString("name"))
                assertTrue(format.getBoolean("strict"))
                assertFalse(format.getJSONObject("schema").getJSONObject("properties").has("subject_mentions"))
                reveal("model_input").performTextReplacement("edited-model")
                reveal("model_api_test_status").assertTextEquals("")
                assertEquals(saved, SettingsStore(context).load())
            }
        }
    }

    @Test fun reportsAuthenticationAndSchemaFailuresThenCanRetry() = withSettings { saved ->
        MockChatConnectionServer().use { server ->
            ActivityScenario.launch(SettingsActivity::class.java).use {
                fillDraft(server)
                server.status = 401
                reveal("test_model_api").performClick()
                awaitResult("API 鉴权失败")
                rule.onNodeWithTag("model_api_test_status").assertTextContains("测试失败", substring = true)
                assertEquals(0, rule.onAllNodes(hasText("secret-server-body", substring = true)).fetchSemanticsNodes().size)
                server.status = 200
                server.content = "{\"ok\":true}"
                reveal("test_model_api").performClick()
                awaitResult("模型返回的标注不符合要求")
                server.content = MockChatConnectionServer.validAnnotation
                reveal("test_model_api").performClick()
                awaitResult("连接成功：draft-model")
                assertEquals(3, server.requests.size)
                assertEquals(saved, SettingsStore(context).load())
            }
        }
    }

    @Test fun requiresConsentAndRecommendationsOnlyFillTheDraft() = withSettings { saved ->
        MockChatConnectionServer().use { server ->
            ActivityScenario.launch(SettingsActivity::class.java).use {
                fillDraft(server, consent = false)
                reveal("test_model_api").performClick()
                awaitResult("请允许将照片和搜索请求发送到此 API")
                assertEquals(0, server.requests.size)
                reveal("recommend_qwen3.6-35b-a3b").performClick()
                reveal("model_input").assertTextContains("qwen3.6-35b-a3b")
                reveal("endpoint_input").assertTextContains(server.endpoint)
                reveal("model_api_test_status").assertTextEquals("")
                assertEquals(saved, SettingsStore(context).load())
                assertEquals(0, server.requests.size)
                rule.onNodeWithTag("visual_model_recommendations").assertDoesNotExist()
                rule.onNodeWithTag("open_identity_settings").assertDoesNotExist()
                rule.onNodeWithText("功能类别：多模态模型").performScrollTo().assertIsDisplayed()
                rule.onNodeWithText("用途：生成描述、标签、识别图片文字，规划自然语言搜索").assertIsDisplayed()
            }
        }
    }

    private fun fillDraft(server: MockChatConnectionServer, consent: Boolean = true) {
        reveal("endpoint_input").performTextReplacement(server.endpoint)
        reveal("model_input").performTextReplacement("draft-model")
        reveal("api_key_input").performTextReplacement("draft-test-key")
        if (consent) reveal("remote_consent").performClick()
    }

    private fun reveal(tag: String): SemanticsNodeInteraction {
        return rule.onNodeWithTag(tag).performScrollTo()
    }

    private fun awaitResult(text: String) {
        reveal("model_api_test_status")
        rule.waitUntil(10_000) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("model_api_test_status").assertTextContains(text, substring = true)
    }

    private fun withSettings(block: (InferenceConfig) -> Unit) {
        val store = SettingsStore(context)
        val original = store.load()
        val saved = InferenceConfig("http://127.0.0.1:1/v1", "saved-model", false, "saved-key")
        store.save(saved)
        try { block(saved) } finally { store.save(original) }
    }
}

private class MockChatConnectionServer(holdResponse: Boolean = false) : Closeable {
    data class Request(val header: String, val body: String)
    private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val gate = CountDownLatch(if (holdResponse) 1 else 0)
    private val failure = AtomicReference<Throwable?>()
    val endpoint = "http://127.0.0.1:${socket.localPort}/v1"
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
    @Volatile var status = 200
    @Volatile var content = validAnnotation
    private val worker = thread(name = "apm-connection-test-server") {
        try {
            while (!socket.isClosed) socket.accept().use { connection ->
                connection.soTimeout = 5_000
                val input = connection.getInputStream()
                val header = StringBuilder()
                while (!header.endsWith("\r\n\r\n")) {
                    val next = input.read(); check(next >= 0); header.append(next.toChar())
                    check(header.length < 16_384)
                }
                val length = header.lines().first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                val body = ByteArray(length)
                var read = 0
                while (read < length) { val n = input.read(body, read, length - read); check(n > 0); read += n }
                requests += Request(header.toString(), String(body, Charsets.UTF_8))
                check(gate.await(15, TimeUnit.SECONDS))
                val response = if (status != 200) "secret-server-body" else JSONObject().put("choices", JSONArray()
                    .put(JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("content", content)))).toString()
                val bytes = response.toByteArray(Charsets.UTF_8)
                connection.getOutputStream().apply {
                    write("HTTP/1.1 $status Mock\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes); flush()
                }
            }
        } catch (error: SocketException) { if (!socket.isClosed) failure.set(error) }
        catch (error: Throwable) { failure.set(error) }
    }
    fun release() = gate.countDown()
    override fun close() {
        release(); socket.close(); worker.join(5_000)
        check(!worker.isAlive)
        failure.get()?.let { throw AssertionError("Mock API failed", it) }
    }
    companion object {
        val validAnnotation = """{"caption":"蓝色方块","tags":["蓝色"],"visible_text":"",
            "facets":{"daylight":"不确定","sky":[],"objects":[],"people":[],"actions":[],"scenes":[],"weather":[]}}"""
    }
}
