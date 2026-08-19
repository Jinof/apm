package com.jinof.apm

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

data class PhotoVisualFeature(
    val photoId: String,
    val globalEmbedding: FloatArray,
    val patchEmbeddings: List<FloatArray>,
    val modelName: String,
    val pipelineVersion: String,
    val indexedAt: String,
) {
    val dimension: Int = globalEmbedding.size

    init {
        require(photoId.isNotBlank())
        require(dimension > 0)
        require(patchEmbeddings.size == PATCH_COUNT) { "构图特征必须是 4x4 共 16 格" }
        require(modelName.isNotBlank() && pipelineVersion.isNotBlank())
        require(globalEmbedding.isUsableEmbedding(dimension))
        require(patchEmbeddings.all { it.isUsableEmbedding(dimension) })
    }

    companion object {
        const val GRID_SIZE = 4
        const val PATCH_COUNT = GRID_SIZE * GRID_SIZE
    }
}

enum class SimilaritySubjectKind {
    FACE,
    PET,
    GENERAL_OBJECT,
}

data class SimilaritySubjectObservation(
    val kind: SimilaritySubjectKind,
    val category: String,
    val embedding: FloatArray,
    val modelName: String,
    val pipelineVersion: String,
) {
    init {
        require(category.isNotBlank() && modelName.isNotBlank() && pipelineVersion.isNotBlank())
        require(embedding.isUsableEmbedding(embedding.size))
    }

    internal val compatibilityKey = listOf(
        kind.name,
        category.lowercase(),
        modelName,
        pipelineVersion,
        embedding.size.toString(),
    ).joinToString("\u0000")
}

data class GeneralSubjectObservation(
    val subjectIndex: Int,
    val category: String,
    val box: FaceBox,
    val detectorScore: Float,
    val embedding: FloatArray,
    val detectorName: String,
    val modelName: String,
    val pipelineVersion: String,
) {
    init {
        require(subjectIndex >= 0)
        require(category.isNotBlank())
        require(detectorScore.isFinite() && detectorScore in 0f..1f)
        require(embedding.isUsableEmbedding(embedding.size))
        require(detectorName.isNotBlank() && modelName.isNotBlank() && pipelineVersion.isNotBlank())
    }

    fun asSimilaritySubject() = SimilaritySubjectObservation(
        kind = SimilaritySubjectKind.GENERAL_OBJECT,
        category = category,
        embedding = embedding,
        modelName = modelName,
        pipelineVersion = pipelineVersion,
    )
}

data class SimilarityPhotoLocation(
    val photoId: String,
    val uri: String,
    val displayName: String,
    val dateTakenMillis: Long?,
)

data class SimilarPhotoCard(
    val result: SimilarPhotoResult,
    val uri: String,
    val displayName: String,
    val dateTakenMillis: Long?,
)

data class SimilarityPhotoInput(
    val photoId: String,
    val feature: PhotoVisualFeature,
    val dateTakenMillis: Long?,
    val subjects: List<SimilaritySubjectObservation> = emptyList(),
) {
    init {
        require(photoId == feature.photoId)
    }
}

enum class SimilarityRelationship(val displayName: String) {
    BURST("连拍"),
    SAME_SCENE("同场景"),
    SIMILAR_COMPOSITION("构图相似"),
    SIMILAR_SUBJECT("主体相似"),
    VISUALLY_SIMILAR("视觉相似"),
}

data class SimilarPhotoResult(
    val queryPhotoId: String,
    val candidatePhotoId: String,
    val relationship: SimilarityRelationship,
    val globalSimilarity: Float,
    val compositionSimilarity: Float,
    val subjectSimilarity: Float?,
    val captureDeltaMillis: Long?,
    val rankScore: Float,
    val explanation: String = "",
)

/**
 * Versioned first-pass thresholds. They are intentionally centralized so real-photo
 * calibration can change the pipeline version instead of silently changing old results.
 */
object SimilarityScorer {
    const val PIPELINE_VERSION = "dinov2-vits14-reg-similarity-v1"
    const val BURST_WINDOW_MILLIS = 10_000L

    fun rank(query: SimilarityPhotoInput, candidates: List<SimilarityPhotoInput>): List<SimilarPhotoResult> =
        candidates.mapNotNull { score(query, it) }
            .sortedWith(compareByDescending<SimilarPhotoResult> { it.rankScore }.thenBy { it.candidatePhotoId })

