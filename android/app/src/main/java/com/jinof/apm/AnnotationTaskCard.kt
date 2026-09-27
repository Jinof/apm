package com.jinof.apm

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
internal fun AnnotationTaskCard(task: AnnotationTaskProgress?, busy: Boolean, onStart: () -> Unit) {
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(task?.active, task?.startedAt) {
        now = SystemClock.elapsedRealtime()
        while (task?.active == true) {
            delay(1000)
            now = SystemClock.elapsedRealtime()
        }
    }
    ElevatedCard(Modifier.fillMaxWidth().testTag("annotation_task_card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("全量标注", style = MaterialTheme.typography.titleMedium)
            if (task == null) {
                Text("处理全部已授权照片，跳过已有当前模型标注的图片。", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("${task.scope}任务 · ${task.phase}", modifier = Modifier.testTag("annotation_task_phase"))
                val total = task.total
                if (total != null) {
                    Text("已处理 ${task.completed}/$total · 成功 ${task.succeeded} · 失败 ${task.failed} · 跳过 ${task.skipped}",
                        modifier = Modifier.testTag("annotation_task_counts"), style = MaterialTheme.typography.bodyMedium)
                    if (total > 0) LinearProgressIndicator(
                        progress = (task.completed.toFloat() / total).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth(),
                    )
                } else if (task.active) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text("当前图片：${task.currentPhotoName ?: if (task.active) "准备中" else "—"}",
                    modifier = Modifier.testTag("annotation_current_photo"), maxLines = 2, overflow = TextOverflow.Ellipsis)
                val eta = task.etaSeconds(now)
                val etaText = when {
                    !task.active -> "—"
                    total == null -> "扫描完成后估算"
                    task.completed == 0 -> "首张完成后估算"
                    eta != null -> "约 ${formatAnnotationDuration(eta)}"
                    else -> "重新估算中"
                }
                Text("已用 ${formatAnnotationDuration(task.elapsedMillis(now) / 1000)} · ETA：$etaText",
                    modifier = Modifier.testTag("annotation_task_eta"), style = MaterialTheme.typography.bodyMedium)
                if (task.logs.isNotEmpty()) {
                    Text("最近日志", style = MaterialTheme.typography.labelLarge)
                    Text(task.logs.takeLast(5).joinToString("\n"), modifier = Modifier.testTag("annotation_task_logs"),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            FilledTonalButton(onClick = onStart, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("annotate_all")) { Text("开始全量标注") }
        }
    }
}
