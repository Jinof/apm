package com.jinof.apm

data class PetSample(
    val petIndex: Int,
    val box: FaceBox,
    val species: String,
    val detectorScore: Float,
    val embedding: FloatArray,
) {
    init {
        require(petIndex >= 0)
        require(species in PetMatcher.SUPPORTED_SPECIES)
        require(detectorScore.isFinite() && detectorScore in 0f..1f)
    }
}

data class PetIdentitySummary(
    val id: Long,
    val name: String,
    val templateCount: Int,
    val species: String,
)

data class PetTemplateRecord(
    val id: Long,
    val identityId: Long,
    val identityName: String,
    val species: String,
    val embedding: FloatArray,
    val modelName: String,
)

data class PetMatchDecision(
    val state: String,
    val identityId: Long? = null,
    val identityName: String? = null,
    val similarity: Float? = null,
    val threshold: Float = PetMatcher.DEFAULT_THRESHOLD,
) {
    init {
        require(state == PetMatcher.MATCHED || state == PetMatcher.UNKNOWN)
        if (state == PetMatcher.MATCHED) {
            require(identityId != null && !identityName.isNullOrBlank())
            require(similarity != null && similarity >= threshold)
        } else {
            require(identityId == null && identityName == null)
        }
    }
}

data class PetIndexReport(
    val photos: Int,
    val pets: Int,
    val matchedPets: Int,
    val unknownPets: Int,
    val errors: List<String>,
)

object PetMatcher {
    const val MATCHED = FaceMatcher.MATCHED
    const val UNKNOWN = FaceMatcher.UNKNOWN
    const val DEFAULT_THRESHOLD = 0.90f
    const val AMBIGUITY_MARGIN = 0.04f
    val SUPPORTED_SPECIES = setOf("cat", "dog")

    fun decide(
        candidate: FloatArray,
        species: String,
        templates: List<PetTemplateRecord>,
        threshold: Float = DEFAULT_THRESHOLD,
        ambiguityMargin: Float = AMBIGUITY_MARGIN,
    ): PetMatchDecision {
        require(species in SUPPORTED_SPECIES)
        require(threshold in -1f..1f)
        require(ambiguityMargin in 0f..2f)
        val normalized = FaceMatcher.normalize(candidate)
        val bestByIdentity = templates
            .asSequence()
            .filter { it.species == species && it.embedding.size == normalized.size }
            .groupBy(PetTemplateRecord::identityId)
            .mapNotNull { (_, identityTemplates) ->
                identityTemplates
                    .map { template -> template to FaceMatcher.cosine(normalized, template.embedding) }
                    .maxByOrNull { it.second }
            }
            .sortedByDescending { it.second }
        val best = bestByIdentity.firstOrNull()
            ?: return PetMatchDecision(state = UNKNOWN, threshold = threshold)
        val second = bestByIdentity.getOrNull(1)
        val accepted = best.second >= threshold &&
            (second == null || best.second - second.second >= ambiguityMargin)
        return if (accepted) {
            PetMatchDecision(
                state = MATCHED,
                identityId = best.first.identityId,
                identityName = best.first.identityName,
                similarity = best.second,
                threshold = threshold,
            )
        } else {
            PetMatchDecision(
                state = UNKNOWN,
                similarity = best.second,
                threshold = threshold,
            )
        }
    }
}

enum class LocalIdentityKind(val storageValue: String, val displayName: String) {
    PERSON("person", "人物"),
    PET("pet", "宠物"),
}

data class LocalIdentitySummary(
    val id: Long,
    val name: String,
    val templateCount: Int,
    val kind: LocalIdentityKind,
    val species: String? = null,
)

data class LocalSubjectMarker(
    val marker: String,
    val kind: LocalIdentityKind,
    val observationIndex: Int,
    val box: FaceBox,
    val matchedIdentityId: Long? = null,
    val matchedName: String? = null,
) {
    init {
        require(marker.matches(Regex("P[1-9][0-9]*|PET[1-9][0-9]*")))
        require(observationIndex >= 0)
        require((matchedIdentityId == null) == matchedName.isNullOrBlank())
    }

    val renderedName: String
        get() = matchedName ?: when (kind) {
            LocalIdentityKind.PERSON -> "未知人物${observationIndex + 1}"
            LocalIdentityKind.PET -> "未知宠物${observationIndex + 1}"
        }
}
