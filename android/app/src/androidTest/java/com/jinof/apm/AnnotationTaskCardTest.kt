package com.jinof.apm

import android.os.SystemClock
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AnnotationTaskCardTest {
    @get:Rule val rule = createComposeRule()

    @Test fun cardShowsMeasuredProgressLogsEtaAndAllowsRestartOnlyAfterFinish() {
        val state = mutableStateOf<AnnotationTaskProgress?>(null)
        var starts = 0
        rule.setContent { MaterialTheme { AnnotationTaskCard(state.value, state.value?.active == true) { starts++ } } }
        rule.onNodeWithTag("annotate_all").assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(1, starts)
            state.value = AnnotationTaskProgress("全量", SystemClock.elapsedRealtime(), phase = "扫描中")
        }
        rule.onNodeWithTag("annotate_all").assertIsNotEnabled()
        rule.onNodeWithTag("annotation_task_eta").assertTextContains("扫描完成后估算", substring = true)
        rule.runOnIdle {
            val now = SystemClock.elapsedRealtime()
            state.value = AnnotationTaskProgress("全量", now - 30000, total = 5, succeeded = 1, failed = 1,
                skipped = 12, attemptMillis = 30000).startPhoto(PendingPhoto("current", "content://photo", "海边照片.jpg"), now)
                .log("请求模型，等待响应", now)
        }
        rule.onNodeWithTag("annotation_task_counts").assertTextContains("已处理 2/5 · 成功 1 · 失败 1 · 跳过 12", substring = true)
        rule.onNodeWithTag("annotation_current_photo").assertTextContains("海边照片.jpg", substring = true)
        rule.onNodeWithTag("annotation_task_eta").assertTextContains("ETA：约", substring = true)
        rule.onNodeWithTag("annotation_task_logs").assertTextContains("请求模型，等待响应", substring = true)
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { directory ->
            val file = File(directory, "annotation-task-card.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        rule.runOnIdle { state.value = state.value!!.finish(SystemClock.elapsedRealtime(), fatal = true) }
        rule.onNodeWithTag("annotation_task_phase").assertTextContains("任务失败", substring = true)
        rule.onNodeWithTag("annotation_current_photo").assertTextContains("—", substring = true)
        rule.onNodeWithTag("annotation_task_eta").assertTextContains("ETA：—", substring = true)
        rule.onNodeWithTag("annotate_all").assertIsEnabled()
    }
}
