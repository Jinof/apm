package com.jinof.apm

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

data class FaceBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(listOf(left, top, right, bottom).all(Float::isFinite)) { "人脸边界必须是有限数值" }
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f) {
            "人脸边界必须归一化到 0..1"
        }
        require(right > left && bottom > top) { "人脸边界必须具有正面积" }
    }
}

data class FaceSample(
    val faceIndex: Int,
    val box: FaceBox,
    val detectorScore: Float,
    val embedding: FloatArray,
) {
    init {
        require(faceIndex >= 0)
        require(detectorScore.isFinite() && detectorScore in 0f..1f)
    }
}

data class PersonIdentitySummary(
    val id: Long,
    val name: String,
    val templateCount: Int,
)

data class FaceTemplateRecord(
    val id: Long,
    val identityId: Long,
    val identityName: String,
    val embedding: FloatArray,
    val modelName: String,
)

data class FaceMatchDecision(
    val state: String,
    val identityId: Long? = null,
    val identityName: String? = null,
    val similarity: Float? = null,
    val threshold: Float = FaceMatcher.DEFAULT_THRESHOLD,
) {
    init {
        require(state == FaceMatcher.MATCHED || state == FaceMatcher.UNKNOWN)
        if (state == FaceMatcher.MATCHED) {
            require(identityId != null && !identityName.isNullOrBlank())
            require(similarity != null && similarity >= threshold)
        } else {
            require(identityId == null && identityName == null)
        }
    }
}

data class FaceIndexReport(
    val photos: Int,
    val faces: Int,
    val matchedFaces: Int,
    val unknownFaces: Int,
    val errors: List<String>,
)

object FaceMatcher {
    const val MATCHED = "matched"
    const val UNKNOWN = "unknown"
    const val DEFAULT_THRESHOLD = 0.55f
    const val AMBIGUITY_MARGIN = 0.05f

    fun normalize(candidate: FloatArray): FloatArray {
        require(candidate.isNotEmpty()) { "embedding 不能为空" }
        require(candidate.all(Float::isFinite)) { "embedding 包含非有限数值" }
        val norm = sqrt(candidate.fold(0.0) { sum, value -> sum + value * value }).toFloat()
        require(norm > 1e-8f) { "embedding 范数不能为 0" }
        return FloatArray(candidate.size) { index -> candidate[index] / norm }
    }

    fun cosine(left: FloatArray, right: FloatArray): Float {
        require(left.size == right.size && left.isNotEmpty()) { "embedding 维度不一致" }
        require(left.all(Float::isFinite) && right.all(Float::isFinite)) { "embedding 包含非有限数值" }
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in left.indices) {
            dot += left[index] * right[index]
            leftNorm += left[index] * left[index]
            rightNorm += right[index] * right[index]
        }
        require(leftNorm > 1e-16 && rightNorm > 1e-16) { "embedding 范数不能为 0" }
        return (dot / sqrt(leftNorm * rightNorm)).toFloat().coerceIn(-1f, 1f)
    }

    fun decide(
        candidate: FloatArray,
        templates: List<FaceTemplateRecord>,
        threshold: Float = DEFAULT_THRESHOLD,
        ambiguityMargin: Float = AMBIGUITY_MARGIN,
    ): FaceMatchDecision {
        require(threshold in -1f..1f)
        require(ambiguityMargin in 0f..2f)
        val normalized = normalize(candidate)
        val bestByIdentity = templates
            .filter { it.embedding.size == normalized.size }
            .groupBy(FaceTemplateRecord::identityId)
            .mapNotNull { (_, identityTemplates) ->
                identityTemplates.maxByOrNull { cosine(normalized, it.embedding) }?.let { template ->
                    template to cosine(normalized, template.embedding)
                }
            }
            .sortedByDescending { it.second }
        val best = bestByIdentity.firstOrNull()
            ?: return FaceMatchDecision(state = UNKNOWN, threshold = threshold)
        val second = bestByIdentity.getOrNull(1)
        val accepted = best.second >= threshold &&
            (second == null || best.second - second.second >= ambiguityMargin)
        return if (accepted) {
            FaceMatchDecision(
                state = MATCHED,
                identityId = best.first.identityId,
                identityName = best.first.identityName,
                similarity = best.second,
                threshold = threshold,
            )
        } else {
            FaceMatchDecision(
                state = UNKNOWN,
                similarity = best.second,
                threshold = threshold,
            )
        }
    }
}

object FaceEmbeddingCodec {
    fun encode(embedding: FloatArray): ByteArray {
        val normalized = FaceMatcher.normalize(embedding)
        return ByteBuffer.allocate(normalized.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .also { buffer -> normalized.forEach(buffer::putFloat) }
            .array()
    }

    fun decode(bytes: ByteArray, dimension: Int): FloatArray {
        require(dimension > 0 && bytes.size == dimension * Float.SIZE_BYTES) { "embedding 存储维度不合法" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FaceMatcher.normalize(FloatArray(dimension) { buffer.float })
    }
}
