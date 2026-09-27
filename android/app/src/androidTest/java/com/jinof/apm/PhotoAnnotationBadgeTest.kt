package com.jinof.apm

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PhotoAnnotationBadgeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun storedAnnotationShowsGreenBottomRightBadgeInBothModesAndKeepsPhotoClickable() {
        val annotation = PhotoAnnotation("一张已标注照片", emptyList(), "", PhotoFacets(
            "室内", emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        ))
        val stored = mutableStateOf<PhotoAnnotation?>(null)
        val dark = mutableStateOf(false)
        val running = mutableStateOf(false)
        var opened = 0
        rule.setContent {
            MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                val photo = GalleryPhotoCard("badge-photo", "content://apm.test/badge", "测试照片", null,
                    stored.value, "older-model", "older-prompt", null)
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Box(Modifier.size(160.dp)) { PhotoWallThumbnail(photo, annotating = running.value) { opened++ } }
                    Spacer(Modifier.height(16.dp))
                    PhotoWallDetailCard(photo, annotating = running.value) { opened++ }
                }
            }
        }
        rule.onAllNodesWithTag("photo_annotation_badge", useUnmergedTree = true).assertCountEquals(0)
        rule.runOnIdle { stored.value = annotation }
        fun badges() = rule.onAllNodesWithTag("photo_annotation_badge", useUnmergedTree = true)
        badges().assertCountEquals(2)
        for (mode in listOf("thumbnail", "detail")) {
            val badge = rule.onNode(hasTestTag("photo_annotation_badge") and
                hasAnyAncestor(hasTestTag("photo_wall_${mode}_badge-photo")), useUnmergedTree = true)
            badge.assertIsDisplayed().assertContentDescriptionEquals("已标注")
            if (mode == "thumbnail") {
                val image = rule.onNodeWithTag("photo_wall_thumbnail_badge-photo").fetchSemanticsNode().boundsInRoot
                val check = badge.fetchSemanticsNode().boundsInRoot
                assertTrue(check.left > image.center.x && check.top > image.center.y)
                assertTrue(check.right < image.right && check.bottom < image.bottom)
            }
            val pixels = badge.captureToImage().toPixelMap()
            var green = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                val color = pixels[x, y]
                if (color.green > color.red * 2 && color.green > color.blue * 1.5) green++
            }
            assertTrue("Badge must visibly contain green pixels", green > 100)
            badge.performTouchInput { click() }
        }
        rule.runOnIdle { assertEquals(2, opened); dark.value = true }
        badges().assertCountEquals(2)
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        if (output != null) {
            val file = File(output, "annotation-badges-dark.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        rule.runOnIdle { running.value = true }
        badges().assertCountEquals(0)
        rule.onAllNodesWithTag("photo_annotation_running", useUnmergedTree = true).assertCountEquals(2)
        rule.runOnIdle { running.value = false }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("photo_annotation_badge", useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
        badges().assertCountEquals(2)
        rule.onAllNodesWithTag("photo_annotation_running", useUnmergedTree = true).assertCountEquals(0)
        rule.runOnIdle { stored.value = null }
        badges().assertCountEquals(0)
    }
}
