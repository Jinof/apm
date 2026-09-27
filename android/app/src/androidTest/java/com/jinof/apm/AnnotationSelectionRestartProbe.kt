package com.jinof.apm

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Two opt-in phases around an external emulator-only force-stop and launcher start. */
class AnnotationSelectionRestartProbe {
    @Test fun probe() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val phase = InstrumentationRegistry.getArguments().getString("apmSelectionRestartPhase")
        assumeTrue(phase in setOf("seed", "verify"))
        assumeTrue(Build.FINGERPRINT.startsWith("generic") || Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir, "selection-restart-fixture")
        ApmDatabase(context).use { db ->
            if (phase == "seed") {
                check(!fixture.exists())
                val id = MessageDigest.getInstance("SHA-256").digest(UUID.randomUUID().toString().toByteArray())
                    .joinToString("") { "%02x".format(it) }
                db.upsertAccessiblePhotos(listOf(ScannedPhoto(
                    "content://apm.test/$id", -1, id, "$id.png", "image/png", 1, 1, null,
                )))
                db.replaceAnnotationSelection(listOf(id))
                fixture.writeText(id)
                assertEquals(1, db.annotationSelectionCount())
            } else {
                val id = fixture.readText()
                assertEquals(0, db.annotationSelectionCount())
                assertTrue(db.galleryPhotos().any { it.photoId == id })
                assertEquals(8, db.readableDatabase.version)
                listOf("annotation_selection", "media_locations", "photo_assets").forEach {
                    db.writableDatabase.delete(it, "photo_id = ?", arrayOf(id))
                }
                check(fixture.delete())
            }
        }
    }
}
