package com.jinof.apm

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal data class StoredAnnotationDetails(
    val photoName: String,
    val annotation: PhotoAnnotation,
    val model: String?,
    val promptVersion: String?,
    val annotatedAt: String?,
)

internal fun GalleryPhotoCard.savedDetails(): StoredAnnotationDetails? = annotation?.let {
    StoredAnnotationDetails(displayName, it, modelName, promptVersion, annotatedAt)
}
internal fun PhotoCard.savedDetails() = StoredAnnotationDetails(displayName, annotation, modelName, promptVersion, annotatedAt)

@Composable
internal fun PhotoAnnotationDetailsDialog(details: StoredAnnotationDetails, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("annotation_details_dialog"),
        title = { Text("标注详情") },
        text = {
            SelectionContainer {
                Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DetailField("照片", details.photoName, "annotation_detail_filename")
                    val a = details.annotation
                    DetailField("描述", a.caption, "annotation_detail_caption")
                    DetailField("标签", a.tags.joinToString("、"), "annotation_detail_tags")
                    DetailField("识别文字", a.visibleText, "annotation_detail_ocr")
                    DetailField("天色", a.facets.daylight, "annotation_detail_daylight")
                    DetailField("天空", a.facets.sky.joinToString("、"), "annotation_detail_sky")
                    DetailField("物体", a.facets.objects.joinToString("\n") {
                        "${it.name} × ${it.count}" + if (it.attributes.isEmpty()) "" else " · ${it.attributes.joinToString("、")}"
                    }, "annotation_detail_objects")
                    DetailField("人物外观", a.facets.people.joinToString("\n") { "${it.presentation} × ${it.count}" }, "annotation_detail_people")
                    DetailField("动作", a.facets.actions.joinToString("、"), "annotation_detail_actions")
                    DetailField("场景", a.facets.scenes.joinToString("、"), "annotation_detail_scenes")
                    DetailField("天气", a.facets.weather.joinToString("、"), "annotation_detail_weather")
                    if (a.recognizedSubjects.isNotEmpty()) DetailField("历史身份标签",
                        a.recognizedSubjects.joinToString("\n") { "${it.kind} · ${it.name}" }, "annotation_detail_legacy_identities")
                    if (a.subjectMentions.isNotEmpty()) DetailField("历史主体描述", a.subjectMentions.joinToString("\n") {
                        "${it.marker} · ${it.kind} · ${it.description} · ${it.actions.joinToString("、")}" }, "annotation_detail_legacy_subjects")
                    DetailField("模型", details.model?.takeIf(String::isNotBlank) ?: "未记录", "annotation_detail_model")
                    DetailField("提示词版本", details.promptVersion?.takeIf(String::isNotBlank) ?: "未记录", "annotation_detail_prompt")
                    DetailField("标注时间", details.annotatedAt?.takeIf(String::isNotBlank) ?: "未记录", "annotation_detail_time")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("close_annotation_details")) { Text("关闭") } },
    )
}

@Composable
private fun DetailField(label: String, value: String, tag: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(value.ifBlank { "无" }, modifier = Modifier.testTag(tag), style = MaterialTheme.typography.bodyMedium)
    }
}