    fun score(query: SimilarityPhotoInput, candidate: SimilarityPhotoInput): SimilarPhotoResult? {
        if (query.photoId == candidate.photoId || !compatible(query.feature, candidate.feature)) return null

        val global = boundedCosine(query.feature.globalEmbedding, candidate.feature.globalEmbedding)
        val composition = query.feature.patchEmbeddings.indices
            .map { index ->
                boundedCosine(
                    query.feature.patchEmbeddings[index],
                    candidate.feature.patchEmbeddings[index],
                )
            }
            .average().toFloat().coerceIn(0f, 1f)
        val subject = subjectSimilarity(query.subjects, candidate.subjects)
        val delta = captureDelta(query.dateTakenMillis, candidate.dateTakenMillis)

        val sceneRank = 0.72f * global + 0.28f * composition
        val compositionRank = 0.20f * global + 0.80f * composition
        val subjectRank = subject?.let { 0.20f * global + 0.10f * composition + 0.70f * it }
        val burstEligible = delta != null && delta <= BURST_WINDOW_MILLIS &&
            global >= 0.84f && composition >= 0.78f
        val burstRank = if (burstEligible) {
            val proximity = 1f - delta!!.toFloat() / BURST_WINDOW_MILLIS
            0.55f * global + 0.35f * composition + 0.10f * proximity
        } else {
            null
        }
        val rank = listOfNotNull(sceneRank, compositionRank, subjectRank, burstRank)
            .maxOrNull()!!
            .coerceIn(0f, 1f)

        val relationship = when {
            burstEligible -> SimilarityRelationship.BURST
            composition >= 0.86f && composition > global + 0.04f ->
                SimilarityRelationship.SIMILAR_COMPOSITION
            subject != null && subject >= 0.80f && subject > max(global, composition) + 0.04f ->
                SimilarityRelationship.SIMILAR_SUBJECT
            global >= 0.80f -> SimilarityRelationship.SAME_SCENE
            composition >= 0.82f && global >= 0.62f -> SimilarityRelationship.SIMILAR_COMPOSITION
            subject != null && subject >= 0.76f -> SimilarityRelationship.SIMILAR_SUBJECT
            rank >= 0.70f -> SimilarityRelationship.VISUALLY_SIMILAR
            else -> return null
        }

        return SimilarPhotoResult(
            queryPhotoId = query.photoId,
            candidatePhotoId = candidate.photoId,
            relationship = relationship,
            globalSimilarity = global,
            compositionSimilarity = composition,
            subjectSimilarity = subject,
            captureDeltaMillis = delta,
            rankScore = rank,
        )
    }

    internal fun subjectSimilarity(
        left: List<SimilaritySubjectObservation>,
        right: List<SimilaritySubjectObservation>,
    ): Float? {
        val leftGroups = left.groupBy(SimilaritySubjectObservation::compatibilityKey)
        val rightGroups = right.groupBy(SimilaritySubjectObservation::compatibilityKey)
        val commonKeys = leftGroups.keys.intersect(rightGroups.keys)
        if (commonKeys.isEmpty()) return null

        var matchedWeight = 0f
        var comparableSlots = 0
        commonKeys.sorted().forEach { key ->
            val leftEmbeddings = leftGroups.getValue(key).map(SimilaritySubjectObservation::embedding)
            val rightEmbeddings = rightGroups.getValue(key).map(SimilaritySubjectObservation::embedding)
            val weights = leftEmbeddings.map { leftEmbedding ->
                rightEmbeddings.map { rightEmbedding -> boundedCosine(leftEmbedding, rightEmbedding) }
            }
            matchedWeight += maximumAssignmentWeight(weights)
            comparableSlots += max(leftEmbeddings.size, rightEmbeddings.size)
        }
        return if (comparableSlots == 0) null else (matchedWeight / comparableSlots).coerceIn(0f, 1f)
    }

    private fun compatible(left: PhotoVisualFeature, right: PhotoVisualFeature): Boolean =
        left.modelName == right.modelName &&
            left.pipelineVersion == right.pipelineVersion &&
            left.dimension == right.dimension &&
            left.patchEmbeddings.size == right.patchEmbeddings.size

    private fun boundedCosine(left: FloatArray, right: FloatArray): Float =
        FaceMatcher.cosine(left, right).coerceIn(0f, 1f)

    private fun captureDelta(left: Long?, right: Long?): Long? {
        if (left == null || right == null) return null
        return if (left >= right) {
            if (right < 0 && left > Long.MAX_VALUE + right) Long.MAX_VALUE else left - right
        } else {
            if (left < 0 && right > Long.MAX_VALUE + left) Long.MAX_VALUE else right - left
        }
    }

