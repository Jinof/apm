package com.jinof.apm

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PhotoViewerActivityTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun opensExactPhotoZoomsResetsAndNavigatesInsideApm() {
        assumeTrue(
            "This test creates temporary emulator images only when explicitly requested.",
            InstrumentationRegistry.getArguments().getString(ARGUMENT) == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val photos = createTemporaryPhotos(context)
        var scenario: ActivityScenario<PhotoViewerActivity>? = null
        try {
            val originalHash = hashUri(context, photos[0].uri)
            val originalBitmap = loadOriginalViewerBitmap(
                resolver = context.contentResolver,
                uri = android.net.Uri.parse(photos[0].uri),
            )
            try {
                assertEquals(3_072, originalBitmap.width)
                assertEquals(2_048, originalBitmap.height)
            } finally {
                originalBitmap.recycle()
            }
            assertEquals(originalHash, hashUri(context, photos[0].uri))
            val totalPhotos = ApmDatabase(context).use { database ->
                database.upsertAccessiblePhotos(photos)
                database.galleryPhotos().size
            }
            val intent = PhotoViewerActivity.intent(context, photos[0].photoId, photos[0].uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            scenario = ActivityScenario.launch(intent)
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodesWithTag("photo_viewer_image").fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("photo_viewer_position").assertTextEquals("1 / $totalPhotos")
            composeRule.onNodeWithTag("photo_viewer_next").assertIsEnabled()

            composeRule.onNodeWithTag("photo_viewer_image").performTouchInput {
                val midpoint = center
                pinch(
                    start0 = Offset(midpoint.x - 40f, midpoint.y),
                    start1 = Offset(midpoint.x + 40f, midpoint.y),
                    end0 = Offset(midpoint.x - 220f, midpoint.y),
                    end1 = Offset(midpoint.x + 220f, midpoint.y),
                    durationMillis = 500,
                )
            }
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("photo_viewer_reset_zoom")
                    .fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("photo_viewer_image").performTouchInput {
                swipe(
                    start = center,
                    end = Offset(center.x + 160f, center.y + 100f),
                    durationMillis = 400,
                )
            }
            composeRule.onNodeWithTag("photo_viewer_reset_zoom").performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("photo_viewer_reset_zoom")
                    .fetchSemanticsNodes().isEmpty()
            }

            composeRule.onNodeWithTag("photo_viewer_next").performClick()
            composeRule.onNodeWithTag("photo_viewer_position").assertTextEquals("2 / $totalPhotos")
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodes(
                    hasTestTag("photo_viewer_image") and
                        hasContentDescription(photos[1].displayName),
                ).fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("photo_viewer_image")
                .assertContentDescriptionEquals(photos[1].displayName)
            captureViewerScreenshot()
        } finally {
            scenario?.close()
            photos.forEach { photo -> context.contentResolver.delete(android.net.Uri.parse(photo.uri), null, null) }
        }
    }

    private fun captureViewerScreenshot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val outputDir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        val outputFile = File(outputDir, "photo-viewer-acceptance.png")
        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { output ->
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            try {
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
            } finally {
                screenshot.recycle()
            }
        }
    }

    private fun createTemporaryPhotos(context: Context): List<ScannedPhoto> {
        val resolver = context.contentResolver
        val baseCaptureTime = System.currentTimeMillis() + 7L * 24L * 60L * 60L * 1_000L
        val fixtures = listOf(
            Triple(Color.rgb(31, 112, 170), 3_072, 2_048),
            Triple(Color.rgb(198, 101, 48), 2_560, 1_440),
        )
        return fixtures.mapIndexed { index, (color, width, height) ->
            val displayName = "apm-viewer-test-${UUID.randomUUID()}-$index.png"
            val uri = requireNotNull(
                resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/APMViewerTest")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    },
                ),
            ) { "Cannot create viewer fixture $displayName" }
            resolver.openOutputStream(uri, "w").use { output ->
                requireNotNull(output) { "Cannot write viewer fixture $uri" }
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(color)
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                } finally {
                    bitmap.recycle()
                }
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
            ScannedPhoto(
                uri = uri.toString(),
                mediaStoreId = ContentUris.parseId(uri),
                photoId = hashUri(context, uri.toString()),
                displayName = displayName,
                mediaType = "image/png",
                byteSize = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L,
                modifiedSeconds = System.currentTimeMillis() / 1_000L,
                dateTakenMillis = baseCaptureTime - index,
            )
        }.also { photos ->
            assertEquals(2, photos.size)
            assertEquals(photos.map { it.photoId }.distinct(), photos.map { it.photoId })
        }
    }

    private fun hashUri(context: Context, uriText: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(android.net.Uri.parse(uriText)).use { input ->
            requireNotNull(input) { "Cannot read viewer fixture $uriText" }
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val ARGUMENT = "verifyPhotoViewer"
    }
}
