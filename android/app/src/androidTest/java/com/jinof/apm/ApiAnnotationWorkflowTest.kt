package com.jinof.apm

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.security.MessageDigest
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Opt-in UI integration test: only the generated photo rows are removed. */
class ApiAnnotationWorkflowTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test
    fun selectedAnnotationUsesOnlyChatApiAndFailureKeepsPreviousAnnotation() {
        assumeTrue(
            "Run explicitly on an emulator with -e apmApiWorkflowEmulator true",
            InstrumentationRegistry.getArguments().getString("apmApiWorkflowEmulator") == "true" &&
                (Build.FINGERPRINT.startsWith("generic") || Build.HARDWARE in setOf("ranchu", "goldfish")),
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val model = "mock-vision-$id"
        val settings = SavedApiSettings(context)
        val database = ApmDatabase(context)
        val oldSelection = database.readableDatabase.rawQuery(
            "SELECT photo_id, selected_at FROM annotation_selection", null,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) } }
        var photo: ScannedPhoto? = null
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            photo = createPhoto(context, id)
            database.upsertAccessiblePhotos(listOf(photo))
            database.replaceAnnotationSelection(listOf(photo.photoId))
            MockChatApi().use { server ->
                val config = InferenceConfig(server.endpoint, model, true, server.apiKey)
                SettingsStore(context).save(config)
                scenario = ActivityScenario.launch(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
                composeRule.onNodeWithTag("startup_story_continue").performClick()
                waitUntilAnnotationEnabled()
                composeRule.onNodeWithTag("annotate_selected").performClick()
                val photoId = photo.photoId
                composeRule.waitUntil(30_000) { annotationRows(database, photoId).size == 1 }
                waitUntilAnnotationEnabled()
                val beforeFailure = annotationRows(database, photoId)
                assertEquals("一个人站在室内", beforeFailure.single()["caption"])
                assertEquals(model, beforeFailure.single()["model_name"])
                assertEquals("[]", beforeFailure.single()["recognized_subjects_json"])
                assertEquals(listOf("/v1/chat/completions"), server.requests.map { it.path })
                val request = JSONObject(server.requests.single().body)
                assertEquals(model, request.getString("model"))
                val content = request.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
                assertFalse(content.getJSONObject(0).getString("text").contains("P1"))
                assertTrue(content.getJSONObject(1).getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,"))
                assertEquals("json_schema", request.getJSONObject("response_format").getString("type"))

                // A different model makes the existing photo pending without deleting its annotation.
                server.status = 401
                SettingsStore(context).save(config.copy(modelName = "$model-retry"))
                composeRule.onNodeWithTag("annotate_selected").performClick()
                composeRule.waitUntil(30_000) { server.requests.size == 2 }
                waitUntilAnnotationEnabled()
                assertEquals(beforeFailure, annotationRows(database, photoId))
                assertEquals(listOf("/v1/chat/completions", "/v1/chat/completions"), server.requests.map { it.path })
            }
        } finally {
            scenario?.close()
            try {
                val sql = database.writableDatabase
                sql.beginTransaction()
                try {
                    photo?.let { own ->
                        listOf("annotation_selection", "photo_annotations", "photo_faces", "photo_face_index", "media_locations")
                            .forEach { sql.delete(it, "photo_id = ?", arrayOf(own.photoId)) }
                        sql.delete("photo_assets", "photo_id = ?", arrayOf(own.photoId))
                    }
                    oldSelection.forEach { (photoId, selectedAt) ->
                        sql.insertWithOnConflict("annotation_selection", null, ContentValues().apply {
                            put("photo_id", photoId)
                            put("selected_at", selectedAt)
                        }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
                    }
                    sql.setTransactionSuccessful()
                } finally { sql.endTransaction() }
                photo?.let { context.contentResolver.delete(android.net.Uri.parse(it.uri), null, null) }
            } finally {
                database.close()
                settings.restore()
            }
        }
    }

    private fun waitUntilAnnotationEnabled() {
        composeRule.waitUntil(30_000) {
            runCatching { composeRule.onNodeWithTag("annotate_selected").assertIsEnabled() }.isSuccess
        }
    }

    private fun annotationRows(database: ApmDatabase, photoId: String): List<Map<String, String?>> =
        database.readableDatabase.rawQuery("SELECT * FROM photo_annotations WHERE photo_id = ? ORDER BY annotation_id", arrayOf(photoId)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.columnNames.indices.associate { index ->
                cursor.getColumnName(index) to if (cursor.isNull(index)) null else cursor.getString(index)
            }) }
        }

    private fun createPhoto(context: Context, id: String): ScannedPhoto {
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "api-workflow-$id.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/APMApiWorkflowTest")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        try {
            val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.rgb(70, 130, 170))
                resolver.openOutputStream(uri).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, requireNotNull(it))) }
            } finally { bitmap.recycle() }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
            return ScannedPhoto(uri.toString(), ContentUris.parseId(uri), digest, "api-workflow-$id.png", "image/png", 100, 1, Instant.now().toEpochMilli())
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    /** Restores encrypted bytes and legacy preferences exactly, including an initially missing file. */
    private class SavedApiSettings(private val context: Context) {
        private val files = listOf("external-api.enc", "visual-service.enc").associate { name ->
            val file = File(context.noBackupFilesDir, name)
            file to if (file.exists()) file.readBytes() else null
        }
        private val preferences = context.getSharedPreferences("inference", Context.MODE_PRIVATE)
        private val values = preferences.all.toMap()
        fun restore() {
            files.forEach { (file, bytes) -> if (bytes == null) file.delete() else file.writeBytes(bytes) }
            val editor = preferences.edit().clear()
            values.forEach { (name, value) -> when (value) {
                is String -> editor.putString(name, value)
                is Boolean -> editor.putBoolean(name, value)
                is Int -> editor.putInt(name, value)
                is Long -> editor.putLong(name, value)
                is Float -> editor.putFloat(name, value)
                is Set<*> -> editor.putStringSet(name, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported preference type")
            } }
            check(editor.commit())
        }
    }

    private class MockChatApi : Closeable {
        data class Request(val path: String, val body: String)
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val failure = AtomicReference<Throwable?>()
        val origin = "http://127.0.0.1:${server.localPort}"
        val endpoint = "$origin/v1"
        val apiKey = "mock-chat-api-test-key"
        val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
        @Volatile var status = 200
        private val worker = thread(name = "apm-chat-api-workflow") {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val input = socket.getInputStream()
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) {
                        val next = input.read()
                        check(next >= 0 && header.length < 16_384)
                        header.append(next.toChar())
                    }
                    val lines = header.toString().split("\r\n")
                    val path = lines.first().split(' ')[1]
                    val length = lines.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                    check(length in 0..2_097_152)
                    val bytes = ByteArray(length)
                    var received = 0
                    while (received < length) {
                        val count = input.read(bytes, received, length - received)
                        check(count > 0)
                        received += count
                    }
                    requests += Request(path, String(bytes, Charsets.UTF_8))
                    check(path != "/v1/chat/completions" || lines.any { it.equals("Authorization: Bearer $apiKey", true) })
                    val responseStatus = if (path == "/v1/chat/completions") status else 503
                    val body = if (responseStatus == 200) response() else JSONObject().put("error", JSONObject().put("code", "invalid_api_key")).toString()
                    val output = body.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $responseStatus Mock\r\nContent-Type: application/json\r\nContent-Length: ${output.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(output)
                        flush()
                    }
                }
            } catch (error: Throwable) {
                if (error !is SocketException || !server.isClosed) failure.set(error)
            }
        }
        private fun response(): String {
            val annotation = JSONObject()
                .put("caption", "一个人站在室内")
                .put("tags", JSONArray().put("人物"))
                .put("visible_text", "")
                .put("facets", JSONObject().put("daylight", "室内")
                    .put("sky", JSONArray()).put("objects", JSONArray())
                    .put("people", JSONArray().put(JSONObject().put("presentation", "不确定").put("count", 1)))
                    .put("actions", JSONArray()).put("scenes", JSONArray()).put("weather", JSONArray()))
            return JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                .put("message", JSONObject().put("role", "assistant").put("content", annotation.toString())))).toString()
        }
        override fun close() {
            server.close()
            worker.join(3_000)
            check(!worker.isAlive)
            failure.get()?.let { throw AssertionError("Mock Chat API failed", it) }
        }
    }
}