    /** Hungarian maximum-weight assignment for a rectangular non-negative matrix. */
    private fun maximumAssignmentWeight(source: List<List<Float>>): Float {
        if (source.isEmpty() || source.first().isEmpty()) return 0f
        val weights = if (source.size <= source.first().size) source else transpose(source)
        val rows = weights.size
        val columns = weights.first().size
        val u = DoubleArray(rows + 1)
        val v = DoubleArray(columns + 1)
        val matchedRow = IntArray(columns + 1)
        val previousColumn = IntArray(columns + 1)

        for (row in 1..rows) {
            matchedRow[0] = row
            var column0 = 0
            val minValue = DoubleArray(columns + 1) { Double.POSITIVE_INFINITY }
            val used = BooleanArray(columns + 1)
            do {
                used[column0] = true
                val currentRow = matchedRow[column0]
                var delta = Double.POSITIVE_INFINITY
                var column1 = 0
                for (column in 1..columns) {
                    if (used[column]) continue
                    val cost = 1.0 - weights[currentRow - 1][column - 1]
                    val current = cost - u[currentRow] - v[column]
                    if (current < minValue[column]) {
                        minValue[column] = current
                        previousColumn[column] = column0
                    }
                    if (minValue[column] < delta) {
                        delta = minValue[column]
                        column1 = column
                    }
                }
                for (column in 0..columns) {
                    if (used[column]) {
                        u[matchedRow[column]] += delta
                        v[column] -= delta
                    } else {
                        minValue[column] -= delta
                    }
                }
                column0 = column1
            } while (matchedRow[column0] != 0)

            do {
                val column1 = previousColumn[column0]
                matchedRow[column0] = matchedRow[column1]
                column0 = column1
            } while (column0 != 0)
        }

        var total = 0f
        for (column in 1..columns) {
            val row = matchedRow[column]
            if (row != 0) total += weights[row - 1][column - 1]
        }
        return total
    }

    private fun transpose(values: List<List<Float>>): List<List<Float>> =
        List(values.first().size) { column -> List(values.size) { row -> values[row][column] } }
}

object SimilarityExplanationBuilder {
    fun build(
        ranked: SimilarPhotoResult,
        query: PhotoAnnotation?,
        candidate: PhotoAnnotation?,
    ): SimilarPhotoResult {
        val evidence = mutableListOf(ranked.relationship.displayName)
        if (query != null && candidate != null) {
            val sharedTags = orderedIntersection(query.tags, candidate.tags).take(3)
            val sharedScenes = orderedIntersection(query.facets.scenes, candidate.facets.scenes).take(2)
            val sharedObjects = orderedIntersection(
                query.facets.objects.map(CountedObject::name),
                candidate.facets.objects.map(CountedObject::name),
            ).take(3)
            if (sharedScenes.isNotEmpty()) evidence += "共同场景：${sharedScenes.joinToString("、")}"
            if (sharedObjects.isNotEmpty()) evidence += "共同物体：${sharedObjects.joinToString("、")}"
            if (sharedTags.isNotEmpty()) evidence += "共同标签：${sharedTags.joinToString("、")}"
        }
        return ranked.copy(explanation = evidence.joinToString("；"))
    }

    private fun orderedIntersection(left: List<String>, right: List<String>): List<String> {
        val rightKeys = right.mapTo(hashSetOf()) { it.trim().lowercase() }
        return left.map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase)
            .filter { it.lowercase() in rightKeys }
    }
}

object VisualEmbeddingCodec {
    fun encode(embedding: FloatArray): ByteArray = FaceEmbeddingCodec.encode(embedding)

    fun decode(bytes: ByteArray, dimension: Int): FloatArray = FaceEmbeddingCodec.decode(bytes, dimension)

    fun encodeGrid(grid: List<FloatArray>, dimension: Int): ByteArray {
        require(grid.size == PhotoVisualFeature.PATCH_COUNT)
        require(grid.all { it.size == dimension })
        return ByteBuffer.allocate(grid.size * dimension * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .also { buffer ->
                grid.forEach { embedding -> FaceMatcher.normalize(embedding).forEach(buffer::putFloat) }
            }
            .array()
    }

    fun decodeGrid(bytes: ByteArray, dimension: Int): List<FloatArray> {
        require(dimension > 0)
        require(bytes.size == PhotoVisualFeature.PATCH_COUNT * dimension * Float.SIZE_BYTES) {
            "4x4 构图特征存储维度不合法"
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return List(PhotoVisualFeature.PATCH_COUNT) {
            FaceMatcher.normalize(FloatArray(dimension) { buffer.float })
        }
    }
}

private fun FloatArray.isUsableEmbedding(expectedDimension: Int): Boolean =
    size == expectedDimension && isNotEmpty() && all(Float::isFinite) && any { abs(it) > 1e-8f }
