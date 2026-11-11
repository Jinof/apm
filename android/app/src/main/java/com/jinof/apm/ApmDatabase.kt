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
) : SQLiteOpenHelper(context, databaseName, null, 8) {
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
        }
        if (oldVersion < 7) {
            createVisualSimilarityTables(db)
        } else if (oldVersion < 8) {
            // SQLiteOpenHelper runs upgrades in a transaction. Preserve every retained
            // vector and its original provenance; only the retired grid columns go away.
            createVisualFeatureTable(db, "photo_visual_features_without_grid")
            db.execSQL("""
                INSERT INTO photo_visual_features_without_grid
                    (photo_id, global_embedding, embedding_dimension, model_name, pipeline_version, indexed_at)
                SELECT photo_id, global_embedding, embedding_dimension, model_name, pipeline_version, indexed_at
                FROM photo_visual_features
            """.trimIndent())
            db.execSQL("DROP TABLE photo_visual_features")
            db.execSQL("ALTER TABLE photo_visual_features_without_grid RENAME TO photo_visual_features")
            db.execSQL("CREATE INDEX photo_visual_features_pipeline ON photo_visual_features(model_name, pipeline_version)")
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

    // Schema v8 keeps retired tables so upgrades never discard user-owned archives.
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

    private fun createVisualFeatureTable(db: SQLiteDatabase, tableName: String = "photo_visual_features") {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $tableName (
                photo_id TEXT PRIMARY KEY REFERENCES photo_assets(photo_id) ON DELETE CASCADE,
                global_embedding BLOB NOT NULL,
                embedding_dimension INTEGER NOT NULL CHECK(embedding_dimension > 0),
                model_name TEXT NOT NULL,
                pipeline_version TEXT NOT NULL,
                indexed_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createVisualSimilarityTables(db: SQLiteDatabase) {
        createVisualFeatureTable(db)
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
    fun pendingAllPhotos(modelName: String, promptVersion: String): List<PendingPhoto> =
        pendingAllPhotos(modelName, listOf(promptVersion))

    @Synchronized
    fun pendingAllPhotos(modelName: String, promptVersions: List<String>): List<PendingPhoto> {
        require(promptVersions.isNotEmpty())
        val sql = """
            SELECT location.photo_id, MIN(location.uri) AS uri, MIN(location.display_name) AS display_name
            FROM media_locations AS location
            WHERE location.accessible = 1
              AND NOT EXISTS (
                  SELECT 1 FROM photo_annotations AS annotation
                  WHERE annotation.photo_id = location.photo_id
                    AND annotation.provider = 'openai-compatible'
                    AND annotation.model_name = ?
                    AND annotation.prompt_version IN (${promptVersions.joinToString(",") { "?" }})
            )
            GROUP BY location.photo_id
            ORDER BY COALESCE(MAX(location.date_taken_millis), MAX(location.modified_seconds) * 1000) DESC
        """.trimIndent()
        return readableDatabase.rawQuery(sql, (listOf(modelName) + promptVersions).toTypedArray()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(PendingPhoto(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                }
            }
        }
    }

    @Synchronized
    fun pendingSelectedPhotos(modelName: String, promptVersion: String): List<PendingPhoto> =
        pendingSelectedPhotos(modelName, listOf(promptVersion))

    @Synchronized
    fun pendingSelectedPhotos(modelName: String, promptVersions: List<String>): List<PendingPhoto> {
        require(promptVersions.isNotEmpty())
        val sql = """
            SELECT location.photo_id, MIN(location.uri) AS uri, MIN(location.display_name) AS display_name
            FROM annotation_selection AS selection
            JOIN media_locations AS location ON location.photo_id = selection.photo_id
            WHERE location.accessible = 1
              AND NOT EXISTS (
                  SELECT 1 FROM photo_annotations AS annotation
                  WHERE annotation.photo_id = location.photo_id
                    AND annotation.provider = 'openai-compatible'
                    AND annotation.model_name = ?
                    AND annotation.prompt_version IN (${promptVersions.joinToString(",") { "?" }})
              )
            GROUP BY location.photo_id
            ORDER BY MIN(selection.selected_at), location.photo_id
        """.trimIndent()
        return readableDatabase.rawQuery(sql, (listOf(modelName) + promptVersions).toTypedArray()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(PendingPhoto(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                }
            }
        }
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
                put("provider", "openai-compatible")
                put("model_name", modelName)
                put("prompt_version", promptVersion)
                put("created_at", Instant.now().toString())
                put("search_text", FacetRules.searchText(valid))
            },
        )
    }

    @Synchronized
    fun insertAnnotationGroup(
        annotations: List<Pair<String, PhotoAnnotation>>, modelName: String, promptVersion: String,
    ) {
        require(annotations.size in 1..8 && annotations.map { it.first }.distinct().size == annotations.size)
        val db = writableDatabase
        db.beginTransaction()
        try {
            annotations.forEach { (id, annotation) -> insertAnnotation(id, annotation, modelName, promptVersion) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
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
                annotation.created_at,
                annotation.recognized_subjects_json
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
                        val stored = JSONObject()
                                .put("caption", cursor.getString(5))
                                .put("tags", JSONArray(cursor.getString(6)))
                                .put("facets", JSONObject(cursor.getString(7)))
                                .put("visible_text", cursor.getString(8))
                                .put("subject_mentions", JSONArray(cursor.getString(9)))
                        val parsed = parseStoredAnnotation(stored, cursor.getString(13))
                        legacyAnnotation(photoId, parsed)
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
        val parsed = parseStoredAnnotation(combined, getString(7))
        val composed = legacyAnnotation(photoId, parsed)
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

    private fun parseStoredAnnotation(value: JSONObject, legacySubjects: String): PhotoAnnotation {
        val parsed = AnnotationContract.parse(value)
        if (parsed.subjectMentions.isNotEmpty()) return parsed
        value.remove("subject_mentions")
        value.put("recognized_subjects", JSONArray(legacySubjects))
        return AnnotationContract.parse(value)
    }

    // Read compatibility only: never detect, match, register, or rewrite retired records.
    private fun legacyAnnotation(photoId: String, annotation: PhotoAnnotation): PhotoAnnotation {
        if (annotation.subjectMentions.isEmpty()) return annotation
        val names = mutableMapOf<String, String>()
        val subjects = mutableListOf<RecognizedSubject>()
        fun read(table: String, identities: String, index: String, prefix: String, kind: String, model: String) {
            readableDatabase.rawQuery(
                "SELECT observation.$index, identity.name FROM $table AS observation " +
                    "LEFT JOIN $identities AS identity ON identity.identity_id = observation.matched_identity_id " +
                    "WHERE observation.photo_id = ? AND observation.model_name = ? ORDER BY observation.$index",
                arrayOf(photoId, model),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val number = cursor.getInt(0) + 1
                    val name = if (cursor.isNull(1)) null else cursor.getString(1)
                    names["$prefix$number"] = name ?: "$kind$number"
                    if (name != null) subjects += RecognizedSubject(name, kind)
                }
            }
        }
        read("photo_faces", "person_identities", "face_index", "P", "人物", "opencv-sface-2021dec@0ba9fbfa01b5")
        read("photo_pets", "pet_identities", "pet_index", "PET", "宠物", "litert-mobilenet-v3-small-float32-imagenet")
        val pattern = Regex("(?<![A-Z0-9])(?:PET[1-9][0-9]*|P[1-9][0-9]*)(?![A-Z0-9])")
        fun substitute(text: String) = pattern.replace(text) { names[it.value] ?: it.value }
        return FacetRules.validate(annotation.copy(
            caption = substitute(annotation.caption),
            subjectMentions = annotation.subjectMentions.map { it.copy(description = substitute(it.description)) },
            recognizedSubjects = subjects.distinctBy { "${it.kind}:${it.name.lowercase()}" },
        ))
    }

    private fun escapeLike(value: String): String = value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

}
