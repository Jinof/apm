package com.jinof.apm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualSimilarityModelsTest {
    @Test
    fun captureTimeAloneCannotCreateBurstOrCandidate() {
        val query = photo("q", global = vector(1f), patch = vector(1f), time = 100_000)
        val different = photo("different", global = vector(0.1f), patch = vector(0.1f), time = 100_001)

        assertNull(SimilarityScorer.score(query, different))
    }

    @Test
    fun visuallyClosePhotosInsideWindowAreBurst() {
        val query = photo("q", vector(1f), vector(1f), 100_000)
        val candidate = photo("c", vector(0.96f), vector(0.92f), 104_000)

        val result = SimilarityScorer.score(query, candidate)!!

        assertEquals(SimilarityRelationship.BURST, result.relationship)
        assertEquals(4_000L, result.captureDeltaMillis)
    }

    @Test
    fun correspondingGridKeepsCompositionSeparateFromWholeScene() {
        val queryPatches = List(16) { index -> if (index < 8) basis(0) else basis(1) }
        val sameLayout = List(16) { index -> if (index < 8) basis(0) else basis(1) }
        val swappedLayout = List(16) { index -> if (index < 8) basis(1) else basis(0) }
        val query = photo("q", basis(2), queryPatches, null)

        val aligned = SimilarityScorer.score(query, photo("aligned", basis(2), sameLayout, null))!!
        val swapped = SimilarityScorer.score(query, photo("swapped", basis(2), swappedLayout, null))!!

        assertTrue(aligned.compositionSimilarity > swapped.compositionSimilarity)
        assertEquals(aligned.globalSimilarity, swapped.globalSimilarity, 0.0001f)
    }

    @Test
    fun subjectMatchingUsesBestOneToOneAssignment() {
        val left = listOf(subject("person", vector(1f)), subject("person", vector(0.75f)))
        val right = listOf(subject("person", vector(0.72f)), subject("person", vector(0.99f)))

        val score = SimilarityScorer.subjectSimilarity(left, right)!!

        assertTrue(score > 0.98f)
    }

    @Test
    fun absentCompatibleSubjectsAreUnknownNotZero() {
        val face = subject("person", basis(0), SimilaritySubjectKind.FACE)
        val pet = subject("cat", basis(0), SimilaritySubjectKind.PET)

        assertNull(SimilarityScorer.subjectSimilarity(listOf(face), listOf(pet)))
    }

    @Test
    fun vlmExplanationCannotChangeScoresRelationshipOrOrdering() {
        val ranked = SimilarityScorer.rank(
            photo("q", vector(1f), vector(1f), null),
            listOf(
                photo("a", vector(0.95f), vector(0.91f), null),
                photo("b", vector(0.90f), vector(0.86f), null),
            ),
        )
        val annotation = annotation(tags = listOf("狗", "草地"), scenes = listOf("公园"))
        val changed = annotation(tags = listOf("室内"), scenes = listOf("客厅"))

        val explained = ranked.map { SimilarityExplanationBuilder.build(it, annotation, changed) }

        assertEquals(ranked.map(SimilarPhotoResult::candidatePhotoId), explained.map(SimilarPhotoResult::candidatePhotoId))
        assertEquals(ranked.map(SimilarPhotoResult::rankScore), explained.map(SimilarPhotoResult::rankScore))
        assertEquals(ranked.map(SimilarPhotoResult::relationship), explained.map(SimilarPhotoResult::relationship))
    }

    private fun photo(
        id: String,
        global: FloatArray,
        patch: FloatArray,
        time: Long?,
    ): SimilarityPhotoInput = photo(id, global, List(16) { patch }, time)

    private fun photo(
        id: String,
        global: FloatArray,
        patches: List<FloatArray>,
        time: Long?,
    ) = SimilarityPhotoInput(
        photoId = id,
        feature = PhotoVisualFeature(
            photoId = id,
            globalEmbedding = FaceMatcher.normalize(global),
            patchEmbeddings = patches.map(FaceMatcher::normalize),
            modelName = "dinov2-vits14-reg",
            pipelineVersion = SimilarityScorer.PIPELINE_VERSION,
            indexedAt = "2026-08-15T00:00:00Z",
        ),
        dateTakenMillis = time,
    )

    private fun subject(
        category: String,
        embedding: FloatArray,
        kind: SimilaritySubjectKind = SimilaritySubjectKind.FACE,
    ) = SimilaritySubjectObservation(
        kind = kind,
        category = category,
        embedding = FaceMatcher.normalize(embedding),
        modelName = if (kind == SimilaritySubjectKind.FACE) "sface" else "pet-model",
        pipelineVersion = "v1",
    )

    private fun vector(cosine: Float): FloatArray = floatArrayOf(
        cosine,
        kotlin.math.sqrt((1f - cosine * cosine).coerceAtLeast(0f)),
        0f,
    )

    private fun basis(index: Int): FloatArray = FloatArray(3).also { it[index] = 1f }

    private fun annotation(tags: List<String>, scenes: List<String>) = PhotoAnnotation(
        caption = "测试照片",
        tags = tags,
        visibleText = "",
        facets = PhotoFacets(
            daylight = "天亮",
            sky = emptyList(),
            objects = emptyList(),
            people = emptyList(),
            actions = emptyList(),
            scenes = scenes,
            weather = emptyList(),
        ),
    )
}
