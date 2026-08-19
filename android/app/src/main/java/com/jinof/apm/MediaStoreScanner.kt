package com.jinof.apm

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.MediaStore
import java.security.MessageDigest

class MediaStoreScanner(
    private val context: Context,
    private val database: ApmDatabase,
) {
    fun scan(onProgress: (processed: Int, total: Int, name: String) -> Unit): ScanReport {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_TAKEN,
        )
        val photos = mutableListOf<ScannedPhoto>()
        val errors = mutableListOf<String>()
        var hashed = 0
        var reused = 0
        resolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC",
        )?.use { cursor ->
            val total = cursor.count
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val takenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            var processed = 0
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(collection, id).toString()
                val name = cursor.getString(nameColumn) ?: "照片-$id"
                val type = cursor.getString(typeColumn) ?: "image/unknown"
                val size = cursor.getLong(sizeColumn).coerceAtLeast(0)
                val modified = cursor.getLong(modifiedColumn).coerceAtLeast(0)
                val dateTaken = if (cursor.isNull(takenColumn)) null else cursor.getLong(takenColumn)
                processed += 1
                onProgress(processed, total, name)
                try {
                    val known = database.knownLocation(uri)
                    val photoId = if (
                        known != null && known.byteSize == size && known.modifiedSeconds == modified
                    ) {
                        reused += 1
                        known.photoId
                    } else {
                        hashed += 1
                        sha256(resolver, uri)
                    }
                    photos += ScannedPhoto(
                        uri = uri,
                        mediaStoreId = id,
                        photoId = photoId,
                        displayName = name,
                        mediaType = type,
                        byteSize = size,
                        modifiedSeconds = modified,
                        dateTakenMillis = dateTaken,
                    )
                } catch (error: Exception) {
                    errors += "$name：${error.message ?: error.javaClass.simpleName}"
                }
            }
        } ?: throw IllegalStateException("系统没有返回可读取的 MediaStore 游标")

        val inaccessible = database.replaceVisibleSnapshot(photos)
        return ScanReport(
            visible = photos.size,
            hashed = hashed,
            reused = reused,
            inaccessible = inaccessible,
            errors = errors,
        )
    }

    fun scanSelected(
        uris: List<Uri>,
        onProgress: (processed: Int, total: Int, name: String) -> Unit,
    ): SelectedScanReport {
        val resolver = context.contentResolver
        val selectedUris = uris.distinctBy(Uri::toString)
        val photos = mutableListOf<ScannedPhoto>()
        val errors = mutableListOf<String>()
        var hashed = 0
        selectedUris.forEachIndexed { index, uri ->
            val metadata = selectedMetadata(resolver, uri, index)
            onProgress(index + 1, selectedUris.size, metadata.displayName)
            try {
                val uriText = uri.toString()
                hashed += 1
                val photoId = sha256(resolver, uriText)
                photos += ScannedPhoto(
                    uri = uriText,
                    mediaStoreId = mediaStoreId(uri),
                    photoId = photoId,
                    displayName = metadata.displayName,
                    mediaType = resolver.getType(uri) ?: "image/unknown",
                    byteSize = metadata.byteSize,
                    modifiedSeconds = metadata.modifiedSeconds,
                    dateTakenMillis = metadata.dateTakenMillis,
                )
            } catch (error: Exception) {
                errors += "${metadata.displayName}：${error.message ?: error.javaClass.simpleName}"
            }
        }
        database.upsertAccessiblePhotos(photos)
        return SelectedScanReport(
            scan = ScanReport(
                visible = photos.size,
                hashed = hashed,
                reused = 0,
                inaccessible = 0,
                errors = errors,
            ),
            photoIds = photos.map(ScannedPhoto::photoId).distinct(),
        )
    }

    private fun selectedMetadata(
        resolver: ContentResolver,
        uri: Uri,
        index: Int,
    ): SelectedMetadata {
        var displayName = "所选照片-${index + 1}"
        var byteSize = 0L
        var modifiedSeconds = 0L
        var dateTakenMillis: Long? = null
        runCatching {
            resolver.query(
                uri,
                arrayOf(
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE,
                    MediaStore.Images.Media.DATE_MODIFIED,
                    MediaStore.Images.Media.DATE_TAKEN,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val modifiedColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                    val takenColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                    if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                        displayName = cursor.getString(nameColumn)
                    }
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                        byteSize = cursor.getLong(sizeColumn).coerceAtLeast(0)
                    }
                    if (modifiedColumn >= 0 && !cursor.isNull(modifiedColumn)) {
                        modifiedSeconds = cursor.getLong(modifiedColumn).coerceAtLeast(0)
                    }
                    if (takenColumn >= 0 && !cursor.isNull(takenColumn)) {
                        dateTakenMillis = cursor.getLong(takenColumn).takeIf { it > 0 }
                    }
                }
            }
        }
        if (displayName == "所选照片-${index + 1}" || byteSize == 0L) {
            runCatching {
                resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                            displayName = cursor.getString(nameColumn)
                        }
                        if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                            byteSize = cursor.getLong(sizeColumn).coerceAtLeast(0)
                        }
                    }
                }
            }
        }
        if (byteSize == 0L) {
            byteSize = runCatching {
                resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                    descriptor.length.coerceAtLeast(0)
                } ?: 0
            }.getOrDefault(0)
        }
        return SelectedMetadata(displayName, byteSize, modifiedSeconds, dateTakenMillis)
    }

    private fun mediaStoreId(uri: Uri): Long = uri.lastPathSegment?.toLongOrNull()
        ?: (uri.toString().hashCode().toLong() and Long.MAX_VALUE)

    private fun sha256(resolver: ContentResolver, uriText: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val uri = android.net.Uri.parse(uriText)
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        } ?: throw IllegalStateException("无法读取照片内容")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class SelectedMetadata(
        val displayName: String,
        val byteSize: Long,
        val modifiedSeconds: Long,
        val dateTakenMillis: Long?,
    )
}
