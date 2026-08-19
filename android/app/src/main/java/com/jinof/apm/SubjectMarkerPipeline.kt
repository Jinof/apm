package com.jinof.apm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max

object SubjectMarkerPipeline {
    private val markerPattern = Regex("(?<![A-Z0-9])(?:PET[1-9][0-9]*|P[1-9][0-9]*)(?![A-Z0-9])")

    fun draw(source: Bitmap, markers: List<LocalSubjectMarker>): Bitmap {
        val mutable = source.copy(Bitmap.Config.ARGB_8888, true)
        if (markers.isEmpty()) return mutable
        val canvas = Canvas(mutable)
        val scale = max(1f, minOf(source.width, source.height) / 720f)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f * scale
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 30f * scale
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val labelBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        markers.forEach { marker ->
            val color = when (marker.kind) {
                LocalIdentityKind.PERSON -> Color.rgb(0, 105, 210)
                LocalIdentityKind.PET -> Color.rgb(192, 75, 0)
            }
            stroke.color = color
            labelBackground.color = color
            val box = RectF(
                marker.box.left * mutable.width,
                marker.box.top * mutable.height,
                marker.box.right * mutable.width,
                marker.box.bottom * mutable.height,
            )
            canvas.drawRect(box, stroke)
            val padding = 8f * scale
            val labelWidth = text.measureText(marker.marker) + padding * 2
            val labelHeight = text.textSize + padding * 2
            val labelTop = (box.top - labelHeight).coerceAtLeast(0f)
            val labelRight = (box.left + labelWidth).coerceAtMost(mutable.width.toFloat())
            canvas.drawRect(box.left, labelTop, labelRight, labelTop + labelHeight, labelBackground)
            canvas.drawText(marker.marker, box.left + padding, labelTop + padding + text.textSize * 0.82f, text)
        }
        return mutable
    }

    fun compose(annotation: PhotoAnnotation, markers: List<LocalSubjectMarker>): PhotoAnnotation {
        val byMarker = markers.associateBy(LocalSubjectMarker::marker)
        fun substitute(value: String): String = markerPattern.replace(value) { match ->
            byMarker[match.value]?.renderedName ?: match.value
        }
        val recognized = markers.mapNotNull { marker ->
            marker.matchedName?.let { name -> RecognizedSubject(name, marker.kind.displayName) }
        }.distinctBy { "${it.kind}:${it.name.lowercase()}" }
        return FacetRules.validate(
            annotation.copy(
                caption = substitute(annotation.caption),
                subjectMentions = annotation.subjectMentions.map { mention ->
                    mention.copy(description = substitute(mention.description))
                },
                recognizedSubjects = recognized,
            ),
        )
    }
}
