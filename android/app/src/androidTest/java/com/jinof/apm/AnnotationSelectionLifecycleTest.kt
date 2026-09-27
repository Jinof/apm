package com.jinof.apm

import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID

class AnnotationSelectionLifecycleTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun emulatorOnly() {
        assumeTrue(Build.FINGERPRINT.startsWith("generic") || Build.HARDWARE in setOf("ranchu", "goldfish"))
    }

    @Test fun startupClearsOldSelectionWithoutChangingPersistentData() = withPhoto { db, photo ->
        SettingsStore(context).load()
        db.replaceAnnotationSelection(listOf(photo.photoId))
        val rows = persistentRows(db)
        val settings = settingsBytes()
        val grants = context.contentResolver.persistedUriPermissions.map { it.uri.toString() }.sorted()
        launch().use {
            dismissStory()
            assertCount(0)
            assertEquals(rows, persistentRows(db))
            assertEquals(settings, settingsBytes())
            assertEquals(grants, context.contentResolver.persistedUriPermissions.map { it.uri.toString() }.sorted())
            assertEquals(8, db.readableDatabase.version)
        }
    }

    @Test fun pickerCancelRecreationBackgroundDockAndViewerKeepCurrentSelection() = withPhoto { db, photo ->
        launch().use { scenario ->
            dismissStory()
            scenario.onActivity { it.onPhotosSelected(listOf(Uri.parse(photo.uri))) }
            assertCount(1)
            val selected = selectedRows(db)
            scenario.onActivity { it.onPhotosSelected(emptyList()) }
            assertCount(1)
            assertEquals(selected, selectedRows(db))
            scenario.recreate()
            assertCount(1)
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertCount(1)
            rule.onNodeWithTag("dock_heatmap").performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("dock_album").performClick()
            assertCount(1)
            scenario.onActivity { it.startActivity(PhotoViewerActivity.intent(it, photo.photoId, photo.uri)) }
            rule.waitUntil(5_000) { runCatching { rule.onAllNodesWithTag("photo_viewer").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
            rule.onNodeWithTag("photo_viewer_back").performClick()
            assertCount(1)
            assertEquals(selected, selectedRows(db))
        }
    }

    @Test fun albumBackAndTaskRemovalStartNewEmptySessions() = withPhoto { _, photo ->
        launch().use { scenario ->
            dismissStory()
            scenario.onActivity { it.onPhotosSelected(listOf(Uri.parse(photo.uri))) }
            assertCount(1)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            rule.waitUntil(5_000) { scenario.state == Lifecycle.State.DESTROYED }
        }
        launch().use { scenario ->
            dismissStory()
            assertCount(0)
            scenario.onActivity { it.onPhotosSelected(listOf(Uri.parse(photo.uri))) }
            assertCount(1)
            scenario.onActivity { it.finishAndRemoveTask() }
            rule.waitUntil(5_000) { scenario.state == Lifecycle.State.DESTROYED }
        }
        launch().use { dismissStory(); assertCount(0) }
    }

    @Test fun sessionInitializesOnceAndRejectsClosedSessionImports() = withPhoto { db, photo ->
        val previous = AnnotationSelectionSession()
        previous.initialize(db)
        assertTrue(previous.replaceSelection(db, listOf(photo.photoId)))
        previous.initialize(db)
        assertEquals(1, db.annotationSelectionCount())
        previous.closeSession()
        val next = AnnotationSelectionSession()
        next.initialize(db)
        assertFalse(previous.replaceSelection(db, listOf(photo.photoId)))
        assertEquals(0, db.annotationSelectionCount())
        assertTrue(next.replaceSelection(db, listOf(photo.photoId)))
        assertEquals(1, db.annotationSelectionCount())
        next.closeSession()
    }

    private fun launch() = ActivityScenario.launch<MainActivity>(
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
    )

    private fun dismissStory() {
        rule.onNodeWithTag("startup_story_continue").performClick()
    }

    private fun assertCount(count: Int) {
        rule.waitUntil(10_000) {
            runCatching {
                rule.onNodeWithTag("annotate_selected").assertTextEquals("标注所选 ($count)")
                if (count == 0) rule.onNodeWithTag("annotate_selected").assertIsNotEnabled()
                else rule.onNodeWithTag("annotate_selected").assertIsEnabled()
            }.isSuccess
        }
    }

    private fun selectedRows(db: ApmDatabase): List<String> = db.readableDatabase.rawQuery(
        "SELECT photo_id, selected_at FROM annotation_selection ORDER BY photo_id", null,
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) + ":" + c.getString(1)) } }

    private fun persistentRows(db: ApmDatabase): Map<String, List<String>> {
        val sql = db.readableDatabase
        val tables = sql.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name != 'annotation_selection' ORDER BY name", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        return tables.associateWith { name ->
            sql.rawQuery("SELECT * FROM \"${name.replace("\"", "\"\"")}\"", null).use { c ->
                buildList {
                    while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { i ->
                        when (c.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> "null"
                            Cursor.FIELD_TYPE_BLOB -> c.getBlob(i).joinToString(",")
                            else -> c.getString(i)
                        }
                    })
                }.sorted()
            }
        }
    }

    private fun settingsBytes() = context.noBackupFilesDir.walkTopDown().filter { it.isFile && it.extension == "enc" }
        .associate { it.relativeTo(context.noBackupFilesDir).path to sha(it.readBytes()) }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun withPhoto(block: (ApmDatabase, ScannedPhoto) -> Unit) {
        val resolver = context.contentResolver
        val name = "selection-session-${UUID.randomUUID()}.png"
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/APMSelectionTest")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(name.hashCode() or 0xff000000.toInt())
            resolver.openOutputStream(uri).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, requireNotNull(it)) }
        } finally { bitmap.recycle() }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
        val photo = ScannedPhoto(uri.toString(), ContentUris.parseId(uri), sha(bytes), name, "image/png", bytes.size.toLong(), 1, null)
        ApmDatabase(context).use { db ->
            db.upsertAccessiblePhotos(listOf(photo))
            try { block(db, photo) } finally {
                listOf("annotation_selection", "media_locations", "photo_assets").forEach {
                    db.writableDatabase.delete(it, "photo_id = ?", arrayOf(photo.photoId))
                }
                resolver.delete(uri, null, null)
            }
        }
    }
}
