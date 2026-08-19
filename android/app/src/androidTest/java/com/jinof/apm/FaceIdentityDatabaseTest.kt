package com.jinof.apm

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class FaceIdentityDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val names = mutableListOf<String>()

    @After
    fun cleanup() {
        names.forEach(context::deleteDatabase)
    }

    @Test
    fun matchedIdentityIsDynamicSearchableAndUnknownAddsNoName() {
        val name = databaseName("matching")
        val database = ApmDatabase(context, name)
        try {
            val firstId = "a".repeat(64)
            val secondId = "b".repeat(64)
            addPhoto(database, firstId, "content://apm.test/first", "first.jpg")
            addPhoto(database, secondId, "content://apm.test/second", "second.jpg")
            addAnnotation(database, firstId)
            addAnnotation(database, secondId)

            val templateSample = sample(floatArrayOf(1f, 0f))
            database.registerFaceTemplate(
                name = "小明",
                sample = templateSample,
                modelName = LocalFaceEngine.EMBEDDING_MODEL_NAME,
                sourcePhotoId = firstId,
            )
            val templates = database.faceTemplates(LocalFaceEngine.EMBEDDING_MODEL_NAME)
            val matchedSample = sample(floatArrayOf(0.82f, sqrt(1f - 0.82f * 0.82f)))
            val unknownSample = sample(floatArrayOf(0.54f, sqrt(1f - 0.54f * 0.54f)))
            database.replaceFaceObservations(
                firstId,
                listOf(matchedSample),
                listOf(FaceMatcher.decide(matchedSample.embedding, templates)),
                LocalFaceEngine.EMBEDDING_MODEL_NAME,
                LocalFaceEngine.DETECTOR_NAME,
            )
            database.replaceFaceObservations(
                secondId,
                listOf(unknownSample),
                listOf(FaceMatcher.decide(unknownSample.embedding, templates)),
                LocalFaceEngine.EMBEDDING_MODEL_NAME,
                LocalFaceEngine.DETECTOR_NAME,
            )

            val results = database.search("小明")

            assertEquals(listOf(firstId), results.map(PhotoCard::photoId))
            assertEquals(listOf(RecognizedSubject("小明", "人物")), database.matchedPeople(firstId))
            assertTrue(database.matchedPeople(secondId).isEmpty())
            assertEquals(listOf("小明"), results.single().annotation.recognizedSubjects.map { it.name })

            val identity = database.identitySummaries().single()
            database.deleteIdentity(identity.id, LocalFaceEngine.EMBEDDING_MODEL_NAME)
            assertTrue(database.search("小明").isEmpty())
            assertTrue(database.matchedPeople(firstId).isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun versionTwoMigrationRemovesLegacyVlmPersonNames() {
        val name = databaseName("migration")
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL(
                """
                CREATE TABLE photo_assets (
                    photo_id TEXT PRIMARY KEY,
                    media_type TEXT NOT NULL,
                    byte_size INTEGER NOT NULL,
                    discovered_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            old.execSQL(
                """
                CREATE TABLE photo_annotations (
                    annotation_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    photo_id TEXT NOT NULL,
                    caption TEXT NOT NULL,
                    tags_json TEXT NOT NULL,
                    facets_json TEXT NOT NULL,
                    visible_text TEXT NOT NULL,
                    recognized_subjects_json TEXT NOT NULL DEFAULT '[]',
                    provider TEXT NOT NULL,
                    model_name TEXT NOT NULL,
                    prompt_version TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    search_text TEXT NOT NULL
                )
                """.trimIndent(),
            )
            old.execSQL(
                """
                INSERT INTO photo_annotations (
                    photo_id, caption, tags_json, facets_json, visible_text,
                    recognized_subjects_json, provider, model_name, prompt_version, created_at, search_text
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(
                    "c".repeat(64),
                    "一张人物照片",
                    "[]",
                    facetsJson().toString(),
                    "",
                    JSONArray()
                        .put(JSONObject().put("name", "小明").put("kind", "人物"))
                        .put(JSONObject().put("name", "旺财").put("kind", "宠物"))
                        .toString(),
                    "ollama",
                    "qwen3-vl:4b",
                    "photo-annotation-zh-v3",
                    "2026-08-13T00:00:00Z",
                    "一张人物照片 小明 旺财",
                ),
            )
            old.version = 2
        }

        val upgraded = ApmDatabase(context, name)
        try {
            upgraded.readableDatabase.rawQuery(
                "SELECT recognized_subjects_json, search_text FROM photo_annotations",
                null,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("[]", cursor.getString(0))
                assertFalse(cursor.getString(1).contains("小明"))
                assertFalse(cursor.getString(1).contains("旺财"))
            }
            upgraded.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM person_identities",
                null,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            upgraded.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM annotation_selection",
                null,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        } finally {
            upgraded.close()
        }
    }

    @Test
    fun versionFourMigrationAddsFacePipelineProvenance() {
        val name = databaseName("face-pipeline-migration")
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE photo_faces (face_id INTEGER PRIMARY KEY)")
            old.version = 4
        }

        val upgraded = ApmDatabase(context, name)
        try {
            val columns = upgraded.readableDatabase.rawQuery(
                "PRAGMA table_info(photo_faces)",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(1))
                }
            }
            assertTrue(columns.contains("pipeline_version"))
        } finally {
            upgraded.close()
        }
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
    fun androidVlmContractUsesAnonymousMarkersAndRejectsIdentityFields() {
        assertEquals("photo-annotation-zh-v6-grammar-safe", AnnotationContract.PROMPT_VERSION)
        val markers = listOf(
            LocalSubjectMarker(
                marker = "P1",
                kind = LocalIdentityKind.PERSON,
                observationIndex = 0,
                box = FaceBox(0.1f, 0.1f, 0.4f, 0.5f),
                matchedIdentityId = 1,
                matchedName = "小明",
            ),
            LocalSubjectMarker(
                marker = "PET1",
                kind = LocalIdentityKind.PET,
                observationIndex = 0,
                box = FaceBox(0.5f, 0.2f, 0.9f, 0.8f),
                matchedIdentityId = 2,
                matchedName = "旺财",
            ),
        )
        val mentions = AnnotationContract.schema(markers)
            .getJSONObject("properties")
            .getJSONObject("subject_mentions")
        val itemProperties = mentions.getJSONObject("items").getJSONObject("properties")

        assertEquals(2, mentions.getInt("maxItems"))
        assertEquals(listOf("P1", "PET1"), jsonStrings(itemProperties.getJSONObject("marker").getJSONArray("enum")))
        val prompt = AnnotationContract.prompt(markers)
        assertTrue(prompt.contains("P1=人物"))
        assertTrue(prompt.contains("PET1=宠物"))
        assertFalse(prompt.contains("小明"))
        assertFalse(prompt.contains("旺财"))
        val request = AnnotationRequestFactory.create("qwen3-vl:4b", "IMAGE_BYTES", markers)
        assertEquals(8192, request.getJSONObject("options").getInt("num_ctx"))
        val responseSchema = request.getJSONObject("format")
        assertEquals(
            AnnotationContract.VISIBLE_TEXT_SCHEMA_MAX_LENGTH,
            responseSchema
                .getJSONObject("properties")
                .getJSONObject("visible_text")
                .getInt("maxLength"),
        )
        val schemaMaxLengths = mutableListOf<Int>()
        collectMaxLengths(responseSchema, schemaMaxLengths)
        assertTrue(schemaMaxLengths.isNotEmpty())
        assertTrue(
            schemaMaxLengths.all { it < AnnotationContract.GRAMMAR_MAX_REPETITION_EXCLUSIVE },
        )
        val requestText = request.toString()
        assertFalse(requestText.contains("\"maxLength\":4000"))
        assertFalse(requestText.contains("小明"))
        assertFalse(requestText.contains("旺财"))
        assertTrue(requestText.contains("P1"))
        assertTrue(requestText.contains("PET1"))

        val invalidMarker = AnnotationContract.toJson(
            PhotoAnnotation(
                caption = "P2抱着PET1",
                tags = listOf("人物"),
                visibleText = "",
                facets = PhotoFacets(
                    "室内",
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                ),
                subjectMentions = listOf(SubjectMention("P2", "人物", "站立的人", listOf("抱"))),
            ),
        ).apply { remove("recognized_subjects") }
        assertThrows(IllegalArgumentException::class.java) {
            AnnotationContract.parseVlm(invalidMarker, markers)
        }

        val identityField = JSONObject(invalidMarker.toString())
            .put("recognized_subjects", JSONArray().put(JSONObject().put("name", "小明")))
        assertThrows(IllegalArgumentException::class.java) {
            AnnotationContract.parseVlm(identityField, markers)
        }

        val leakedLocalName = JSONObject(invalidMarker.toString())
            .put("caption", "小明抱着PET1")
            .put(
                "subject_mentions",
                JSONArray().put(
                    JSONObject()
                        .put("marker", "PET1")
                        .put("kind", "宠物")
                        .put("description", "一只狗")
                        .put("actions", JSONArray()),
                ),
            )
        assertThrows(IllegalArgumentException::class.java) {
            AnnotationContract.parseVlm(leakedLocalName, markers)
        }
    }

    @Test
    fun vlmInferenceUsesBoundedJpegAndRecognizesContextOverflow() {
        val source = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
        try {
            val primary = VlmThumbnailEncoder.encode(
                source = source,
                markers = emptyList(),
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
                markers = emptyList(),
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
    fun localFaceModelsLoadAndRunOnBlankFrame() {
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        try {
            LocalFaceEngine(context).use { engine ->
                assertTrue(engine.analyze(bitmap).isEmpty())
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun localPetModelsLoadAndRunOnBlankFrame() {
        val bitmap = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        try {
            LocalPetEngine(context).use { engine ->
                assertTrue(engine.analyze(bitmap).isEmpty())
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun localPetPipelineDetectsAndEmbedsRealCatImage() {
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val bitmap = testAssets.open("cat_reference_test.jpg").use(BitmapFactory::decodeStream)
        try {
            LocalPetEngine(context).use { engine ->
                val samples = engine.analyze(bitmap)
                val cat = samples.firstOrNull { it.species == "cat" }
                assertTrue("真实猫照片应被本地 SSD 检测到", cat != null)
                assertTrue(cat!!.detectorScore >= 0.35f)
                assertTrue(cat.box.left in 0f..1f && cat.box.right in 0f..1f)
                assertTrue(cat.box.top in 0f..1f && cat.box.bottom in 0f..1f)
                val norm = sqrt(cat.embedding.sumOf { (it * it).toDouble() })
                assertTrue("宠物 embedding 必须归一化", norm in 0.999..1.001)
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun bundledPetModelsHavePinnedChecksums() {
        assertEquals(
            "e4b118e5e4531945de2e659742c7c590f7536f8d0ed26d135abcfe83b4779d13",
            assetSha256("models/pet_detection_ssd_mobilenet_v1_uint8.tflite"),
        )
        assertEquals(
            "bbbb4c51a55a53905af1daec995ca1aae355046f8839bb8c9f5ce9271394bc40",
            assetSha256("models/pet_embedding_mobilenet_v3_small_float32.tflite"),
        )
    }

    @Test
    fun matchedPetIsDynamicSearchableAndUnknownAddsNoName() {
        val name = databaseName("pet-matching")
        val database = ApmDatabase(context, name)
        try {
            val firstId = "d".repeat(64)
            val secondId = "e".repeat(64)
            addPhoto(database, firstId, "content://apm.test/pet-first", "pet-first.jpg")
            addPhoto(database, secondId, "content://apm.test/pet-second", "pet-second.jpg")
            addAnnotation(database, firstId)
            addAnnotation(database, secondId)

            val template = petSample(floatArrayOf(1f, 0f), "dog")
            database.registerPetTemplate(
                name = "旺财",
                sample = template,
                modelName = LocalPetEngine.EMBEDDING_MODEL_NAME,
                sourcePhotoId = firstId,
            )
            val templates = database.petTemplates(LocalPetEngine.EMBEDDING_MODEL_NAME)
            val matched = petSample(floatArrayOf(0.95f, sqrt(1f - 0.95f * 0.95f)), "dog")
            val unknown = petSample(floatArrayOf(0.89f, sqrt(1f - 0.89f * 0.89f)), "dog")
            database.replacePetObservations(
                firstId,
                listOf(matched),
                listOf(PetMatcher.decide(matched.embedding, matched.species, templates)),
                LocalPetEngine.EMBEDDING_MODEL_NAME,
                LocalPetEngine.DETECTOR_NAME,
                LocalPetEngine.PET_INDEX_VERSION,
            )
            database.replacePetObservations(
                secondId,
                listOf(unknown),
                listOf(PetMatcher.decide(unknown.embedding, unknown.species, templates)),
                LocalPetEngine.EMBEDDING_MODEL_NAME,
                LocalPetEngine.DETECTOR_NAME,
                LocalPetEngine.PET_INDEX_VERSION,
            )

            val results = database.search("旺财")

            assertEquals(listOf(firstId), results.map(PhotoCard::photoId))
            assertEquals(listOf(RecognizedSubject("旺财", "宠物")), database.matchedPets(firstId))
            assertTrue(database.matchedPets(secondId).isEmpty())
            assertEquals(listOf("旺财"), results.single().annotation.recognizedSubjects.map { it.name })

            val identity = database.petIdentitySummaries().single()
            database.deletePetIdentity(identity.id, LocalPetEngine.EMBEDDING_MODEL_NAME)
            assertTrue(database.search("旺财").isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun anonymousCaptionUsesCurrentLocalMatchesWithoutVlmRerun() {
        val name = databaseName("anonymous-caption")
        val database = ApmDatabase(context, name)
        try {
            val photoId = "f".repeat(64)
            addPhoto(database, photoId, "content://apm.test/anonymous", "anonymous.jpg")
            val face = sample(floatArrayOf(1f, 0f))
            val pet = petSample(floatArrayOf(1f, 0f), "dog")
            database.replaceFaceObservations(
                photoId,
                listOf(face),
                listOf(FaceMatchDecision(FaceMatcher.UNKNOWN)),
                LocalFaceEngine.EMBEDDING_MODEL_NAME,
                LocalFaceEngine.DETECTOR_NAME,
            )
            database.replacePetObservations(
                photoId,
                listOf(pet),
                listOf(PetMatchDecision(PetMatcher.UNKNOWN)),
                LocalPetEngine.EMBEDDING_MODEL_NAME,
                LocalPetEngine.DETECTOR_NAME,
                LocalPetEngine.PET_INDEX_VERSION,
            )
            database.insertAnnotation(
                photoId = photoId,
                annotation = PhotoAnnotation(
                    caption = "P1抱着PET1",
                    tags = listOf("合影"),
                    visibleText = "",
                    facets = PhotoFacets(
                        "室内",
                        emptyList(),
                        emptyList(),
                        emptyList(),
                        listOf("抱"),
                        emptyList(),
                        emptyList(),
                    ),
                    subjectMentions = listOf(
                        SubjectMention("P1", "人物", "站立的人", listOf("抱")),
                        SubjectMention("PET1", "宠物", "一只狗", emptyList()),
                    ),
                ),
                modelName = "qwen3-vl:4b",
                promptVersion = AnnotationContract.PROMPT_VERSION,
            )

            val before = database.search("").single()
            assertEquals("未知人物1抱着未知宠物1", before.caption)
            assertTrue(before.annotation.recognizedSubjects.isEmpty())

            database.registerFaceTemplate(
                "小明",
                face,
                LocalFaceEngine.EMBEDDING_MODEL_NAME,
                photoId,
            )
            database.registerPetTemplate(
                "旺财",
                pet,
                LocalPetEngine.EMBEDDING_MODEL_NAME,
                photoId,
            )
            database.rematchFaceObservations(LocalFaceEngine.EMBEDDING_MODEL_NAME)
            database.rematchPetObservations(LocalPetEngine.EMBEDDING_MODEL_NAME)

            val after = database.search("小明").single()
            assertEquals("小明抱着旺财", after.caption)
            assertEquals(
                setOf("人物:小明", "宠物:旺财"),
                after.annotation.recognizedSubjects.map { "${it.kind}:${it.name}" }.toSet(),
            )
            assertEquals(1, database.annotationCount())
            assertEquals(listOf(photoId), database.search("旺财").map(PhotoCard::photoId))
        } finally {
            database.close()
        }
    }

    @Test
    fun oversizedFrontalFaceIsDetectedOnceAndMatchesOriginalScale() {
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val source = testAssets.open("opencv_sface_demo.jpg").use(BitmapFactory::decodeStream)
        requireNotNull(source)
        val tightPortrait = Bitmap.createBitmap(source, 210, 35, 220, 260)
        val oversized = Bitmap.createScaledBitmap(tightPortrait, 1_354, 1_600, true)
        try {
            val (sourceFace, oversizedFaces) = LocalFaceEngine(context).use { engine ->
                val sourceFace = engine.analyze(source).maxBy { sample ->
                    (sample.box.right - sample.box.left) * (sample.box.bottom - sample.box.top)
                }
                sourceFace to engine.analyze(oversized)
            }

            assertEquals("cross-scale detections must collapse to one physical face", 1, oversizedFaces.size)
            val oversizedFace = oversizedFaces.single()
            assertTrue(oversizedFace.box.right - oversizedFace.box.left > 0.45f)
            assertTrue(oversizedFace.box.bottom - oversizedFace.box.top > 0.45f)
            assertEquals(128, oversizedFace.embedding.size)
            assertTrue(oversizedFace.embedding.all(Float::isFinite))
            val decision = FaceMatcher.decide(
                oversizedFace.embedding,
                listOf(
                    FaceTemplateRecord(
                        id = 1,
                        identityId = 1,
                        identityName = "大脸回归测试",
                        embedding = sourceFace.embedding,
                        modelName = LocalFaceEngine.EMBEDDING_MODEL_NAME,
                    ),
                ),
            )
            assertEquals(FaceMatcher.MATCHED, decision.state)
        } finally {
            oversized.recycle()
            tightPortrait.recycle()
            source.recycle()
        }
    }

    @Test
    fun newDetectorPipelineReindexesOldZeroFaceRowsAndKeepsTemplates() {
        val name = databaseName("face-pipeline-version")
        val database = ApmDatabase(context, name)
        try {
            val photo = scannedPhoto(91)
            database.replaceVisibleSnapshot(listOf(photo))
            database.registerFaceTemplate(
                name = "保留模板",
                sample = sample(floatArrayOf(1f, 0f)),
                modelName = LocalFaceEngine.EMBEDDING_MODEL_NAME,
                sourcePhotoId = photo.photoId,
            )
            database.replaceFaceObservations(
                photoId = photo.photoId,
                samples = emptyList(),
                decisions = emptyList(),
                modelName = LocalFaceEngine.EMBEDDING_MODEL_NAME,
                detectorName = "opencv-yunet-2023mar@legacy-single-scale",
            )

            assertEquals(
                listOf(photo.photoId),
                database.pendingFacePhotos(LocalFaceEngine.FACE_INDEX_VERSION).map(PendingPhoto::photoId),
            )
            assertEquals(1, database.faceTemplates(LocalFaceEngine.EMBEDDING_MODEL_NAME).size)

            val observed = sample(floatArrayOf(1f, 0f))
            val observedDecision = FaceMatcher.decide(
                observed.embedding,
                database.faceTemplates(LocalFaceEngine.EMBEDDING_MODEL_NAME),
            )
            database.replaceFaceObservations(
                photoId = photo.photoId,
                samples = listOf(observed),
                decisions = listOf(observedDecision),
                modelName = LocalFaceEngine.EMBEDDING_MODEL_NAME,
                detectorName = LocalFaceEngine.DETECTOR_NAME,
                indexVersion = LocalFaceEngine.FACE_INDEX_VERSION,
            )

            assertTrue(database.pendingFacePhotos(LocalFaceEngine.FACE_INDEX_VERSION).isEmpty())
            assertEquals(1, database.faceTemplates(LocalFaceEngine.EMBEDDING_MODEL_NAME).size)
            database.readableDatabase.rawQuery(
                "SELECT model_name, pipeline_version FROM photo_faces WHERE photo_id = ?",
                arrayOf(photo.photoId),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(LocalFaceEngine.EMBEDDING_MODEL_NAME, cursor.getString(0))
                assertEquals(LocalFaceEngine.FACE_INDEX_VERSION, cursor.getString(1))
            }
            database.readableDatabase.rawQuery(
                "SELECT model_name, detector_name FROM photo_face_index WHERE photo_id = ?",
                arrayOf(photo.photoId),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(LocalFaceEngine.FACE_INDEX_VERSION, cursor.getString(0))
                assertEquals(LocalFaceEngine.DETECTOR_NAME, cursor.getString(1))
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun officialRealFacesProduceEmbeddingMatchSamePersonAndRejectDifferentPerson() {
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val bitmap = testAssets.open("opencv_sface_demo.jpg").use(BitmapFactory::decodeStream)
        requireNotNull(bitmap)
        val queryHalf = Bitmap.createBitmap(bitmap, bitmap.width / 2, 0, bitmap.width / 2, bitmap.height)
        val enlargedQuery = Bitmap.createScaledBitmap(queryHalf, 2_048, 2_048, true)
        try {
            val (targetSamples, querySamples) = LocalFaceEngine(context).use { engine ->
                engine.analyze(bitmap) to engine.analyze(enlargedQuery)
            }
            assertTrue("YuNet should detect the quality-eligible target face", targetSamples.isNotEmpty())
            assertTrue("YuNet should detect multiple query faces", querySamples.size >= 2)
            val targetFace = targetSamples.maxBy { sample ->
                (sample.box.right - sample.box.left) * (sample.box.bottom - sample.box.top)
            }
            val samePerson = querySamples.maxBy { it.box.left }
            val differentPerson = querySamples.minBy { it.box.left }

            assertEquals(128, targetFace.embedding.size)
            assertEquals(
                1f,
                sqrt(targetFace.embedding.fold(0f) { sum, value -> sum + value * value }),
                0.0001f,
            )
            assertTrue(targetFace.embedding.all(Float::isFinite))
            val templates = listOf(
                FaceTemplateRecord(
                    id = 1,
                    identityId = 1,
                    identityName = "官方测试人物",
                    embedding = targetFace.embedding,
                    modelName = LocalFaceEngine.EMBEDDING_MODEL_NAME,
                ),
            )
            val sameDecision = FaceMatcher.decide(samePerson.embedding, templates)
            val differentDecision = FaceMatcher.decide(differentPerson.embedding, templates)

            assertEquals(FaceMatcher.MATCHED, sameDecision.state)
            assertEquals("官方测试人物", sameDecision.identityName)
            assertTrue(sameDecision.similarity!! >= FaceMatcher.DEFAULT_THRESHOLD)
            assertEquals(FaceMatcher.UNKNOWN, differentDecision.state)
            assertTrue(differentDecision.similarity!! < FaceMatcher.DEFAULT_THRESHOLD)
        } finally {
            enlargedQuery.recycle()
            queryHalf.recycle()
            bitmap.recycle()
        }
    }

    @Test
    fun versionSixMigrationAddsVisualTablesWithoutChangingExistingAssets() {
        val name = databaseName("visual-migration")
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val photoId = "d".repeat(64)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL(
                """
                CREATE TABLE photo_assets (
                    photo_id TEXT PRIMARY KEY,
                    media_type TEXT NOT NULL,
                    byte_size INTEGER NOT NULL,
                    discovered_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            old.execSQL(
                "INSERT INTO photo_assets VALUES (?, 'image/jpeg', 123, '2026-08-15T00:00:00Z')",
                arrayOf(photoId),
            )
            old.version = 6
        }

        val upgraded = ApmDatabase(context, name)
        try {
            upgraded.readableDatabase.rawQuery(
                "SELECT photo_id, byte_size FROM photo_assets",
                null,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(photoId, cursor.getString(0))
                assertEquals(123L, cursor.getLong(1))
            }
            val tables = upgraded.readableDatabase.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table'",
                null,
            ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            assertTrue("photo_visual_features" in tables)
            assertTrue("photo_general_subjects" in tables)
        } finally {
            upgraded.close()
        }
    }

    @Test
    fun visualFeaturesRoundTripAndProduceReadOnlyExplainedRanking() {
        val name = databaseName("visual-ranking")
        val database = ApmDatabase(context, name)
        try {
            val query = scannedPhoto(101).copy(dateTakenMillis = 100_000)
            val candidate = scannedPhoto(102).copy(dateTakenMillis = 104_000)
            val unrelated = scannedPhoto(103).copy(dateTakenMillis = 104_001)
            database.replaceVisibleSnapshot(listOf(query, candidate, unrelated))
            addAnnotation(database, query.photoId)
            addAnnotation(database, candidate.photoId)

            database.replaceVisualFeature(
                visualFeature(query.photoId, 1f),
                listOf(generalSubject("car", floatArrayOf(1f, 0f, 0f))),
            )
            database.replaceVisualFeature(
                visualFeature(candidate.photoId, 0.96f),
                listOf(generalSubject("car", floatArrayOf(0.99f, 0.1f, 0f))),
            )
            database.replaceVisualFeature(
                visualFeature(unrelated.photoId, 0.1f),
                emptyList(),
            )

            val beforeAnnotations = database.annotationCount()
            val results = database.similarPhotos(query.photoId)

            assertEquals(listOf(candidate.photoId), results.map { it.result.candidatePhotoId })
            assertEquals(SimilarityRelationship.BURST, results.single().result.relationship)
            assertTrue(results.single().result.subjectSimilarity!! > 0.9f)
            assertTrue(results.single().result.explanation.contains("连拍"))
            assertEquals(beforeAnnotations, database.annotationCount())
            assertEquals(3, database.visualFeatureCount(SimilarityScorer.PIPELINE_VERSION))
        } finally {
            database.close()
        }
    }

    @Test
    fun visualCheckScopesSelectIncrementalRecentAndFullPhotosExactly() {
        val name = databaseName("visual-check-scopes")
        val database = ApmDatabase(context, name)
        try {
            val requestedAt = 2_000_000_000_000L
            val visible = (1..25).map { index ->
                scannedPhoto(200 + index).copy(
                    dateTakenMillis = when (index) {
                        1 -> requestedAt - 60L * 60L * 1_000L
                        2 -> requestedAt - 2L * 60L * 60L * 1_000L
                        3 -> requestedAt - 30L * 24L * 60L * 60L * 1_000L
                        4 -> null
                        else -> requestedAt - 60L * 24L * 60L * 60L * 1_000L
                    },
                )
            }
            val inaccessible = scannedPhoto(999).copy(dateTakenMillis = requestedAt - 1_000L)
            database.replaceVisibleSnapshot(visible + inaccessible)
            database.replaceVisibleSnapshot(visible)

            (listOf(visible[1]) + visible.drop(4)).forEach { photo ->
                database.replaceVisualFeature(visualFeature(photo.photoId, 1f), emptyList())
            }
            val modelName = visualFeature(visible[1].photoId, 1f).modelName

            val incremental = database.visualPhotosForCheck(
                SimilarityCheckRequest.incremental(requestedAt),
                modelName,
                SimilarityScorer.PIPELINE_VERSION,
            )
            val recent = database.visualPhotosForCheck(
                SimilarityCheckRequest.recent(7, SimilarityRecentUnit.DAYS, requestedAt),
                modelName,
                SimilarityScorer.PIPELINE_VERSION,
            )
            val full = database.visualPhotosForCheck(
                SimilarityCheckRequest.full(requestedAt),
                modelName,
                SimilarityScorer.PIPELINE_VERSION,
            )

            assertEquals(setOf(visible[0].photoId, visible[2].photoId, visible[3].photoId), incremental.photos.map { it.photoId }.toSet())
            assertEquals(setOf(visible[0].photoId, visible[1].photoId), recent.photos.map { it.photoId }.toSet())
            assertEquals(1, recent.excludedWithoutCaptureTime)
            assertEquals(25, full.photos.size)
            assertFalse(full.photos.any { it.photoId == inaccessible.photoId })

            val protectedPhoto = visible[1]
            val replacement = visualFeature(protectedPhoto.photoId, 0.98f).copy(
                indexedAt = "2026-08-16T00:00:00Z",
            )
            assertThrows(IllegalArgumentException::class.java) {
                database.replaceVisualFeature(
                    replacement,
                    listOf(generalSubject("car", floatArrayOf(1f, 0f, 0f)).copy(modelName = "wrong-model")),
                )
            }
            database.readableDatabase.rawQuery(
                "SELECT indexed_at FROM photo_visual_features WHERE photo_id = ?",
                arrayOf(protectedPhoto.photoId),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("2026-08-15T00:00:00Z", cursor.getString(0))
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun bundledDinoV2OnnxProducesSpatialFeaturesAndSeparatesRealPhotos() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val bitmap = testAssets.open("cat_reference_test.jpg").use(BitmapFactory::decodeStream)
        val different = testAssets.open("opencv_sface_demo.jpg").use(BitmapFactory::decodeStream)
        val insetX = bitmap.width / 20
        val insetY = bitmap.height / 20
        val centerCrop = Bitmap.createBitmap(
            bitmap,
            insetX,
            insetY,
            bitmap.width - 2 * insetX,
            bitmap.height - 2 * insetY,
        )
        try {
            val outputs = OnnxRuntimeDinoV2ImageEncoder(targetContext).use { encoder ->
                assertEquals("dinov2-vits14-reg@18964f360347", encoder.modelName)
                listOf(encoder.encode(bitmap), encoder.encode(centerCrop), encoder.encode(different))
            }
            val output = outputs[0]

            assertEquals(384, output.globalEmbedding.size)
            assertEquals(PhotoVisualFeature.PATCH_COUNT, output.patchGrid.size)
            assertTrue(output.patchGrid.all { it.size == 384 })
            assertEquals(1f, embeddingNorm(output.globalEmbedding), 0.0001f)
            assertTrue(output.globalEmbedding.any { it != 0f })
            output.patchGrid.forEach { patch -> assertEquals(1f, embeddingNorm(patch), 0.0001f) }
            assertTrue(output.patchGrid.all { patch -> patch.any { it != 0f } })
            assertTrue(FaceMatcher.cosine(outputs[0].globalEmbedding, outputs[1].globalEmbedding) > 0.9f)
            assertTrue(
                outputs[0].patchGrid.indices
                    .map { FaceMatcher.cosine(outputs[0].patchGrid[it], outputs[1].patchGrid[it]) }
                    .average() > 0.9,
            )
            assertTrue(FaceMatcher.cosine(outputs[0].globalEmbedding, outputs[2].globalEmbedding) < 0.8f)
        } finally {
            centerCrop.recycle()
            different.recycle()
            bitmap.recycle()
        }
    }

    private fun jsonStrings(array: JSONArray): List<String> =
        (0 until array.length()).map(array::getString)

    private fun assetSha256(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

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

    private fun sample(embedding: FloatArray) = FaceSample(
        faceIndex = 0,
        box = FaceBox(0.1f, 0.1f, 0.6f, 0.7f),
        detectorScore = 0.99f,
        embedding = FaceMatcher.normalize(embedding),
    )

    private fun petSample(embedding: FloatArray, species: String) = PetSample(
        petIndex = 0,
        box = FaceBox(0.1f, 0.1f, 0.6f, 0.7f),
        species = species,
        detectorScore = 0.99f,
        embedding = FaceMatcher.normalize(embedding),
    )

    private fun embeddingNorm(embedding: FloatArray): Float =
        sqrt(embedding.fold(0f) { sum, value -> sum + value * value })

    private fun visualFeature(photoId: String, cosine: Float): PhotoVisualFeature {
        val vector = FaceMatcher.normalize(
            floatArrayOf(cosine, sqrt((1f - cosine * cosine).coerceAtLeast(0f)), 0f),
        )
        return PhotoVisualFeature(
            photoId = photoId,
            globalEmbedding = vector,
            patchEmbeddings = List(PhotoVisualFeature.PATCH_COUNT) { vector },
            modelName = "dinov2-vits14-reg@test",
            pipelineVersion = SimilarityScorer.PIPELINE_VERSION,
            indexedAt = "2026-08-15T00:00:00Z",
        )
    }

    private fun generalSubject(category: String, embedding: FloatArray) = GeneralSubjectObservation(
        subjectIndex = 0,
        category = category,
        box = FaceBox(0.1f, 0.1f, 0.8f, 0.8f),
        detectorScore = 0.95f,
        embedding = FaceMatcher.normalize(embedding),
        detectorName = LocalObjectDetector.DETECTOR_NAME,
        modelName = "dinov2-vits14-reg@test",
        pipelineVersion = SimilarityScorer.PIPELINE_VERSION,
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
