package com.jinof.apm

import android.content.Context
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class PhotoWallVisualSeedTest {
    @Test
    fun seedExplicitPublicPhotoWallFixture() {
        assumeTrue(
            "This fixture mutates only the emulator app database and requires an explicit argument.",
            InstrumentationRegistry.getArguments().getString(ARGUMENT) == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dates = mapOf(
            "apm-reference.jpg" to captured(2026, 8, 16, 12),
            "apm-query-same.jpg" to captured(2026, 8, 15, 10),
            "apm-query-different.jpg" to captured(2026, 8, 15, 14),
            "apm-main-v7.png" to captured(2026, 8, 14, 9),
            "apm-identity-v7.png" to captured(2026, 8, 14, 11),
            "APM_WALL_20260816_1.jpg" to captured(2026, 8, 14, 13),
            "APM_WALL_20260814_SCREEN.png" to captured(2026, 8, 14, 15),
        )
        val photos = publicFixturePhotos(context, dates)
        assertEquals(dates.keys, photos.map(ScannedPhoto::displayName).toSet())

        ApmDatabase(context).use { database ->
            database.upsertAccessiblePhotos(photos)
            val fixtureIds = photos.map(ScannedPhoto::photoId).toSet()
            val wall = database.galleryPhotos().filter { it.photoId in fixtureIds }
            val heatmap = PhotoWallOrganizer.heatmap(
                year = 2026,
                photos = wall,
                zoneId = ZoneId.systemDefault(),
            )
            assertEquals(7, wall.size)
            assertEquals(7, heatmap.totalCount)
            assertEquals(4, heatmap.maxDayCount)
            assertTrue(wall.all { it.uri.startsWith("content://media/") })
        }
    }

    private fun publicFixturePhotos(
        context: Context,
        dates: Map<String, Long>,
    ): List<ScannedPhoto> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.MIME_TYPE,
        )
        return context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameColumn)
                    val date = dates[name] ?: continue
                    val id = cursor.getLong(idColumn)
                    val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        .buildUpon()
                        .appendPath(id.toString())
                        .build()
                    val digest = MessageDigest.getInstance("SHA-256")
                    context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Cannot read public visual fixture $name" }
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                    add(
                        ScannedPhoto(
                            uri = uri.toString(),
                            mediaStoreId = id,
                            photoId = digest.digest().joinToString("") { "%02x".format(it) },
                            displayName = name,
                            mediaType = cursor.getString(typeColumn) ?: "image/jpeg",
                            byteSize = cursor.getLong(sizeColumn),
                            modifiedSeconds = cursor.getLong(modifiedColumn),
                            dateTakenMillis = date,
                        ),
                    )
                }
            }
        }.orEmpty()
    }

    private fun captured(year: Int, month: Int, day: Int, hour: Int): Long =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private companion object {
        const val ARGUMENT = "seedPhotoWallUi"
    }
}
