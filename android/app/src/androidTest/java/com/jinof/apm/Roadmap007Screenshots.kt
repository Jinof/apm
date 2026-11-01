package com.jinof.apm

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

internal fun SemanticsNodeInteraction.saveRoadmapScreenshot(name: String) {
    val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
    val file = File(output, name)
    file.parentFile?.mkdirs()
    file.outputStream().use { captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
}
