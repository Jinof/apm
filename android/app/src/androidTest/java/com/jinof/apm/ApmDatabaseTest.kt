package com.jinof.apm

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class ApmDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val names = mutableListOf<String>()

    @After
    fun cleanup() {
        names.forEach(context::deleteDatabase)
    }

    @Test
    fun selectedAnnotationScopeIsExactAndSelectionImportPreservesOtherPhotos() {
        val name = databaseName("annotation-selection")
        val database = ApmDatabase(context, name)
        try {
            val original = (1..3).map(::scannedPhoto)
            database.replaceVisibleSnapshot(original)
            val imported = scannedPhoto(4).copy(uri = "content://media/picker/media/4")
            database.upsertAccessiblePhotos(listOf(imported))

            database.replaceAnnotationSelection(listOf(original[0].photoId, imported.photoId))

            assertEquals(4, database.accessibleCount())
            assertEquals(2, database.annotationSelectionCount())
            assertEquals(
                setOf(original[0].photoId, imported.photoId),
                database.pendingSelectedPhotos("qwen3-vl:4b", AnnotationContract.PROMPT_VERSION)
                    .map(PendingPhoto::photoId)
                    .toSet(),
            )

            database.replaceAnnotationSelection(listOf(original[2].photoId))

            assertEquals(1, database.annotationSelectionCount())
            assertEquals(
                listOf(original[2].photoId),
                database.pendingSelectedPhotos("qwen3-vl:4b", AnnotationContract.PROMPT_VERSION)
                    .map(PendingPhoto::photoId),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun legacyProviderAnnotationRemainsReadableAndDoesNotSkipExternalApiAnnotation() {
        val database = ApmDatabase(context, databaseName("annotation-provider"))
        try {
            val photo = scannedPhoto(42)
            val model = "same-model-name"
            database.upsertAccessiblePhotos(listOf(photo))
            database.replaceAnnotationSelection(listOf(photo.photoId))
            val oldId = database.insertAnnotation(photo.photoId, annotation("旧服务标注"), model, AnnotationContract.PROMPT_VERSION)
            database.writableDatabase.execSQL(
                "UPDATE photo_annotations SET provider = 'ollama' WHERE annotation_id = ?",
                arrayOf(oldId),
            )
            assertEquals(listOf(photo.photoId), database.pendingAllPhotos(model, AnnotationContract.PROMPT_VERSION).map { it.photoId })
            assertEquals(listOf(photo.photoId), database.pendingSelectedPhotos(model, AnnotationContract.PROMPT_VERSION).map { it.photoId })
            assertEquals("旧服务标注", database.galleryPhotos().single().annotation?.caption)

            val newId = database.insertAnnotation(photo.photoId, annotation("外部 API 标注"), model, AnnotationContract.PROMPT_VERSION)
            assertTrue(newId > oldId)
            assertTrue(database.pendingAllPhotos(model, AnnotationContract.PROMPT_VERSION).isEmpty())
            assertTrue(database.pendingSelectedPhotos(model, AnnotationContract.PROMPT_VERSION).isEmpty())
            database.readableDatabase.rawQuery(
                "SELECT annotation_id, provider, caption FROM photo_annotations WHERE photo_id = ? ORDER BY annotation_id",
                arrayOf(photo.photoId),
            ).use { cursor ->
                assertEquals(2, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals(oldId, cursor.getLong(0))
                assertEquals("ollama", cursor.getString(1))
                assertEquals("旧服务标注", cursor.getString(2))
                assertTrue(cursor.moveToNext())
                assertEquals(newId, cursor.getLong(0))
                assertEquals("openai-compatible", cursor.getString(1))
                assertEquals("外部 API 标注", cursor.getString(2))
            }
            assertEquals("外部 API 标注", database.galleryPhotos().single().annotation?.caption)
        } finally {
            database.close()
        }
    }

    @Test
    fun fullAnnotationScopeHasNoTwentyPhotoBatchCap() {
        val name = databaseName("annotation-all")
        val database = ApmDatabase(context, name)
        try {
            database.replaceVisibleSnapshot((1..25).map(::scannedPhoto))

            val pending = database.pendingAllPhotos("qwen3-vl:4b", AnnotationContract.PROMPT_VERSION)

            assertEquals(25, pending.size)
            assertEquals((1..25).map { scannedPhoto(it).photoId }.toSet(), pending.map(PendingPhoto::photoId).toSet())
        } finally {
            database.close()
        }
    }

    @Test
    fun photoWallIncludesAllDistinctAccessiblePhotosWithoutAnnotationOrSearchCap() {
        val name = databaseName("photo-wall-all")
        val database = ApmDatabase(context, name)
        try {
            val visible = (1..120).map(::scannedPhoto)
            val inaccessible = scannedPhoto(121)
            database.replaceVisibleSnapshot(visible + inaccessible)
            database.insertAnnotation(
                photoId = visible.first().photoId,
                annotation = annotation("旧标注"),
                modelName = "qwen3-vl:4b",
                promptVersion = AnnotationContract.PROMPT_VERSION,
            )
            database.insertAnnotation(
                photoId = visible.first().photoId,
                annotation = annotation("最新标注"),
                modelName = "qwen3-vl:4b",
                promptVersion = AnnotationContract.PROMPT_VERSION,
            )
            database.insertAnnotation(
                photoId = inaccessible.photoId,
                annotation = annotation("不可访问标注"),
                modelName = "qwen3-vl:4b",
                promptVersion = AnnotationContract.PROMPT_VERSION,
            )
            val duplicateLocation = visible.first().copy(
                uri = "content://media/external/images/media/10001",
                mediaStoreId = 10_001,
                displayName = "duplicate-location.jpg",
            )
            database.replaceVisibleSnapshot(visible + duplicateLocation)

            val wall = database.galleryPhotos()

            assertEquals(120, wall.size)
            assertEquals(120, wall.map(GalleryPhotoCard::photoId).distinct().size)
            assertEquals(119, wall.count { it.annotation == null })
            assertEquals("最新标注", wall.single { it.photoId == visible.first().photoId }.annotation?.caption)
            assertFalse(wall.any { it.photoId == inaccessible.photoId })
        } finally {
            database.close()
        }
    }

    @Test
    fun vlmInferenceUsesBoundedJpegAndRecognizesContextOverflow() {
        val source = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
        try {
            val primary = VlmThumbnailEncoder.encode(
                source = source,
                maxEdge = VlmInferencePolicy.PRIMARY_MAX_EDGE,
            )
            assertEquals(1024, primary.width)
            assertEquals(512, primary.height)
            val primaryBytes = Base64.decode(primary.base64, Base64.NO_WRAP)
            val primaryBitmap = BitmapFactory.decodeByteArray(primaryBytes, 0, primaryBytes.size)
            try {
                assertEquals(1024, primaryBitmap.width)
                assertEquals(512, primaryBitmap.height)
            } finally {
                primaryBitmap.recycle()
            }

            val retry = VlmThumbnailEncoder.encode(
                source = source,
                maxEdge = VlmInferencePolicy.RETRY_MAX_EDGE,
            )
            assertEquals(768, retry.width)
            assertEquals(384, retry.height)
        } finally {
            source.recycle()
        }

        val overflowMessage = "request (4459 tokens) exceeds the available context size (4096 tokens)"
        assertTrue(VlmInferencePolicy.shouldRetryContextOverflow(0, 400, overflowMessage))
        assertFalse(VlmInferencePolicy.shouldRetryContextOverflow(1, 400, overflowMessage))
        assertFalse(VlmInferencePolicy.shouldRetryContextOverflow(0, 500, overflowMessage))
        assertFalse(VlmInferencePolicy.isContextOverflow("connection refused"))
    }

    private fun collectMaxLengths(value: Any?, output: MutableList<Int>) {
        when (value) {
            is JSONObject -> {
                if (value.has("maxLength")) output += value.getInt("maxLength")
                value.keys().forEach { key -> collectMaxLengths(value.opt(key), output) }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) collectMaxLengths(value.opt(index), output)
            }
        }
    }

    @Test
    fun preMarkerAnnotationKeepsItsStoredLabelsAndProvenance() {
        val name = databaseName("legacy-labels")
        val photo = scannedPhoto(106)
        lateinit var before: Map<String, List<String>>
        ApmDatabase(context, name).use { database ->
            database.replaceVisibleSnapshot(listOf(photo))
            val id = database.insertAnnotation(photo.photoId, annotation("旧标签在室内"), "old-model", "photo-annotation-zh-v3")
            database.writableDatabase.execSQL(
                "UPDATE photo_annotations SET recognized_subjects_json = ?, provider = 'ollama' WHERE annotation_id = ?",
                arrayOf("""[{"name":"旧标签","kind":"人物"}]""", id),
            )
            before = storedRows(database.readableDatabase)
        }
        ApmDatabase(context, name).use { database ->
            val expected = listOf(RecognizedSubject("旧标签", "人物"))
            assertEquals(expected, database.galleryPhotos().single().annotation!!.recognizedSubjects)
            assertEquals(expected, database.search("旧标签").single().annotation.recognizedSubjects)
            assertEquals(before, storedRows(database.readableDatabase))
        }
    }

    @Test
    fun openingLegacyCatalogPreservesEveryStoredRowAndRendersOldLabels() {
        for (version in listOf(7, 8)) {
            val name = databaseName("legacy-$version")
            val photo = scannedPhoto(105)
            lateinit var before: Map<String, List<String>>
            ApmDatabase(context, name).use { database ->
                database.replaceVisibleSnapshot(listOf(photo))
                database.insertAnnotation(photo.photoId, annotation("P1和PET1在室内").copy(
                    subjectMentions = listOf(SubjectMention("P1", "人物", "P1站立", emptyList()),
                        SubjectMention("PET1", "宠物", "PET1坐着", emptyList())),
                ), "legacy-model", "photo-annotation-zh-v6-grammar-safe")
                val db = database.writableDatabase
                val personModel = "opencv-sface-2021dec@0ba9fbfa01b5"
                val petModel = "litert-mobilenet-v3-small-float32-imagenet"
                db.execSQL("INSERT INTO person_identities VALUES (1,'历史人物','created','updated')")
                db.execSQL("INSERT INTO pet_identities VALUES (1,'历史宠物','created','updated')")
                db.execSQL("INSERT INTO face_templates VALUES (1,1,X'0000803F',1,?,?,'{}','created')", arrayOf(personModel, photo.photoId))
                db.execSQL("INSERT INTO pet_templates VALUES (1,1,'dog',X'0000803F',1,?,?,'{}','created')", arrayOf(petModel, photo.photoId))
                db.execSQL("INSERT INTO photo_face_index VALUES (?,?,'detector',1,'indexed')", arrayOf(photo.photoId, personModel))
                db.execSQL("INSERT INTO photo_pet_index VALUES (?,?,'detector',1,'indexed')", arrayOf(photo.photoId, petModel))
                db.execSQL("""INSERT INTO photo_faces
                    (face_id,photo_id,face_index,embedding,embedding_dimension,bounding_box_json,detector_score,
                     model_name,pipeline_version,match_state,matched_identity_id,similarity,threshold,indexed_at)
                    VALUES (1,?,0,X'0000803F',1,'{}',1,?,'pipeline','matched',1,1,0.5,'indexed')""", arrayOf(photo.photoId, personModel))
                db.execSQL("""INSERT INTO photo_pets
                    (pet_id,photo_id,pet_index,species,embedding,embedding_dimension,bounding_box_json,detector_score,
                     model_name,pipeline_version,match_state,matched_identity_id,similarity,threshold,indexed_at)
                    VALUES (1,?,0,'dog',X'0000803F',1,'{}',1,?,'pipeline','matched',1,1,0.9,'indexed')""", arrayOf(photo.photoId, petModel))
                db.execSQL("INSERT INTO photo_visual_features VALUES (?,X'0000803F',1,'legacy-model','legacy-pipeline','indexed')", arrayOf(photo.photoId))
                db.execSQL("INSERT INTO photo_general_subjects VALUES (1,?,0,'car',X'0000803F',1,'{}',1,'detector','model','pipeline','indexed')", arrayOf(photo.photoId))
                if (version == 7) {
                    db.execSQL("ALTER TABLE photo_visual_features ADD COLUMN patch_grid BLOB NOT NULL DEFAULT X'00'")
                    db.execSQL("ALTER TABLE photo_visual_features ADD COLUMN grid_size INTEGER NOT NULL DEFAULT 4")
                }
                before = storedRows(db)
                db.version = version
            }
            ApmDatabase(context, name).use { database ->
                assertEquals(8, database.readableDatabase.version)
                assertEquals("历史人物和历史宠物在室内", database.galleryPhotos().single().annotation!!.caption)
                assertEquals(photo.photoId, database.search("历史人物").single().photoId)
                assertEquals(photo.photoId, database.search("历史宠物").single().photoId)
                assertEquals(before, storedRows(database.readableDatabase))
            }
        }
    }

    private fun storedRows(db: SQLiteDatabase): Map<String, List<String>> {
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return tables.associateWith { table ->
            db.rawQuery("SELECT * FROM $table", null).use { cursor ->
                val columns = cursor.columnNames.indices.filter { cursor.columnNames[it] !in listOf("patch_grid", "grid_size") }
                buildList {
                    while (cursor.moveToNext()) add(columns.joinToString("|") { index ->
                        when (cursor.getType(index)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> "NULL"
                            android.database.Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).joinToString("") { "%02x".format(it) }
                            else -> cursor.getString(index)
                        }
                    })
                }.sorted()
            }
        }
    }

    private fun jsonStrings(array: JSONArray): List<String> =
        (0 until array.length()).map(array::getString)

    private fun addPhoto(database: ApmDatabase, photoId: String, uri: String, displayName: String) {
        database.replaceVisibleSnapshot(
            existingPhotos(database) + ScannedPhoto(
                uri = uri,
                mediaStoreId = photoId.first().code.toLong(),
                photoId = photoId,
                displayName = displayName,
                mediaType = "image/jpeg",
                byteSize = 100,
                modifiedSeconds = 1,
                dateTakenMillis = 1,
            ),
        )
    }

    private fun existingPhotos(database: ApmDatabase): List<ScannedPhoto> = database.readableDatabase.rawQuery(
        """
        SELECT uri, media_store_id, photo_id, display_name, byte_size, modified_seconds, date_taken_millis
        FROM media_locations WHERE accessible = 1
        """.trimIndent(),
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    ScannedPhoto(
                        uri = cursor.getString(0),
                        mediaStoreId = cursor.getLong(1),
                        photoId = cursor.getString(2),
                        displayName = cursor.getString(3),
                        mediaType = "image/jpeg",
                        byteSize = cursor.getLong(4),
                        modifiedSeconds = cursor.getLong(5),
                        dateTakenMillis = if (cursor.isNull(6)) null else cursor.getLong(6),
                    ),
                )
            }
        }
    }

    private fun addAnnotation(database: ApmDatabase, photoId: String) {
        database.insertAnnotation(
            photoId = photoId,
            annotation = annotation("一张人物照片"),
            modelName = "qwen3-vl:4b",
            promptVersion = AnnotationContract.PROMPT_VERSION,
        )
    }

    private fun annotation(caption: String) = PhotoAnnotation(
        caption = caption,
        tags = listOf("人物"),
        visibleText = "",
        facets = PhotoFacets("室内", emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
    )

    private fun scannedPhoto(index: Int) = ScannedPhoto(
        uri = "content://media/external/images/media/$index",
        mediaStoreId = index.toLong(),
        photoId = index.toString(16).padStart(64, '0'),
        displayName = "photo-$index.jpg",
        mediaType = "image/jpeg",
        byteSize = 100L + index,
        modifiedSeconds = index.toLong(),
        dateTakenMillis = index.toLong() * 1_000,
    )

    private fun facetsJson(): JSONObject = JSONObject()
        .put("daylight", "室内")
        .put("sky", JSONArray())
        .put("objects", JSONArray())
        .put("people", JSONArray())
        .put("actions", JSONArray())
        .put("scenes", JSONArray())
        .put("weather", JSONArray())

    private fun databaseName(suffix: String): String = "apm-$suffix-${System.nanoTime()}.sqlite3".also(names::add)
}
