package com.jinof.apm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PetMatcherTest {
    @Test
    fun acceptsOnlyConservativeSameSpeciesMatch() {
        val candidate = vector(1f, 0f, 0f)
        val templates = listOf(
            template(1, 10, "旺财", "dog", vector(0.995f, 0.05f, 0f)),
            template(2, 20, "豆豆", "dog", vector(0.70f, 0.71f, 0f)),
            template(3, 30, "咪咪", "cat", vector(1f, 0f, 0f)),
        )

        val result = PetMatcher.decide(candidate, "dog", templates)

        assertEquals(PetMatcher.MATCHED, result.state)
        assertEquals(10L, result.identityId)
        assertEquals("旺财", result.identityName)
        assertTrue(result.similarity!! >= PetMatcher.DEFAULT_THRESHOLD)
    }

    @Test
    fun belowThresholdStaysUnknown() {
        val result = PetMatcher.decide(
            vector(1f, 0f),
            "dog",
            listOf(template(1, 10, "旺财", "dog", vector(0.89f, 0.456f))),
        )

        assertEquals(PetMatcher.UNKNOWN, result.state)
        assertNull(result.identityId)
        assertNull(result.identityName)
        assertTrue(result.similarity!! < PetMatcher.DEFAULT_THRESHOLD)
    }

    @Test
    fun ambiguousTopCandidatesStayUnknown() {
        val result = PetMatcher.decide(
            vector(1f, 0f, 0f),
            "dog",
            listOf(
                template(1, 10, "旺财", "dog", vector(0.98f, 0.20f, 0f)),
                template(2, 20, "豆豆", "dog", vector(0.975f, 0.222f, 0f)),
            ),
        )

        assertEquals(PetMatcher.UNKNOWN, result.state)
        assertNull(result.identityId)
        assertNull(result.identityName)
    }

    @Test
    fun differentSpeciesNeverMatches() {
        val result = PetMatcher.decide(
            vector(1f, 0f),
            "dog",
            listOf(template(1, 10, "咪咪", "cat", vector(1f, 0f))),
        )

        assertEquals(PetMatcher.UNKNOWN, result.state)
        assertNull(result.identityId)
    }

    private fun vector(vararg values: Float): FloatArray = FaceMatcher.normalize(values)

    private fun template(
        id: Long,
        identityId: Long,
        name: String,
        species: String,
        embedding: FloatArray,
    ) = PetTemplateRecord(
        id = id,
        identityId = identityId,
        identityName = name,
        species = species,
        embedding = embedding,
        modelName = "mobilenet-v3",
    )
}
