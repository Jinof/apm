package com.jinof.apm

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteException
import android.graphics.Bitmap
import android.media.ExifInterface
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogHeatmapTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "heatmap-${UUID.randomUUID()}.sqlite3"
    private val day = LocalDate.of(2026, 8, 1)
    private val zone = ZoneId.systemDefault()

    @After fun cleanup() { context.deleteDatabase(name) }

    private fun photo(id: Int, date: LocalDate?) = ScannedPhoto(
        "content://media/external/images/media/$id", id.toLong(), id.toString().padStart(64, '0'),
        "fixture-$id", "image/jpeg", 10, 1, date?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
    )

    @Test fun pickerEventsCountDistinctAccessibleContentAndOnlyRebuildChangedYears() {
        ApmDatabase(context, name).use { db ->
            val a = photo(1, day)
            val b = photo(2, day.plusDays(1))
            val c = photo(3, day.plusYears(1))
            db.upsertAccessiblePhotos(listOf(a, b))
            val first = db.preparedHeatmap()
            db.replaceAnnotationSelection(listOf(b.photoId))
            db.upsertAccessiblePhotos(listOf(b, b, photo(4, null)))
            assertSame(first, db.preparedHeatmap())
            db.upsertAccessiblePhotos(listOf(b, c))
            val added = db.preparedHeatmap()
            assertSame(first.years[2026], added.years[2026])
            assertEquals(1, added.years.getValue(2027).totalCount)
            val alias = a.copy(uri = "content://z-alternate/1", dateTakenMillis = b.dateTakenMillis)
            db.upsertAccessiblePhotos(listOf(alias))
            assertSame(added, db.preparedHeatmap())
            db.upsertAccessiblePhotos(emptyList(), listOf(a.uri))
            val moved = db.preparedHeatmap().years.getValue(2026)
            assertNull(moved.selection(PhotoHeatmapGranularity.DAY, day))
            assertEquals(2, moved.selection(PhotoHeatmapGranularity.DAY, day.plusDays(1))?.count)
            db.upsertAccessiblePhotos(emptyList(), listOf(alias.uri, alias.uri))
            assertEquals(1, db.preparedHeatmap().years.getValue(2026).totalCount)
            // Same URI now contains a different content id, with no known capture time.
            val oldAndNew = db.upsertAccessiblePhotos(listOf(b.copy(photoId = "f".repeat(64), dateTakenMillis = null)))
            assertEquals(setOf(b.photoId, "f".repeat(64)), oldAndNew)
            assertFalse(db.preparedHeatmap().years.containsKey(2026))
            assertEquals(listOf("f".repeat(64)), db.galleryPhotos(oldAndNew).map { it.photoId })
            db.replaceVisibleSnapshot(emptyList())
            assertEquals(0, db.preparedHeatmap().years.values.sumOf { it.totalCount })
        }
    }

    @Test fun oldCatalogIsBackfilledAndDateBoundaryMovesWithTimeZone() {
        val a = photo(1, day).copy(dateTakenMillis = Instant.parse("2026-01-01T01:00:00Z").toEpochMilli())
        ApmDatabase(context, name).use { db ->
            db.upsertAccessiblePhotos(listOf(a))
            val sql = db.writableDatabase
            listOf("picker_locations", "heatmap_days", "heatmap_state").forEach { sql.execSQL("DROP TABLE $it") }
            sql.version = 8
        }
        ApmDatabase(context, name).use { db ->
            val utc = db.preparedHeatmap(ZoneId.of("UTC"))
            assertEquals(1, utc.years.getValue(2026).selection(PhotoHeatmapGranularity.DAY, LocalDate.of(2026, 1, 1))?.count)
            val la = db.preparedHeatmap(ZoneId.of("America/Los_Angeles"))
            assertEquals(2025, la.initialYear)
            assertEquals(1, la.years.getValue(2025).selection(PhotoHeatmapGranularity.MONTH, LocalDate.of(2025, 12, 1))?.count)
            assertEquals(a.photoId, db.galleryPhotos().single().photoId)
        }
    }

    @Test fun failedCounterWriteRollsBackCatalogAndRetryCountsOnce() {
        ApmDatabase(context, name).use { db ->
            db.upsertAccessiblePhotos(listOf(photo(1, day)))
            val sql = db.writableDatabase
            sql.execSQL("""CREATE TRIGGER fail_counter BEFORE UPDATE ON heatmap_days
                BEGIN SELECT RAISE(ABORT, 'injected failure'); END""")
            assertThrows(SQLiteException::class.java) { db.upsertAccessiblePhotos(listOf(photo(2, day))) }
            assertEquals(1, db.galleryPhotos().size)
            assertEquals(1, db.preparedHeatmap().years.getValue(day.year).totalCount)
            sql.execSQL("DROP TRIGGER fail_counter")
            repeat(2) { db.upsertAccessiblePhotos(listOf(photo(2, day))) }
            assertEquals(2, db.preparedHeatmap().years.getValue(day.year).totalCount)
        }
    }

    @Test fun pickerImportAndProviderDeletionUpdateCountsBeforeOpeningTheHeatmap() {
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "APM-070-${UUID.randomUUID()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.IS_PENDING, 1)
            put(MediaStore.Images.Media.DATE_TAKEN, day.atStartOfDay(zone).toInstant().toEpochMilli())
        }))
        ApmDatabase(context, name).use { db ->
            val scanner = MediaStoreScanner(context, db)
            try {
                context.contentResolver.openOutputStream(uri)!!.use {
                    val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
                    bitmap.recycle()
                }
                context.contentResolver.openFileDescriptor(uri, "rw")!!.use {
                    val exif = ExifInterface(it.fileDescriptor)
                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:08:01 12:00:00")
                    exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00")
                    exif.saveAttributes()
                }
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                repeat(2) {
                    val report = scanner.scanSelected(listOf(uri, uri)) { _, _, _ -> }
                    assertTrue(report.scan.errors.toString(), report.scan.errors.isEmpty())
                }
                assertNotNull("The provider should report the fixture's dated, offset-aware EXIF", db.galleryPhotos().single().dateTakenMillis)
                assertEquals(1, db.preparedHeatmap().years.values.sumOf { it.totalCount })
                assertTrue(scanner.reconcilePickerAccess().isEmpty())
            } finally {
                context.contentResolver.delete(uri, null, null)
            }
            assertEquals(1, scanner.reconcilePickerAccess().size)
            assertTrue(db.galleryPhotos().isEmpty())
            assertEquals(0, db.preparedHeatmap().years.values.sumOf { it.totalCount })
        }
    }
}
