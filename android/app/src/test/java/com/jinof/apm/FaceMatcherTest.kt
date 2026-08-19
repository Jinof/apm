package com.jinof.apm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceMatcherTest {
    @Test
    fun acceptsOnlyStrongUnambiguousBestIdentity() {
        val candidate = vector(0.82f)
        val templates = listOf(
            template(1, "小明", vector(1f)),
            template(2, "小红", floatArrayOf(0f, 1f)),
        )

        val result = FaceMatcher.decide(candidate, templates)

        assertEquals(FaceMatcher.MATCHED, result.state)
        assertEquals(1L, result.identityId)
        assertEquals("小明", result.identityName)
        assertTrue(result.similarity!! >= FaceMatcher.DEFAULT_THRESHOLD)
    }

    @Test
    fun belowThresholdStaysUnknown() {
        val result = FaceMatcher.decide(
            candidate = vector(0.54f),
            templates = listOf(template(1, "小明", vector(1f))),
        )

        assertEquals(FaceMatcher.UNKNOWN, result.state)
        assertNull(result.identityId)
        assertNull(result.identityName)
        assertTrue(result.similarity!! < FaceMatcher.DEFAULT_THRESHOLD)
    }

    @Test
    fun closeDifferentIdentitiesStayUnknownEvenAboveThreshold() {
        val candidate = floatArrayOf(1f, 0f, 0f)
        val templates = listOf(
            template(1, "小明", floatArrayOf(0.72f, 0.69f, 0f)),
            template(2, "小红", floatArrayOf(0.70f, 0.71f, 0f)),
        )

        val result = FaceMatcher.decide(candidate, templates)

        assertEquals(FaceMatcher.UNKNOWN, result.state)
        assertNull(result.identityId)
    }

    @Test
    fun multipleTemplatesForSameIdentityDoNotCreateAmbiguity() {
        val candidate = vector(0.9f)
        val templates = listOf(
            template(1, "小明", vector(1f), id = 1),
            template(1, "小明", vector(0.95f), id = 2),
        )

        assertEquals(FaceMatcher.MATCHED, FaceMatcher.decide(candidate, templates).state)
    }

    @Test
    fun codecRoundTripsNormalizedFiniteEmbedding() {
        val original = floatArrayOf(3f, 4f)
        val decoded = FaceEmbeddingCodec.decode(FaceEmbeddingCodec.encode(original), 2)

        assertEquals(0.6f, decoded[0], 0.0001f)
        assertEquals(0.8f, decoded[1], 0.0001f)
    }

    private fun vector(cosine: Float): FloatArray = floatArrayOf(
        cosine,
        kotlin.math.sqrt((1f - cosine * cosine).coerceAtLeast(0f)),
    )

    private fun template(
        identityId: Long,
        name: String,
        embedding: FloatArray,
        id: Long = identityId,
    ) = FaceTemplateRecord(id, identityId, name, FaceMatcher.normalize(embedding), "sface")
}
