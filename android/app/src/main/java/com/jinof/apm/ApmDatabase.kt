package com.jinof.apm

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

class ApmDatabase(
    context: Context,
    databaseName: String = "apm-android.sqlite3",
) : SQLiteOpenHelper(context, databaseName, null, 7) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE photo_assets (
                photo_id TEXT PRIMARY KEY CHECK(length(photo_id) = 64),
                media_type TEXT NOT NULL,
                byte_size INTEGER NOT NULL CHECK(byte_size >= 0),
                discovered_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE media_locations (
                uri TEXT PRIMARY KEY,
                media_store_id INTEGER NOT NULL,
                photo_id TEXT NOT NULL REFERENCES photo_assets(photo_id),
                display_name TEXT NOT NULL,
                byte_size INTEGER NOT NULL CHECK(byte_size >= 0),
                modified_seconds INTEGER NOT NULL,
                date_taken_millis INTEGER,
                accessible INTEGER NOT NULL CHECK(accessible IN (0, 1)),
                last_seen_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX media_locations_photo ON media_locations(photo_id, accessible)")
        db.execSQL(
            """
            CREATE TABLE photo_annotations (
                annotation_id INTEGER PRIMARY KEY AUTOINCREMENT,
                photo_id TEXT NOT NULL REFERENCES photo_assets(photo_id),
                caption TEXT NOT NULL,
                tags_json TEXT NOT NULL,
                facets_json TEXT NOT NULL,
                visible_text TEXT NOT NULL,
                recognized_subjects_json TEXT NOT NULL DEFAULT '[]',
                subject_mentions_json TEXT NOT NULL DEFAULT '[]',
                provider TEXT NOT NULL,
                model_name TEXT NOT NULL,
                prompt_version TEXT NOT NULL,
                created_at TEXT NOT NULL,
                search_text TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX photo_annotations_latest ON photo_annotations(photo_id, annotation_id DESC)",
        )
        db.execSQL(
            "CREATE INDEX photo_annotations_provenance ON photo_annotations(photo_id, provider, model_name, prompt_version)",
        )
        createFaceTables(db)
        createPetTables(db)
        createAnnotationSelectionTable(db)
        createVisualSimilarityTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                "ALTER TABLE photo_annotations ADD COLUMN recognized_subjects_json TEXT NOT NULL DEFAULT '[]'",
            )
        }
        if (oldVersion < 3) {
            createFaceTables(db)
            stripLegacyVlmPeople(db)
        }
        if (oldVersion < 4) {
            createAnnotationSelectionTable(db)
        }
        if (oldVersion in 3..4) {
            db.execSQL(
                "ALTER TABLE photo_faces ADD COLUMN pipeline_version TEXT NOT NULL DEFAULT 'legacy-single-scale'",
            )
        }
        if (oldVersion < 6) {
            if (tableExists(db, "photo_annotations")) {
                db.execSQL(
                    "ALTER TABLE photo_annotations ADD COLUMN subject_mentions_json TEXT NOT NULL DEFAULT '[]'",
                )
            }
            createPetTables(db)
            if (tableExists(db, "photo_annotations")) stripLegacyVlmIdentities(db)
        }
        if (oldVersion < 7) {
            createVisualSimilarityTables(db)
        }
    }

    private fun createAnnotationSelectionTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS annotation_selection (
                photo_id TEXT PRIMARY KEY REFERENCES photo_assets(photo_id) ON DELETE CASCADE,
                selected_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun tableExists(db: SQLiteDatabase, name: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
        arrayOf(name),
    ).use { it.moveToFirst() }

    private fun createFaceTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS person_identities (
                identity_id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL COLLATE NOCASE UNIQUE CHECK(length(trim(name)) BETWEEN 1 AND 40),
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS face_templates (
                template_id INTEGER PRIMARY KEY AUTOINCREMENT,
                identity_id INTEGER NOT NULL REFERENCES person_identities(identity_id) ON DELETE CASCADE,
                embedding BLOB NOT NULL,
                embedding_dimension INTEGER NOT NULL CHECK(embedding_dimension > 0),
                model_name TEXT NOT NULL,
                source_photo_id TEXT REFERENCES photo_assets(photo_id),
                bounding_box_json TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS face_templates_identity ON face_templates(identity_id, model_name)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS photo_face_index (
                photo_id TEXT NOT NULL REFERENCES photo_assets(photo_id),
                model_name TEXT NOT NULL,
                detector_name TEXT NOT NULL,
                face_count INTEGER NOT NULL CHECK(face_count >= 0),
                indexed_at TEXT NOT NULL,
                PRIMARY KEY (photo_id, model_name)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS photo_faces (
                face_id INTEGER PRIMARY KEY AUTOINCREMENT,
                photo_id TEXT NOT NULL REFERENCES photo_assets(photo_id),
                face_index INTEGER NOT NULL CHECK(face_index >= 0),
                embedding BLOB NOT NULL,
                embedding_dimension INTEGER NOT NULL CHECK(embedding_dimension > 0),
                bounding_box_json TEXT NOT NULL,
                detector_score REAL NOT NULL CHECK(detector_score >= 0 AND detector_score <= 1),
                model_name TEXT NOT NULL,
                pipeline_version TEXT NOT NULL,
                match_state TEXT NOT NULL CHECK(match_state IN ('matched', 'unknown')),
                matched_identity_id INTEGER REFERENCES person_identities(identity_id) ON DELETE SET NULL,
                similarity REAL,
                threshold REAL NOT NULL,
                indexed_at TEXT NOT NULL,
                UNIQUE(photo_id, face_index, model_name),
                CHECK(
                    (match_state = 'matched' AND matched_identity_id IS NOT NULL AND similarity >= threshold)
                    OR (match_state = 'unknown' AND matched_identity_id IS NULL)
                )
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS photo_faces_photo ON photo_faces(photo_id, model_name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS photo_faces_identity ON photo_faces(matched_identity_id, match_state)")
    }

    private fun createPetTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pet_identities (
                identity_id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL COLLATE NOCASE UNIQUE CHECK(length(trim(name)) BETWEEN 1 AND 40),
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pet_templates (
                template_id INTEGER PRIMARY KEY AUTOINCREMENT,
                identity_id INTEGER NOT NULL REFERENCES pet_identities(identity_id) ON DELETE CASCADE,
                species TEXT NOT NULL CHECK(species IN ('cat', 'dog')),
                embedding BLOB NOT NULL,
                embedding_dimension INTEGER NOT NULL CHECK(embedding_dimension > 0),
                model_name TEXT NOT NULL,
                source_photo_id TEXT REFERENCES photo_assets(photo_id),
                bounding_box_json TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS pet_templates_identity ON pet_templates(identity_id, species, model_name)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS photo_pet_index (
                photo_id TEXT NOT NULL REFERENCES photo_assets(photo_id),
                pipeline_version TEXT NOT NULL,
                detector_name TEXT NOT NULL,
                pet_count INTEGER NOT NULL CHECK(pet_count >= 0),
                indexed_at TEXT NOT NULL,
                PRIMARY KEY (photo_id, pipeline_version)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS photo_pets (
                pet_id INTEGER PRIMARY KEY AUTOINCREMENT,
                photo_id TEXT NOT NULL REFERENCES photo_assets(photo_id),
                pet_index INTEGER NOT NULL CHECK(pet_index >= 0),
                species TEXT NOT NULL CHECK(species IN ('cat', 'dog')),
                embedding BLOB NOT NULL,
                embedding_dimension INTEGER NOT NULL CHECK(embedding_dimension > 0),
                bounding_box_json TEXT NOT NULL,
                detector_score REAL NOT NULL CHECK(detector_score >= 0 AND detector_score <= 1),
                model_name TEXT NOT NULL,
                pipeline_version TEXT NOT NULL,
                match_state TEXT NOT NULL CHECK(match_state IN ('matched', 'unknown')),
                matched_identity_id INTEGER REFERENCES pet_identities(identity_id) ON DELETE SET NULL,
                similarity REAL,
                threshold REAL NOT NULL,
                indexed_at TEXT NOT NULL,
                UNIQUE(photo_id, pet_index, model_name),
                CHECK(
                    (match_state = 'matched' AND matched_identity_id IS NOT NULL AND similarity >= threshold)
                    OR (match_state = 'unknown' AND matched_identity_id IS NULL)
                )
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS photo_pets_photo ON photo_pets(photo_id, model_name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS photo_pets_identity ON photo_pets(matched_identity_id, match_state)")
    }

    private fun createVisualSimilarityTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS photo_visual_features (
                photo_id TEXT PRIMARY KEY REFERENCES photo_assets(photo_id) ON DELETE CASCADE,
                global_embedding BLOB NOT NULL,
                embedding_dimension INTEGER NOT NULL CHECK(embedding_dimension > 0),
                patch_grid BLOB NOT NULL,
                grid_size INTEGER NOT NULL CHECK(grid_size = 4),
                model_name TEXT NOT NULL,
                pipeline_version TEXT NOT NULL,
                indexed_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS photo_visual_features_pipeline ON photo_visual_features(model_name, pipeline_version)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS photo_general_subjects (
                subject_id INTEGER PRIMARY KEY AUTOINCREMENT,
                photo_id TEXT NOT NULL REFERENCES photo_assets(photo_id) ON DELETE CASCADE,
                subject_index INTEGER NOT NULL CHECK(subject_index >= 0),
                category TEXT NOT NULL,
                embedding BLOB NOT NULL,
                embedding_dimension INTEGER NOT NULL CHECK(embedding_dimension > 0),
                bounding_box_json TEXT NOT NULL,
                detector_score REAL NOT NULL CHECK(detector_score >= 0 AND detector_score <= 1),
                detector_name TEXT NOT NULL,
                model_name TEXT NOT NULL,
                pipeline_version TEXT NOT NULL,
                indexed_at TEXT NOT NULL,
                UNIQUE(photo_id, subject_index, pipeline_version)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS photo_general_subjects_photo ON photo_general_subjects(photo_id, pipeline_version)",
        )
    }

    private fun stripLegacyVlmPeople(db: SQLiteDatabase) {
        val rows = db.rawQuery(
            """
            SELECT annotation_id, caption, tags_json, facets_json, visible_text, recognized_subjects_json
            FROM photo_annotations
            """.trimIndent(),
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        listOf(
                            cursor.getString(0),
                            cursor.getString(1),
                            cursor.getString(2),
                            cursor.getString(3),
                            cursor.getString(4),
                            cursor.getString(5),
                        ),
                    )
                }
            }
        }
        rows.forEach { row ->
            val keptPets = runCatching {
                val source = JSONArray(row[5])
                JSONArray().also { target ->
                    for (index in 0 until source.length()) {
                        val subject = source.optJSONObject(index) ?: continue
                        if (subject.optString("kind") == "宠物") target.put(subject)
                    }
                }
            }.getOrElse { JSONArray() }
            val combined = JSONObject()
                .put("caption", row[1])
                .put("tags", JSONArray(row[2]))
                .put("facets", JSONObject(row[3]))
                .put("visible_text", row[4])
                .put("recognized_subjects", keptPets)
            val searchText = runCatching { FacetRules.searchText(AnnotationContract.parse(combined)) }
                .getOrElse { "${row[1]} ${row[2]} ${row[3]} ${row[4]}".lowercase() }
            db.update(
                "photo_annotations",
                ContentValues().apply {
                    put("recognized_subjects_json", keptPets.toString())
                    put("search_text", searchText)
                },
                "annotation_id = ?",
                arrayOf(row[0]),
            )
        }
    }

    private fun stripLegacyVlmIdentities(db: SQLiteDatabase) {
        val rows = db.rawQuery(
            """
            SELECT annotation_id, caption, tags_json, facets_json, visible_text
            FROM photo_annotations
            """.trimIndent(),
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        listOf(
                            cursor.getString(0),
                            cursor.getString(1),
                            cursor.getString(2),
                            cursor.getString(3),
                            cursor.getString(4),
                        ),
                    )
                }
            }
        }
        rows.forEach { row ->
            val annotation = JSONObject()
                .put("caption", row[1])
                .put("tags", JSONArray(row[2]))
                .put("facets", JSONObject(row[3]))
                .put("visible_text", row[4])
                .put("subject_mentions", JSONArray())
            val searchText = runCatching { FacetRules.searchText(AnnotationContract.parse(annotation)) }
                .getOrElse { "${row[1]} ${row[2]} ${row[3]} ${row[4]}".lowercase() }
            db.update(
                "photo_annotations",
                ContentValues().apply {
                    put("recognized_subjects_json", "[]")
                    put("subject_mentions_json", "[]")
                    put("search_text", searchText)
                },
                "annotation_id = ?",
                arrayOf(row[0]),
            )
        }
    }

    @Synchronized
    fun knownLocation(uri: String): KnownLocation? {
        readableDatabase.query(
            "media_locations",
            arrayOf("photo_id", "byte_size", "modified_seconds"),
            "uri = ?",
            arrayOf(uri),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return KnownLocation(
                photoId = cursor.getString(0),
                byteSize = cursor.getLong(1),
                modifiedSeconds = cursor.getLong(2),
            )
        }
    }

    @Synchronized
    fun replaceVisibleSnapshot(photos: List<ScannedPhoto>): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update(
                "media_locations",
                ContentValues().apply { put("accessible", 0) },
                "accessible = 1 AND uri LIKE 'content://media/%/images/media/%'",
                null,
            )
            val now = Instant.now().toString()
            upsertAccessiblePhotos(db, photos, now)
            val currentlyInaccessible = db.rawQuery(
                "SELECT COUNT(*) FROM media_locations WHERE accessible = 0",
                null,
            ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
            db.setTransactionSuccessful()
            return currentlyInaccessible
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun upsertAccessiblePhotos(photos: List<ScannedPhoto>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            upsertAccessiblePhotos(db, photos, Instant.now().toString())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun upsertAccessiblePhotos(db: SQLiteDatabase, photos: List<ScannedPhoto>, now: String) {
        photos.forEach { photo ->
            db.insertWithOnConflict(
                "photo_assets",
                null,
                ContentValues().apply {
                    put("photo_id", photo.photoId)
                    put("media_type", photo.mediaType)
                    put("byte_size", photo.byteSize)
                    put("discovered_at", now)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            db.insertWithOnConflict(
                "media_locations",
                null,
                ContentValues().apply {
                    put("uri", photo.uri)
                    put("media_store_id", photo.mediaStoreId)
                    put("photo_id", photo.photoId)
                    put("display_name", photo.displayName)
                    put("byte_size", photo.byteSize)
                    put("modified_seconds", photo.modifiedSeconds)
                    if (photo.dateTakenMillis == null) putNull("date_taken_millis")
                    else put("date_taken_millis", photo.dateTakenMillis)
                    put("accessible", 1)
                    put("last_seen_at", now)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    @Synchronized
    fun replaceAnnotationSelection(photoIds: Collection<String>) {
        val selected = photoIds.distinct()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("annotation_selection", null, null)
            val now = Instant.now().toString()
            selected.forEach { photoId ->
                db.insertOrThrow(
                    "annotation_selection",
                    null,
                    ContentValues().apply {
                        put("photo_id", photoId)
                        put("selected_at", now)
                    },
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun annotationSelectionCount(): Int = readableDatabase.rawQuery(
        """
        SELECT COUNT(*)
        FROM annotation_selection AS selection
        WHERE EXISTS (
            SELECT 1 FROM media_locations AS location
            WHERE location.photo_id = selection.photo_id AND location.accessible = 1
        )
        """.trimIndent(),
        null,
    ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    @Synchronized
    fun pendingAllPhotos(modelName: String, promptVersion: String): List<PendingPhoto> {
        val sql = """
            SELECT location.photo_id, MIN(location.uri) AS uri, MIN(location.display_name) AS display_name
            FROM media_locations AS location
            WHERE location.accessible = 1
              AND NOT EXISTS (
                  SELECT 1 FROM photo_annotations AS annotation
                  WHERE annotation.photo_id = location.photo_id
                    AND annotation.provider = 'ollama'
                    AND annotation.model_name = ?
                    AND annotation.prompt_version = ?
            )
            GROUP BY location.photo_id
            ORDER BY COALESCE(MAX(location.date_taken_millis), MAX(location.modified_seconds) * 1000) DESC
        """.trimIndent()
        return readableDatabase.rawQuery(sql, arrayOf(modelName, promptVersion)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(PendingPhoto(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                }
            }
        }
    }

    @Synchronized
    fun pendingSelectedPhotos(modelName: String, promptVersion: String): List<PendingPhoto> {
        val sql = """
            SELECT location.photo_id, MIN(location.uri) AS uri, MIN(location.display_name) AS display_name
            FROM annotation_selection AS selection
            JOIN media_locations AS location ON location.photo_id = selection.photo_id
            WHERE location.accessible = 1
              AND NOT EXISTS (
                  SELECT 1 FROM photo_annotations AS annotation
                  WHERE annotation.photo_id = location.photo_id
                    AND annotation.provider = 'ollama'
                    AND annotation.model_name = ?
                    AND annotation.prompt_version = ?
              )
            GROUP BY location.photo_id
            ORDER BY MIN(selection.selected_at), location.photo_id
        """.trimIndent()
        return readableDatabase.rawQuery(sql, arrayOf(modelName, promptVersion)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(PendingPhoto(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                }
            }
        }
    }

    @Synchronized
    fun pendingFacePhotos(
        modelName: String,
        limit: Int? = 500,
        selectedOnly: Boolean = false,
    ): List<PendingPhoto> {
        if (limit != null) require(limit in 1..5_000)
        val selectionJoin = if (selectedOnly) {
            "JOIN annotation_selection AS selection ON selection.photo_id = location.photo_id"
        } else {
            ""
        }
        val limitClause = if (limit == null) "" else "LIMIT ?"
        val sql = """
            SELECT location.photo_id, MIN(location.uri), MIN(location.display_name)
            FROM media_locations AS location
            $selectionJoin
            WHERE location.accessible = 1
              AND NOT EXISTS (
                  SELECT 1 FROM photo_face_index AS face_index
                  WHERE face_index.photo_id = location.photo_id
                    AND face_index.model_name = ?
            )
            GROUP BY location.photo_id
            ORDER BY COALESCE(MAX(location.date_taken_millis), MAX(location.modified_seconds) * 1000) DESC
            $limitClause
        """.trimIndent()
        val arguments = if (limit == null) arrayOf(modelName) else arrayOf(modelName, limit.toString())
        return readableDatabase.rawQuery(sql, arguments).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(PendingPhoto(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                }
            }
        }
    }

    @Synchronized
    fun pendingPetPhotos(
        indexVersion: String,
        limit: Int? = 500,
        selectedOnly: Boolean = false,
    ): List<PendingPhoto> {
        if (limit != null) require(limit in 1..5_000)
        val selectionJoin = if (selectedOnly) {
            "JOIN annotation_selection AS selection ON selection.photo_id = location.photo_id"
        } else {
            ""
        }
        val limitClause = if (limit == null) "" else "LIMIT ?"
        val sql = """
            SELECT location.photo_id, MIN(location.uri), MIN(location.display_name)
            FROM media_locations AS location
            $selectionJoin
            WHERE location.accessible = 1
              AND NOT EXISTS (
                  SELECT 1 FROM photo_pet_index AS pet_index
                  WHERE pet_index.photo_id = location.photo_id
                    AND pet_index.pipeline_version = ?
              )
            GROUP BY location.photo_id
            ORDER BY COALESCE(MAX(location.date_taken_millis), MAX(location.modified_seconds) * 1000) DESC
            $limitClause
        """.trimIndent()
        val arguments = if (limit == null) arrayOf(indexVersion) else arrayOf(indexVersion, limit.toString())
        return readableDatabase.rawQuery(sql, arguments).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(PendingPhoto(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                }
            }
        }
    }

    @Synchronized
    fun pendingVisualPhotos(
        modelName: String,
        pipelineVersion: String,
        limit: Int? = null,
        selectedOnly: Boolean = false,
    ): List<PendingPhoto> {
        if (limit != null) require(limit in 1..5_000)
        val selectionJoin = if (selectedOnly) {
            "JOIN annotation_selection AS selection ON selection.photo_id = location.photo_id"
        } else {
            ""
        }
        val limitClause = if (limit == null) "" else "LIMIT ?"
        val sql = """
            SELECT location.photo_id, MIN(location.uri), MIN(location.display_name)
            FROM media_locations AS location
            $selectionJoin
            WHERE location.accessible = 1
              AND NOT EXISTS (
                  SELECT 1 FROM photo_visual_features AS feature
                  WHERE feature.photo_id = location.photo_id
                    AND feature.model_name = ?
                    AND feature.pipeline_version = ?
              )
            GROUP BY location.photo_id
            ORDER BY COALESCE(MAX(location.date_taken_millis), MAX(location.modified_seconds) * 1000) DESC
            $limitClause
        """.trimIndent()
        val arguments = buildList {
            add(modelName)
            add(pipelineVersion)
            if (limit != null) add(limit.toString())
        }.toTypedArray()
        return readableDatabase.rawQuery(sql, arguments).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(PendingPhoto(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                }
            }
        }
    }

    @Synchronized
    fun visualPhotosForCheck(
        request: SimilarityCheckRequest,
        modelName: String,
        pipelineVersion: String,
        limit: Int? = null,
    ): VisualCheckSelection {
        if (limit != null) require(limit >= 1)
        val scopeClause = when (request.mode) {
            SimilarityCheckMode.INCREMENTAL ->
                """
                AND NOT EXISTS (
                    SELECT 1 FROM photo_visual_features AS feature
                    WHERE feature.photo_id = location.photo_id
                      AND feature.model_name = ?
                      AND feature.pipeline_version = ?
                )
                """.trimIndent()
            SimilarityCheckMode.RECENT -> "AND location.date_taken_millis >= ?"
            SimilarityCheckMode.FULL -> ""
        }
        val limitClause = if (limit == null) "" else "LIMIT ?"
        val arguments = buildList {
            when (request.mode) {
                SimilarityCheckMode.INCREMENTAL -> {
                    add(modelName)
                    add(pipelineVersion)
                }
                SimilarityCheckMode.RECENT -> add(requireNotNull(request.recentCutoffMillis).toString())
                SimilarityCheckMode.FULL -> Unit
            }
            if (limit != null) add(limit.toString())
        }.toTypedArray()
        val photos = readableDatabase.rawQuery(
            """
            SELECT location.photo_id, MIN(location.uri), MIN(location.display_name)
            FROM media_locations AS location
            WHERE location.accessible = 1
              $scopeClause
            GROUP BY location.photo_id
            ORDER BY COALESCE(MAX(location.date_taken_millis), MAX(location.modified_seconds) * 1000) DESC,
                     location.photo_id ASC
            $limitClause
            """.trimIndent(),
            arguments,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(PendingPhoto(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                }
            }
        }
        val excludedWithoutCaptureTime = if (request.mode == SimilarityCheckMode.RECENT) {
            readableDatabase.rawQuery(
                """
                SELECT COUNT(*) FROM (
                    SELECT location.photo_id
                    FROM media_locations AS location
                    WHERE location.accessible = 1
                    GROUP BY location.photo_id
                    HAVING MAX(location.date_taken_millis) IS NULL
                )
                """.trimIndent(),
                null,
            ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
        } else {
            0
        }
        return VisualCheckSelection(photos, excludedWithoutCaptureTime)
    }

    @Synchronized
    fun replaceVisualFeature(
        feature: PhotoVisualFeature,
        generalSubjects: List<GeneralSubjectObservation>,
    ) {
        require(generalSubjects.all {
            it.modelName == feature.modelName && it.pipelineVersion == feature.pipelineVersion
        })
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("photo_general_subjects", "photo_id = ?", arrayOf(feature.photoId))
            db.insertWithOnConflict(
                "photo_visual_features",
                null,
                ContentValues().apply {
                    put("photo_id", feature.photoId)
                    put("global_embedding", VisualEmbeddingCodec.encode(feature.globalEmbedding))
                    put("embedding_dimension", feature.dimension)
                    put("patch_grid", VisualEmbeddingCodec.encodeGrid(feature.patchEmbeddings, feature.dimension))
                    put("grid_size", PhotoVisualFeature.GRID_SIZE)
                    put("model_name", feature.modelName)
                    put("pipeline_version", feature.pipelineVersion)
                    put("indexed_at", feature.indexedAt)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            generalSubjects.forEach { subject ->
                db.insertOrThrow(
                    "photo_general_subjects",
                    null,
                    ContentValues().apply {
                        put("photo_id", feature.photoId)
                        put("subject_index", subject.subjectIndex)
                        put("category", subject.category)
                        put("embedding", VisualEmbeddingCodec.encode(subject.embedding))
                        put("embedding_dimension", subject.embedding.size)
                        put("bounding_box_json", faceBoxJson(subject.box).toString())
                        put("detector_score", subject.detectorScore)
                        put("detector_name", subject.detectorName)
                        put("model_name", subject.modelName)
                        put("pipeline_version", subject.pipelineVersion)
                        put("indexed_at", feature.indexedAt)
                    },
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun visualFeatureCount(modelName: String, pipelineVersion: String): Int = readableDatabase.rawQuery(
        """
        SELECT COUNT(*)
        FROM photo_visual_features AS feature
        WHERE feature.model_name = ? AND feature.pipeline_version = ?
          AND EXISTS (
              SELECT 1 FROM media_locations AS location
              WHERE location.photo_id = feature.photo_id AND location.accessible = 1
          )
        """.trimIndent(),
        arrayOf(modelName, pipelineVersion),
    ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    @Synchronized
    fun visualFeatureCount(pipelineVersion: String): Int = readableDatabase.rawQuery(
        """
        SELECT COUNT(*)
        FROM photo_visual_features AS feature
        WHERE feature.pipeline_version = ?
          AND EXISTS (
              SELECT 1 FROM media_locations AS location
              WHERE location.photo_id = feature.photo_id AND location.accessible = 1
          )
        """.trimIndent(),
        arrayOf(pipelineVersion),
    ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    @Synchronized
    fun hasVisualFeature(photoId: String): Boolean = readableDatabase.rawQuery(
        """
        SELECT 1
        FROM photo_visual_features AS feature
        WHERE feature.photo_id = ? AND feature.pipeline_version = ?
          AND EXISTS (
              SELECT 1 FROM media_locations AS location
              WHERE location.photo_id = feature.photo_id AND location.accessible = 1
          )
        LIMIT 1
        """.trimIndent(),
        arrayOf(photoId, SimilarityScorer.PIPELINE_VERSION),
    ).use(Cursor::moveToFirst)

    @Synchronized
    fun similarPhotos(queryPhotoId: String, limit: Int = 100): List<SimilarPhotoCard> {
        require(limit in 1..500)
        val queryFeature = readableDatabase.rawQuery(
            "SELECT model_name, pipeline_version FROM photo_visual_features WHERE photo_id = ?",
            arrayOf(queryPhotoId),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return emptyList()
            cursor.getString(0) to cursor.getString(1)
        }
        val loaded = loadSimilarityInputs(queryFeature.first, queryFeature.second)
        val query = loaded.inputs.firstOrNull { it.photoId == queryPhotoId } ?: return emptyList()

        // Ranking is deliberately completed before any annotation is read.
        val ranked = SimilarityScorer.rank(query, loaded.inputs).take(limit)
        val queryAnnotation = latestAnnotation(queryPhotoId)
        return ranked.mapNotNull { result ->
            val location = loaded.locations[result.candidatePhotoId] ?: return@mapNotNull null
            SimilarPhotoCard(
                result = SimilarityExplanationBuilder.build(
                    result,
                    queryAnnotation,
                    latestAnnotation(result.candidatePhotoId),
                ),
                uri = location.uri,
                displayName = location.displayName,
                dateTakenMillis = location.dateTakenMillis,
            )
        }
    }

    private data class LoadedSimilarityInputs(
        val inputs: List<SimilarityPhotoInput>,
        val locations: Map<String, SimilarityPhotoLocation>,
    )

    private fun loadSimilarityInputs(modelName: String, pipelineVersion: String): LoadedSimilarityInputs {
        val locations = linkedMapOf<String, SimilarityPhotoLocation>()
        val features = linkedMapOf<String, PhotoVisualFeature>()
        readableDatabase.rawQuery(
            """
            WITH chosen_location AS (
                SELECT photo_id, MIN(uri) AS uri
                FROM media_locations
                WHERE accessible = 1
                GROUP BY photo_id
            )
            SELECT feature.photo_id, feature.global_embedding, feature.embedding_dimension,
                   feature.patch_grid, feature.model_name, feature.pipeline_version, feature.indexed_at,
                   location.uri, location.display_name, location.date_taken_millis
            FROM photo_visual_features AS feature
            JOIN chosen_location AS chosen ON chosen.photo_id = feature.photo_id
            JOIN media_locations AS location ON location.uri = chosen.uri
            WHERE feature.model_name = ? AND feature.pipeline_version = ?
              AND location.accessible = 1
            ORDER BY feature.photo_id
            """.trimIndent(),
            arrayOf(modelName, pipelineVersion),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                runCatching {
                    val photoId = cursor.getString(0)
                    val dimension = cursor.getInt(2)
                    features[photoId] = PhotoVisualFeature(
                        photoId = photoId,
                        globalEmbedding = VisualEmbeddingCodec.decode(cursor.getBlob(1), dimension),
                        patchEmbeddings = VisualEmbeddingCodec.decodeGrid(cursor.getBlob(3), dimension),
                        modelName = cursor.getString(4),
                        pipelineVersion = cursor.getString(5),
                        indexedAt = cursor.getString(6),
                    )
                    locations[photoId] = SimilarityPhotoLocation(
                        photoId = photoId,
                        uri = cursor.getString(7),
                        displayName = cursor.getString(8),
                        dateTakenMillis = if (cursor.isNull(9)) null else cursor.getLong(9),
                    )
                }
            }
        }
        if (features.isEmpty()) return LoadedSimilarityInputs(emptyList(), emptyMap())

        val subjects = mutableMapOf<String, MutableList<SimilaritySubjectObservation>>()
        fun addSubject(photoId: String, subject: SimilaritySubjectObservation) {
            if (photoId in features) subjects.getOrPut(photoId, ::mutableListOf).add(subject)
        }
        readableDatabase.rawQuery(
            """
            SELECT face.photo_id, face.embedding, face.embedding_dimension,
                   face.model_name, face.pipeline_version
            FROM photo_faces AS face
            JOIN photo_visual_features AS feature ON feature.photo_id = face.photo_id
            WHERE feature.model_name = ? AND feature.pipeline_version = ?
            ORDER BY face.photo_id, face.face_index
            """.trimIndent(),
            arrayOf(modelName, pipelineVersion),
        ).use { cursor ->
            while (cursor.moveToNext()) runCatching {
                addSubject(
                    cursor.getString(0),
                    SimilaritySubjectObservation(
                        kind = SimilaritySubjectKind.FACE,
                        category = "person",
                        embedding = FaceEmbeddingCodec.decode(cursor.getBlob(1), cursor.getInt(2)),
                        modelName = cursor.getString(3),
                        pipelineVersion = cursor.getString(4),
                    ),
                )
            }
        }
        readableDatabase.rawQuery(
            """
            SELECT pet.photo_id, pet.species, pet.embedding, pet.embedding_dimension,
                   pet.model_name, pet.pipeline_version
            FROM photo_pets AS pet
            JOIN photo_visual_features AS feature ON feature.photo_id = pet.photo_id
            WHERE feature.model_name = ? AND feature.pipeline_version = ?
            ORDER BY pet.photo_id, pet.pet_index
            """.trimIndent(),
            arrayOf(modelName, pipelineVersion),
        ).use { cursor ->
            while (cursor.moveToNext()) runCatching {
                addSubject(
                    cursor.getString(0),
                    SimilaritySubjectObservation(
                        kind = SimilaritySubjectKind.PET,
                        category = cursor.getString(1),
                        embedding = FaceEmbeddingCodec.decode(cursor.getBlob(2), cursor.getInt(3)),
                        modelName = cursor.getString(4),
                        pipelineVersion = cursor.getString(5),
                    ),
                )
            }
        }
        readableDatabase.rawQuery(
            """
            SELECT subject.photo_id, subject.category, subject.embedding, subject.embedding_dimension,
                   subject.model_name, subject.pipeline_version
            FROM photo_general_subjects AS subject
            JOIN photo_visual_features AS feature ON feature.photo_id = subject.photo_id
            WHERE feature.model_name = ? AND feature.pipeline_version = ?
              AND subject.model_name = feature.model_name
              AND subject.pipeline_version = feature.pipeline_version
            ORDER BY subject.photo_id, subject.subject_index
            """.trimIndent(),
            arrayOf(modelName, pipelineVersion),
        ).use { cursor ->
            while (cursor.moveToNext()) runCatching {
                addSubject(
                    cursor.getString(0),
                    SimilaritySubjectObservation(
                        kind = SimilaritySubjectKind.GENERAL_OBJECT,
                        category = cursor.getString(1),
                        embedding = VisualEmbeddingCodec.decode(cursor.getBlob(2), cursor.getInt(3)),
                        modelName = cursor.getString(4),
                        pipelineVersion = cursor.getString(5),
                    ),
                )
            }
        }
        return LoadedSimilarityInputs(
            inputs = features.map { (photoId, feature) ->
                SimilarityPhotoInput(
                    photoId = photoId,
                    feature = feature,
                    dateTakenMillis = locations[photoId]?.dateTakenMillis,
                    subjects = subjects[photoId].orEmpty(),
                )
            },
            locations = locations,
        )
    }

    private fun latestAnnotation(photoId: String): PhotoAnnotation? = readableDatabase.rawQuery(
        """
        SELECT caption, tags_json, facets_json, visible_text, subject_mentions_json
        FROM photo_annotations
        WHERE photo_id = ?
        ORDER BY annotation_id DESC
        LIMIT 1
        """.trimIndent(),
        arrayOf(photoId),
    ).use { cursor ->
        if (!cursor.moveToFirst()) return null
        runCatching {
            val parsed = AnnotationContract.parse(
                JSONObject()
                    .put("caption", cursor.getString(0))
                    .put("tags", JSONArray(cursor.getString(1)))
                    .put("facets", JSONObject(cursor.getString(2)))
                    .put("visible_text", cursor.getString(3))
                    .put("subject_mentions", JSONArray(cursor.getString(4))),
            )
            SubjectMarkerPipeline.compose(parsed, subjectMarkers(photoId))
        }.getOrNull()
    }

    @Synchronized
    fun identitySummaries(): List<PersonIdentitySummary> = readableDatabase.rawQuery(
        """
        SELECT identity.identity_id, identity.name, COUNT(template.template_id)
        FROM person_identities AS identity
        LEFT JOIN face_templates AS template ON template.identity_id = identity.identity_id
        GROUP BY identity.identity_id, identity.name
        ORDER BY identity.name COLLATE NOCASE
        """.trimIndent(),
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(PersonIdentitySummary(cursor.getLong(0), cursor.getString(1), cursor.getInt(2)))
            }
        }
    }

    @Synchronized
    fun identityNames(): List<String> = identitySummaries().map(PersonIdentitySummary::name)

    @Synchronized
    fun personIdentityCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM person_identities",
        null,
    ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    @Synchronized
    fun petIdentityCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM pet_identities",
        null,
    ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    @Synchronized
    fun identityCount(): Int = personIdentityCount() + petIdentityCount()

    @Synchronized
    fun localIdentitySummaries(): List<LocalIdentitySummary> {
        val people = identitySummaries().map {
            LocalIdentitySummary(it.id, it.name, it.templateCount, LocalIdentityKind.PERSON)
        }
        val pets = petIdentitySummaries().map {
            LocalIdentitySummary(it.id, it.name, it.templateCount, LocalIdentityKind.PET, it.species)
        }
        return (people + pets).sortedWith(compareBy({ it.kind.ordinal }, { it.name.lowercase() }))
    }

    @Synchronized
    fun faceTemplates(modelName: String): List<FaceTemplateRecord> = readableDatabase.rawQuery(
        """
        SELECT template.template_id, template.identity_id, identity.name,
               template.embedding, template.embedding_dimension, template.model_name
        FROM face_templates AS template
        JOIN person_identities AS identity ON identity.identity_id = template.identity_id
        WHERE template.model_name = ?
        ORDER BY template.template_id
        """.trimIndent(),
        arrayOf(modelName),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    FaceTemplateRecord(
                        id = cursor.getLong(0),
                        identityId = cursor.getLong(1),
                        identityName = cursor.getString(2),
                        embedding = FaceEmbeddingCodec.decode(cursor.getBlob(3), cursor.getInt(4)),
                        modelName = cursor.getString(5),
                    ),
                )
            }
        }
    }

    @Synchronized
    fun registerFaceTemplate(
        name: String,
        sample: FaceSample,
        modelName: String,
        sourcePhotoId: String?,
    ): PersonIdentitySummary {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty() && normalizedName.length <= 40) { "人物名称必须是 1 到 40 个字符" }
        val embedding = FaceEmbeddingCodec.encode(sample.embedding)
        val now = Instant.now().toString()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict(
                "person_identities",
                null,
                ContentValues().apply {
                    put("name", normalizedName)
                    put("created_at", now)
                    put("updated_at", now)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            val identityId = db.rawQuery(
                "SELECT identity_id FROM person_identities WHERE name = ? COLLATE NOCASE",
                arrayOf(normalizedName),
            ).use { cursor ->
                check(cursor.moveToFirst()) { "无法创建人物身份" }
                cursor.getLong(0)
            }
            db.update(
                "person_identities",
                ContentValues().apply {
                    put("name", normalizedName)
                    put("updated_at", now)
                },
                "identity_id = ?",
                arrayOf(identityId.toString()),
            )
            db.insertOrThrow(
                "face_templates",
                null,
                ContentValues().apply {
                    put("identity_id", identityId)
                    put("embedding", embedding)
                    put("embedding_dimension", sample.embedding.size)
                    put("model_name", modelName)
                    if (sourcePhotoId == null) putNull("source_photo_id") else put("source_photo_id", sourcePhotoId)
                    put("bounding_box_json", faceBoxJson(sample.box).toString())
                    put("created_at", now)
                },
            )
            db.setTransactionSuccessful()
            return PersonIdentitySummary(
                id = identityId,
                name = normalizedName,
                templateCount = db.rawQuery(
                    "SELECT COUNT(*) FROM face_templates WHERE identity_id = ?",
                    arrayOf(identityId.toString()),
                ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) },
            )
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun replaceFaceObservations(
        photoId: String,
        samples: List<FaceSample>,
        decisions: List<FaceMatchDecision>,
        modelName: String,
        detectorName: String,
        indexVersion: String = modelName,
    ) {
        require(samples.size == decisions.size)
        val now = Instant.now().toString()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("photo_faces", "photo_id = ? AND model_name = ?", arrayOf(photoId, modelName))
            samples.zip(decisions).forEach { (sample, decision) ->
                db.insertOrThrow(
                    "photo_faces",
                    null,
                    ContentValues().apply {
                        put("photo_id", photoId)
                        put("face_index", sample.faceIndex)
                        put("embedding", FaceEmbeddingCodec.encode(sample.embedding))
                        put("embedding_dimension", sample.embedding.size)
                        put("bounding_box_json", faceBoxJson(sample.box).toString())
                        put("detector_score", sample.detectorScore)
                        put("model_name", modelName)
                        put("pipeline_version", indexVersion)
                        put("match_state", decision.state)
                        if (decision.identityId == null) putNull("matched_identity_id")
                        else put("matched_identity_id", decision.identityId)
                        if (decision.similarity == null) putNull("similarity")
                        else put("similarity", decision.similarity)
                        put("threshold", decision.threshold)
                        put("indexed_at", now)
                    },
                )
            }
            db.insertWithOnConflict(
                "photo_face_index",
                null,
                ContentValues().apply {
                    put("photo_id", photoId)
                    put("model_name", indexVersion)
                    put("detector_name", detectorName)
                    put("face_count", samples.size)
                    put("indexed_at", now)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.delete(
                "photo_face_index",
                "photo_id = ? AND model_name <> ?",
                arrayOf(photoId, indexVersion),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun rematchFaceObservations(modelName: String): Int {
        val templates = faceTemplates(modelName)
        val rows = readableDatabase.rawQuery(
            """
            SELECT face_id, embedding, embedding_dimension
            FROM photo_faces
            WHERE model_name = ?
            """.trimIndent(),
            arrayOf(modelName),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Triple(cursor.getLong(0), cursor.getBlob(1), cursor.getInt(2)))
                }
            }
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            var matched = 0
            rows.forEach { (faceId, blob, dimension) ->
                val decision = FaceMatcher.decide(FaceEmbeddingCodec.decode(blob, dimension), templates)
                if (decision.state == FaceMatcher.MATCHED) matched += 1
                db.update(
                    "photo_faces",
                    ContentValues().apply {
                        put("match_state", decision.state)
                        if (decision.identityId == null) putNull("matched_identity_id")
                        else put("matched_identity_id", decision.identityId)
                        if (decision.similarity == null) putNull("similarity")
                        else put("similarity", decision.similarity)
                        put("threshold", decision.threshold)
                        put("indexed_at", Instant.now().toString())
                    },
                    "face_id = ?",
                    arrayOf(faceId.toString()),
                )
            }
            db.setTransactionSuccessful()
            return matched
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun deleteIdentity(identityId: Long, modelName: String): Int {
        val db = writableDatabase
        val deleted: Int
        db.beginTransaction()
        try {
            db.update(
                "photo_faces",
                ContentValues().apply {
                    put("match_state", FaceMatcher.UNKNOWN)
                    putNull("matched_identity_id")
                },
                "matched_identity_id = ?",
                arrayOf(identityId.toString()),
            )
            deleted = db.delete("person_identities", "identity_id = ?", arrayOf(identityId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (deleted > 0) rematchFaceObservations(modelName)
        return deleted
    }

    @Synchronized
    fun petIdentitySummaries(): List<PetIdentitySummary> = readableDatabase.rawQuery(
        """
        SELECT identity.identity_id, identity.name, COUNT(template.template_id),
               COALESCE(MIN(template.species), '')
        FROM pet_identities AS identity
        LEFT JOIN pet_templates AS template ON template.identity_id = identity.identity_id
        GROUP BY identity.identity_id, identity.name
        ORDER BY identity.name COLLATE NOCASE
        """.trimIndent(),
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    PetIdentitySummary(
                        id = cursor.getLong(0),
                        name = cursor.getString(1),
                        templateCount = cursor.getInt(2),
                        species = cursor.getString(3),
                    ),
                )
            }
        }
    }

    @Synchronized
    fun petTemplates(modelName: String): List<PetTemplateRecord> = readableDatabase.rawQuery(
        """
        SELECT template.template_id, template.identity_id, identity.name, template.species,
               template.embedding, template.embedding_dimension, template.model_name
        FROM pet_templates AS template
        JOIN pet_identities AS identity ON identity.identity_id = template.identity_id
        WHERE template.model_name = ?
        ORDER BY template.template_id
        """.trimIndent(),
        arrayOf(modelName),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    PetTemplateRecord(
                        id = cursor.getLong(0),
                        identityId = cursor.getLong(1),
                        identityName = cursor.getString(2),
                        species = cursor.getString(3),
                        embedding = FaceEmbeddingCodec.decode(cursor.getBlob(4), cursor.getInt(5)),
                        modelName = cursor.getString(6),
                    ),
                )
            }
        }
    }

    @Synchronized
    fun registerPetTemplate(
        name: String,
        sample: PetSample,
        modelName: String,
        sourcePhotoId: String?,
    ): PetIdentitySummary {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty() && normalizedName.length <= 40) {
            "宠物名称必须是 1 到 40 个字符"
        }
        val embedding = FaceEmbeddingCodec.encode(sample.embedding)
        val now = Instant.now().toString()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict(
                "pet_identities",
                null,
                ContentValues().apply {
                    put("name", normalizedName)
                    put("created_at", now)
                    put("updated_at", now)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            val identityId = db.rawQuery(
                "SELECT identity_id FROM pet_identities WHERE name = ? COLLATE NOCASE",
                arrayOf(normalizedName),
            ).use { cursor ->
                check(cursor.moveToFirst()) { "无法创建宠物身份" }
                cursor.getLong(0)
            }
            val existingSpecies = db.rawQuery(
                "SELECT DISTINCT species FROM pet_templates WHERE identity_id = ?",
                arrayOf(identityId.toString()),
            ).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            require(existingSpecies.isEmpty() || existingSpecies == setOf(sample.species)) {
                "同一个宠物名称不能同时注册为猫和狗"
            }
            db.update(
                "pet_identities",
                ContentValues().apply {
                    put("name", normalizedName)
                    put("updated_at", now)
                },
                "identity_id = ?",
                arrayOf(identityId.toString()),
            )
            db.insertOrThrow(
                "pet_templates",
                null,
                ContentValues().apply {
                    put("identity_id", identityId)
                    put("species", sample.species)
                    put("embedding", embedding)
                    put("embedding_dimension", sample.embedding.size)
                    put("model_name", modelName)
                    if (sourcePhotoId == null) putNull("source_photo_id") else put("source_photo_id", sourcePhotoId)
                    put("bounding_box_json", faceBoxJson(sample.box).toString())
                    put("created_at", now)
                },
            )
            db.setTransactionSuccessful()
            return PetIdentitySummary(
                id = identityId,
                name = normalizedName,
                templateCount = db.rawQuery(
                    "SELECT COUNT(*) FROM pet_templates WHERE identity_id = ?",
                    arrayOf(identityId.toString()),
                ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) },
                species = sample.species,
            )
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun replacePetObservations(
        photoId: String,
        samples: List<PetSample>,
        decisions: List<PetMatchDecision>,
        modelName: String,
        detectorName: String,
        indexVersion: String,
    ) {
        require(samples.size == decisions.size)
        val now = Instant.now().toString()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("photo_pets", "photo_id = ? AND model_name = ?", arrayOf(photoId, modelName))
            samples.zip(decisions).forEach { (sample, decision) ->
                db.insertOrThrow(
                    "photo_pets",
                    null,
                    ContentValues().apply {
                        put("photo_id", photoId)
                        put("pet_index", sample.petIndex)
                        put("species", sample.species)
                        put("embedding", FaceEmbeddingCodec.encode(sample.embedding))
                        put("embedding_dimension", sample.embedding.size)
                        put("bounding_box_json", faceBoxJson(sample.box).toString())
                        put("detector_score", sample.detectorScore)
                        put("model_name", modelName)
                        put("pipeline_version", indexVersion)
                        put("match_state", decision.state)
                        if (decision.identityId == null) putNull("matched_identity_id")
                        else put("matched_identity_id", decision.identityId)
                        if (decision.similarity == null) putNull("similarity")
                        else put("similarity", decision.similarity)
                        put("threshold", decision.threshold)
                        put("indexed_at", now)
                    },
                )
            }
            db.insertWithOnConflict(
                "photo_pet_index",
                null,
                ContentValues().apply {
                    put("photo_id", photoId)
                    put("pipeline_version", indexVersion)
                    put("detector_name", detectorName)
                    put("pet_count", samples.size)
                    put("indexed_at", now)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.delete(
                "photo_pet_index",
                "photo_id = ? AND pipeline_version <> ?",
                arrayOf(photoId, indexVersion),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun rematchPetObservations(modelName: String): Int {
        val templates = petTemplates(modelName)
        val rows = readableDatabase.rawQuery(
            """
            SELECT pet_id, species, embedding, embedding_dimension
            FROM photo_pets
            WHERE model_name = ?
            """.trimIndent(),
            arrayOf(modelName),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PetObservationEmbedding(
                            cursor.getLong(0),
                            cursor.getString(1),
                            cursor.getBlob(2),
                            cursor.getInt(3),
                        ),
                    )
                }
            }
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            var matched = 0
            rows.forEach { row ->
                val decision = PetMatcher.decide(
                    FaceEmbeddingCodec.decode(row.embedding, row.dimension),
                    row.species,
                    templates,
                )
                if (decision.state == PetMatcher.MATCHED) matched += 1
                db.update(
                    "photo_pets",
                    ContentValues().apply {
                        put("match_state", decision.state)
                        if (decision.identityId == null) putNull("matched_identity_id")
                        else put("matched_identity_id", decision.identityId)
                        if (decision.similarity == null) putNull("similarity")
                        else put("similarity", decision.similarity)
                        put("threshold", decision.threshold)
                        put("indexed_at", Instant.now().toString())
                    },
                    "pet_id = ?",
                    arrayOf(row.id.toString()),
                )
            }
            db.setTransactionSuccessful()
            return matched
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun deletePetIdentity(identityId: Long, modelName: String): Int {
        val db = writableDatabase
        val deleted: Int
        db.beginTransaction()
        try {
            db.update(
                "photo_pets",
                ContentValues().apply {
                    put("match_state", PetMatcher.UNKNOWN)
                    putNull("matched_identity_id")
                },
                "matched_identity_id = ?",
                arrayOf(identityId.toString()),
            )
            deleted = db.delete("pet_identities", "identity_id = ?", arrayOf(identityId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (deleted > 0) rematchPetObservations(modelName)
        return deleted
    }

    private data class PetObservationEmbedding(
        val id: Long,
        val species: String,
        val embedding: ByteArray,
        val dimension: Int,
    )

    @Synchronized
    fun matchedPeople(photoId: String): List<RecognizedSubject> = readableDatabase.rawQuery(
        """
        SELECT DISTINCT identity.name
        FROM photo_faces AS face
        JOIN person_identities AS identity ON identity.identity_id = face.matched_identity_id
        WHERE face.photo_id = ? AND face.match_state = 'matched'
        ORDER BY identity.name COLLATE NOCASE
        """.trimIndent(),
        arrayOf(photoId),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(RecognizedSubject(cursor.getString(0), "人物"))
        }
    }

    @Synchronized
    fun matchedPets(photoId: String): List<RecognizedSubject> = readableDatabase.rawQuery(
        """
        SELECT DISTINCT identity.name
        FROM photo_pets AS pet
        JOIN pet_identities AS identity ON identity.identity_id = pet.matched_identity_id
        WHERE pet.photo_id = ? AND pet.match_state = 'matched'
        ORDER BY identity.name COLLATE NOCASE
        """.trimIndent(),
        arrayOf(photoId),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(RecognizedSubject(cursor.getString(0), "宠物"))
        }
    }

    @Synchronized
    fun subjectMarkers(photoId: String): List<LocalSubjectMarker> {
        val people = readableDatabase.rawQuery(
            """
            SELECT face.face_index, face.bounding_box_json, face.matched_identity_id, identity.name
            FROM photo_faces AS face
            LEFT JOIN person_identities AS identity ON identity.identity_id = face.matched_identity_id
            WHERE face.photo_id = ? AND face.model_name = ?
            ORDER BY face.face_index
            """.trimIndent(),
            arrayOf(photoId, LocalFaceEngine.EMBEDDING_MODEL_NAME),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val index = cursor.getInt(0)
                    add(
                        LocalSubjectMarker(
                            marker = "P${index + 1}",
                            kind = LocalIdentityKind.PERSON,
                            observationIndex = index,
                            box = parseFaceBox(cursor.getString(1)),
                            matchedIdentityId = if (cursor.isNull(2)) null else cursor.getLong(2),
                            matchedName = if (cursor.isNull(3)) null else cursor.getString(3),
                        ),
                    )
                }
            }
        }
        val pets = readableDatabase.rawQuery(
            """
            SELECT pet.pet_index, pet.bounding_box_json, pet.matched_identity_id, identity.name
            FROM photo_pets AS pet
            LEFT JOIN pet_identities AS identity ON identity.identity_id = pet.matched_identity_id
            WHERE pet.photo_id = ? AND pet.model_name = ?
            ORDER BY pet.pet_index
            """.trimIndent(),
            arrayOf(photoId, LocalPetEngine.EMBEDDING_MODEL_NAME),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val index = cursor.getInt(0)
                    add(
                        LocalSubjectMarker(
                            marker = "PET${index + 1}",
                            kind = LocalIdentityKind.PET,
                            observationIndex = index,
                            box = parseFaceBox(cursor.getString(1)),
                            matchedIdentityId = if (cursor.isNull(2)) null else cursor.getLong(2),
                            matchedName = if (cursor.isNull(3)) null else cursor.getString(3),
                        ),
                    )
                }
            }
        }
        return people + pets
    }

    @Synchronized
    fun insertAnnotation(
        photoId: String,
        annotation: PhotoAnnotation,
        modelName: String,
        promptVersion: String,
    ): Long {
        val valid = FacetRules.validate(
            annotation.copy(
                recognizedSubjects = emptyList(),
            ),
        )
        val serialized = AnnotationContract.toJson(valid)
        return writableDatabase.insertOrThrow(
            "photo_annotations",
            null,
            ContentValues().apply {
                put("photo_id", photoId)
                put("caption", valid.caption)
                put("tags_json", serialized.getJSONArray("tags").toString())
                put("facets_json", serialized.getJSONObject("facets").toString())
                put("visible_text", valid.visibleText)
                put("recognized_subjects_json", "[]")
                put("subject_mentions_json", serialized.getJSONArray("subject_mentions").toString())
                put("provider", "ollama")
                put("model_name", modelName)
                put("prompt_version", promptVersion)
                put("created_at", Instant.now().toString())
                put("search_text", FacetRules.searchText(valid))
            },
        )
    }

    @Synchronized
    fun galleryPhotos(): List<GalleryPhotoCard> {
        val sql = """
            WITH chosen_location AS (
                SELECT photo_id, MIN(uri) AS uri
                FROM media_locations
                WHERE accessible = 1
                GROUP BY photo_id
            ), latest AS (
                SELECT photo_id, MAX(annotation_id) AS annotation_id
                FROM photo_annotations
                GROUP BY photo_id
            )
            SELECT
                location.photo_id,
                location.uri,
                location.display_name,
                location.date_taken_millis,
                annotation.annotation_id,
                annotation.caption,
                annotation.tags_json,
                annotation.facets_json,
                annotation.visible_text,
                annotation.subject_mentions_json,
                annotation.model_name,
                annotation.prompt_version,
                annotation.created_at
            FROM chosen_location AS chosen
            JOIN media_locations AS location ON location.uri = chosen.uri
            LEFT JOIN latest ON latest.photo_id = location.photo_id
            LEFT JOIN photo_annotations AS annotation ON annotation.annotation_id = latest.annotation_id
            WHERE location.accessible = 1
            ORDER BY
                CASE WHEN location.date_taken_millis IS NULL THEN 1 ELSE 0 END,
                location.date_taken_millis DESC,
                location.photo_id ASC
        """.trimIndent()
        return readableDatabase.rawQuery(sql, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val photoId = cursor.getString(0)
                    val annotation = if (cursor.isNull(4)) {
                        null
                    } else {
                        val parsed = AnnotationContract.parse(
                            JSONObject()
                                .put("caption", cursor.getString(5))
                                .put("tags", JSONArray(cursor.getString(6)))
                                .put("facets", JSONObject(cursor.getString(7)))
                                .put("visible_text", cursor.getString(8))
                                .put("subject_mentions", JSONArray(cursor.getString(9))),
                        )
                        SubjectMarkerPipeline.compose(parsed, subjectMarkers(photoId))
                    }
                    add(
                        GalleryPhotoCard(
                            photoId = photoId,
                            uri = cursor.getString(1),
                            displayName = cursor.getString(2),
                            dateTakenMillis = if (cursor.isNull(3)) null else cursor.getLong(3),
                            annotation = annotation,
                            modelName = if (cursor.isNull(10)) null else cursor.getString(10),
                            promptVersion = if (cursor.isNull(11)) null else cursor.getString(11),
                            annotatedAt = if (cursor.isNull(12)) null else cursor.getString(12),
                        ),
                    )
                }
            }
        }
    }

    @Synchronized
    fun search(query: String, limit: Int = 100): List<PhotoCard> {
        require(limit in 1..500)
        val normalized = query.trim().lowercase()
        val filter = if (normalized.isEmpty()) {
            ""
        } else {
            """
            AND (
                annotation.search_text LIKE ? ESCAPE '\'
                OR EXISTS (
                    SELECT 1
                    FROM photo_faces AS matched_face
                    JOIN person_identities AS matched_identity
                      ON matched_identity.identity_id = matched_face.matched_identity_id
                    WHERE matched_face.photo_id = annotation.photo_id
                      AND matched_face.match_state = 'matched'
                      AND lower(matched_identity.name) LIKE ? ESCAPE '\'
                )
                OR EXISTS (
                    SELECT 1
                    FROM photo_pets AS matched_pet
                    JOIN pet_identities AS matched_pet_identity
                      ON matched_pet_identity.identity_id = matched_pet.matched_identity_id
                    WHERE matched_pet.photo_id = annotation.photo_id
                      AND matched_pet.match_state = 'matched'
                      AND lower(matched_pet_identity.name) LIKE ? ESCAPE '\'
                )
            )
            """.trimIndent()
        }
        val args = if (normalized.isEmpty()) {
            emptyArray()
        } else {
            val pattern = "%${escapeLike(normalized)}%"
            arrayOf(pattern, pattern, pattern)
        }
        val sql = """
            WITH latest AS (
                SELECT photo_id, MAX(annotation_id) AS annotation_id
                FROM photo_annotations
                GROUP BY photo_id
            ), chosen_location AS (
                SELECT photo_id, MIN(uri) AS uri
                FROM media_locations
                WHERE accessible = 1
                GROUP BY photo_id
            )
            SELECT
                annotation.photo_id,
                location.uri,
                location.display_name,
                annotation.caption,
                annotation.tags_json,
                annotation.facets_json,
                annotation.visible_text,
                annotation.recognized_subjects_json,
                annotation.subject_mentions_json,
                annotation.model_name,
                annotation.prompt_version,
                annotation.created_at,
                location.date_taken_millis
            FROM latest
            JOIN photo_annotations AS annotation ON annotation.annotation_id = latest.annotation_id
            JOIN chosen_location AS chosen ON chosen.photo_id = annotation.photo_id
            JOIN media_locations AS location ON location.uri = chosen.uri
            WHERE location.accessible = 1
            $filter
            ORDER BY COALESCE(location.date_taken_millis, location.modified_seconds * 1000) DESC,
                     annotation.annotation_id DESC
            LIMIT $limit
        """.trimIndent()
        return readableDatabase.rawQuery(sql, args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toPhotoCard())
            }
        }
    }

    @Synchronized
    fun accessibleCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(DISTINCT photo_id) FROM media_locations WHERE accessible = 1",
        null,
    ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    @Synchronized
    fun annotationCount(): Int = readableDatabase.rawQuery(
        """
        SELECT COUNT(DISTINCT annotation.photo_id)
        FROM photo_annotations AS annotation
        JOIN media_locations AS location ON location.photo_id = annotation.photo_id
        WHERE location.accessible = 1
        """.trimIndent(),
        null,
    ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    @Synchronized
    fun searchAvailability(): SearchAvailability {
        val cards = search("", limit = 500)
        return SearchAvailability.derive(
            annotationCount = cards.map(PhotoCard::photoId).distinct().size,
            suggestions = SuggestionBuilder.from(cards.map(PhotoCard::annotation)),
        )
    }

    private fun Cursor.toPhotoCard(): PhotoCard {
        val photoId = getString(0)
        val combined = JSONObject()
            .put("caption", getString(3))
            .put("tags", JSONArray(getString(4)))
            .put("facets", JSONObject(getString(5)))
            .put("visible_text", getString(6))
            .put("subject_mentions", JSONArray(getString(8)))
        val parsed = AnnotationContract.parse(combined)
        val composed = SubjectMarkerPipeline.compose(parsed, subjectMarkers(photoId))
        return PhotoCard(
            photoId = photoId,
            uri = getString(1),
            displayName = getString(2),
            caption = composed.caption,
            annotation = composed,
            modelName = getString(9),
            promptVersion = getString(10),
            annotatedAt = getString(11),
            dateTakenMillis = if (isNull(12)) null else getLong(12),
        )
    }

    private fun escapeLike(value: String): String = value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    private fun faceBoxJson(box: FaceBox): JSONObject = JSONObject()
        .put("left", box.left)
        .put("top", box.top)
        .put("right", box.right)
        .put("bottom", box.bottom)

    private fun parseFaceBox(value: String): FaceBox = JSONObject(value).let { box ->
        FaceBox(
            left = box.getDouble("left").toFloat(),
            top = box.getDouble("top").toFloat(),
            right = box.getDouble("right").toFloat(),
            bottom = box.getDouble("bottom").toFloat(),
        )
    }
}
