package com.jinof.apm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectMarkerPipelineTest {
    @Test
    fun substitutesMatchedAndUnknownNamesLocally() {
        val annotation = annotation("P1抱着PET1，P2站在旁边")
        val markers = listOf(
            marker("P1", LocalIdentityKind.PERSON, 0, 1, "小明"),
            marker("P2", LocalIdentityKind.PERSON, 1, null, null),
            marker("PET1", LocalIdentityKind.PET, 0, 2, "旺财"),
        )

        val composed = SubjectMarkerPipeline.compose(annotation, markers)

        assertEquals("小明抱着旺财，未知人物2站在旁边", composed.caption)
        assertEquals(
            setOf("人物:小明", "宠物:旺财"),
            composed.recognizedSubjects.map { "${it.kind}:${it.name}" }.toSet(),
        )
        assertFalse(composed.recognizedSubjects.any { it.name.startsWith("未知") })
    }

    @Test
    fun matchingPetPrefixIsReplacedBeforePersonPrefix() {
        val composed = SubjectMarkerPipeline.compose(
            annotation("PET1看着P1"),
            listOf(
                marker("P1", LocalIdentityKind.PERSON, 0, 1, "小明"),
                marker("PET1", LocalIdentityKind.PET, 0, 2, "旺财"),
            ),
        )

        assertEquals("旺财看着小明", composed.caption)
        assertTrue(composed.caption.none { it == 'P' })
    }

    private fun marker(
        value: String,
        kind: LocalIdentityKind,
        index: Int,
        id: Long?,
        name: String?,
    ) = LocalSubjectMarker(
        marker = value,
        kind = kind,
        observationIndex = index,
        box = FaceBox(0.1f, 0.1f, 0.4f, 0.4f),
        matchedIdentityId = id,
        matchedName = name,
    )

    private fun annotation(caption: String) = PhotoAnnotation(
        caption = caption,
        tags = listOf("合影"),
        visibleText = "",
        facets = PhotoFacets(
            daylight = "天亮",
            sky = emptyList(),
            objects = emptyList(),
            people = emptyList(),
            actions = emptyList(),
            scenes = listOf("室内"),
            weather = emptyList(),
        ),
    )
}
