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
            ActivityScenario.launch(SettingsActivity::class.java).use { activity ->
                fillDraft(server)
                assertEquals(0, server.requests.size)
                reveal("test_model_api").performClick()
                rule.waitUntil(5_000) { server.requests.size == 1 }
                rule.onNodeWithTag("test_model_api").assertIsNotEnabled()
                reveal("model_input").assertIsNotEnabled()
                reveal("decrease_api_concurrency").assertIsNotEnabled()
                reveal("increase_api_concurrency").assertIsNotEnabled()
                rule.onNodeWithTag("add_api_profile").assertDoesNotExist()
                reveal("api_editor_back").assertIsNotEnabled()
                activity.recreate()
                reveal("test_model_api").assertIsNotEnabled()
                reveal("api_editor_back").assertIsNotEnabled()
                assertEquals(1, server.requests.size)
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
                rule.onNodeWithText("用途：生成描述、标签、识别图片文字，规划自然语言搜索").performScrollTo().assertIsDisplayed()
            }
        }
    }

    @Test fun namedProfilesRequireExplicitSwitchAndKeepDraftAcrossRotation() = withSettings {
        MockChatConnectionServer().use { home ->
            MockChatConnectionServer().use { away ->
                val store = SettingsStore(context)
                val homeConfig = InferenceConfig(home.endpoint, "home-model", true, "home-test-key", concurrency = 2)
                store.save(homeConfig)
                ActivityScenario.launch(SettingsActivity::class.java).use { activity ->
                    reveal("add_api_profile").performClick()
                    reveal("endpoint_input").assert(SemanticsMatcher.expectValue(
                        androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
                    reveal("api_key_input").assert(SemanticsMatcher.expectValue(
                        androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
                    reveal("remote_consent").assertIsOff()
                    reveal("api_concurrency").assertTextEquals("8")
                    reveal("increase_api_concurrency").assertIsNotEnabled()
                    repeat(7) { reveal("decrease_api_concurrency").performClick() }
                    reveal("api_concurrency").assertTextEquals("1")
                    reveal("decrease_api_concurrency").assertIsNotEnabled()
                    repeat(3) { reveal("increase_api_concurrency").performClick() }
                    reveal("api_concurrency").assertTextEquals("4")
                    reveal("profile_name_input").performTextReplacement("外出")
                    reveal("endpoint_input").performTextReplacement(away.endpoint)
                    reveal("model_input").performTextReplacement("away-model")
                    reveal("api_key_input").performTextReplacement("away-test-key")
                    reveal("remote_consent").performClick()
                    activity.recreate()
                    reveal("api_concurrency").assertTextEquals("4")
                    reveal("profile_name_input").assertTextContains("外出")
                    rule.onRoot().saveRoadmapScreenshot("api-editor.png")
                    reveal("model_input").assertTextContains("away-model")
                    reveal("remote_consent").assertIsOn()
                    reveal("save_settings").performClick()
                    rule.onNodeWithTag("api_profile_editor").assertDoesNotExist()
                    val profile = store.loadProfiles().profiles.single { it.name == "外出" }
                    assertEquals("away-test-key", profile.config.apiKey)
                    assertEquals(4, profile.config.concurrency)
                    assertEquals(homeConfig, store.load())
                    assertEquals(0, home.requests.size + away.requests.size)
                    reveal("activate_profile_${profile.id}").performClick()
                    assertEquals(profile.config, SettingsStore(context).load())
                    reveal("active_api_profile").assertTextEquals("当前使用：外出")
                    rule.onRoot().saveRoadmapScreenshot("api-profiles.png")
                    reveal("edit_profile_${profile.id}").performClick()
                    reveal("test_model_api").performClick()
                    awaitResult("连接成功：away-model")
                    assertEquals(0, home.requests.size)
                    assertEquals(1, away.requests.size)
                    assertTrue(away.requests.single().header.contains("Authorization: Bearer away-test-key"))
                    assertEquals("away-model", JSONObject(away.requests.single().body).getString("model"))
                    reveal("delete_api_profile").assertIsNotEnabled()
                    reveal("api_editor_back").performClick()
                    reveal("activate_profile_default").performClick()
                    reveal("edit_profile_default").performClick()
                    reveal("test_model_api").performClick()
                    awaitResult("连接成功：home-model")
                    assertEquals(1, home.requests.size)
                    assertTrue(home.requests.single().header.contains("Authorization: Bearer home-test-key"))
                    reveal("api_editor_back").performClick()
                    reveal("edit_profile_${profile.id}").performClick()
                    reveal("delete_api_profile").performClick()
                    rule.onNodeWithTag("confirm_delete_profile").performClick()
                    assertEquals(ApiProfiles.single(homeConfig), store.loadProfiles())
                }
                ActivityScenario.launch(SettingsActivity::class.java).use {
                    reveal("active_api_profile").assertTextEquals("当前使用：默认配置")
                }
            }
        }
    }

    @Test fun returningWithUnsavedEditsNeedsExplicitDiscardAndNeverSavesOrSends() = withSettings { saved ->
        val store = SettingsStore(context)
        store.saveProfile("away", "外出", InferenceConfig("https://example.test/v1", "away", false, "away-key"))
        val original = java.io.File(context.noBackupFilesDir, "external-api.enc").readBytes()
        ActivityScenario.launch(SettingsActivity::class.java).use { activity ->
            reveal("edit_profile_default").performClick()
            reveal("model_input").performTextReplacement("unsaved-model")
            activity.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            rule.onNodeWithTag("keep_profile_changes").performClick()
            reveal("model_input").assertTextContains("unsaved-model")
            assertEquals(saved, store.load())
            reveal("api_editor_back").performClick()
            rule.onNodeWithTag("discard_profile_changes").performClick()
            rule.onNodeWithTag("api_profile_editor").assertDoesNotExist()
            assertTrue(original.contentEquals(java.io.File(context.noBackupFilesDir, "external-api.enc").readBytes()))
            reveal("activate_profile_away").performClick()
            assertFalse(store.load().allowRemote)
            assertEquals("away", store.load().modelName)
            assertEquals(saved, store.loadProfiles().profiles.single { it.id == "default" }.config)
            reveal("activate_profile_default").performClick()
            assertEquals(saved, store.load())
            reveal("edit_profile_default").performClick()
            reveal("model_input").assertTextContains(saved.modelName)
        }
    }

    @Test fun listPlacesAddAtTopAndEditActivateAtRightWithoutExposingSecrets() = withSettings { saved ->
        val store = SettingsStore(context)
        repeat(14) { index -> store.saveProfile("list-$index", "外出配置 $index 很长的名字",
            InferenceConfig("https://long-host-$index.example.test/proxy/path/v1", "long-model-name-$index", false, "private-secret-$index")) }
        val before = java.io.File(context.noBackupFilesDir, "external-api.enc").readBytes()
        ActivityScenario.launch(SettingsActivity::class.java).use {
            rule.onNodeWithTag("api_profile_editor").assertDoesNotExist()
            rule.onNodeWithTag("endpoint_input").assertDoesNotExist()
            val add = reveal("add_api_profile").fetchSemanticsNode().boundsInRoot
            val name = rule.onNodeWithTag("api_profile_name_default", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val edit = reveal("edit_profile_default").fetchSemanticsNode().boundsInRoot
            val activate = reveal("activate_profile_default").fetchSemanticsNode().boundsInRoot
            assertTrue(add.bottom < name.top)
            assertTrue(edit.left >= name.right && activate.left >= edit.right)
            reveal("activate_profile_default").assertIsNotEnabled().assertTextContains("已启用")
            rule.onAllNodes(hasText("private-secret", substring = true)).assertCountEquals(0)
            reveal("edit_profile_list-13").assertIsDisplayed()
            reveal("add_api_profile").assertIsDisplayed() // Fixed above the scrollable list.
            reveal("edit_profile_list-13").performClick()
            reveal("endpoint_input").assertTextContains("https://long-host-13.example.test/proxy/path/v1")
            assertEquals(saved, store.load())
            reveal("api_editor_back").performClick()
            assertTrue(before.contentEquals(java.io.File(context.noBackupFilesDir, "external-api.enc").readBytes()))
        }
    }

    @Test fun invalidAddStaysInEditorAndCancelPreservesSavedConfiguration() = withSettings { saved ->
        val file = java.io.File(context.noBackupFilesDir, "external-api.enc")
        val before = file.readBytes()
        ActivityScenario.launch(SettingsActivity::class.java).use {
            reveal("add_api_profile").performClick()
            rule.onNodeWithTag("api_settings_list").assertDoesNotExist()
            reveal("endpoint_input").performTextReplacement("not a URL")
            reveal("model_input").performTextReplacement("draft-model")
            reveal("remote_consent").performClick()
            reveal("save_settings").performClick()
            rule.onNodeWithTag("api_profile_editor").assertExists()
            reveal("api_settings_status").assertTextContains("保存失败", substring = true)
            assertEquals(saved, SettingsStore(context).load())
            assertTrue(before.contentEquals(file.readBytes()))
            reveal("api_editor_back").performClick()
            rule.onNodeWithTag("discard_profile_changes").performClick()
            assertEquals(1, SettingsStore(context).loadProfiles().profiles.size)
            assertTrue(before.contentEquals(file.readBytes()))
        }
    }

    private fun fillDraft(server: MockChatConnectionServer, consent: Boolean = true) {
        reveal("edit_profile_default").performClick()
        reveal("endpoint_input").performTextReplacement(server.endpoint)
        reveal("model_input").performTextReplacement("draft-model")
        reveal("api_key_input").performTextReplacement("draft-test-key")
        if (consent) reveal("remote_consent").performClick()
    }

    private fun reveal(tag: String): SemanticsNodeInteraction {
        val node = rule.onNodeWithTag(tag)
        if (runCatching { node.assertIsDisplayed() }.isSuccess) return node
        if (rule.onAllNodesWithTag("api_profile_editor").fetchSemanticsNodes().isNotEmpty()) node.performScrollTo()
        else rule.onNodeWithTag("api_settings_list").performScrollToNode(hasTestTag(tag))
        return node
    }

    private fun awaitResult(text: String) {
        reveal("model_api_test_status")
        rule.waitUntil(10_000) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("model_api_test_status").assertTextContains(text, substring = true)
    }

    private fun withSettings(block: (InferenceConfig) -> Unit) {
        val store = SettingsStore(context)
        val file = java.io.File(context.noBackupFilesDir, "external-api.enc")
        val original = file.takeIf { it.exists() }?.readBytes()
        val saved = InferenceConfig("http://127.0.0.1:1/v1", "saved-model", false, "saved-key")
        store.save(saved)
        try { block(saved) } finally {
            if (original != null) file.writeBytes(original) else file.delete()
        }
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
                check(gate.await(45, TimeUnit.SECONDS))
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
