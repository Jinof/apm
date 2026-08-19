package com.jinof.apm

import java.util.Locale

enum class PhotoAccessAction {
    SCAN_NOW,
    REQUEST_INITIAL_ACCESS,
    REQUEST_RESELECTION,
}

object PhotoAccessPolicy {
    fun nextScanAction(
        sdkInt: Int,
        hasFullAccess: Boolean,
        hasPartialAccess: Boolean,
    ): PhotoAccessAction {
        if (hasFullAccess) return PhotoAccessAction.SCAN_NOW
        return if (sdkInt >= 34 && hasPartialAccess) {
            PhotoAccessAction.REQUEST_RESELECTION
        } else {
            PhotoAccessAction.REQUEST_INITIAL_ACCESS
        }
    }
}

data class SearchAvailability(
    val annotationCount: Int,
    val enabled: Boolean,
    val suggestions: List<String>,
) {
    companion object {
        fun derive(annotationCount: Int, suggestions: List<String>): SearchAvailability {
            require(annotationCount >= 0)
            val enabled = annotationCount > 0
            return SearchAvailability(
                annotationCount = annotationCount,
                enabled = enabled,
                suggestions = if (enabled) suggestions else emptyList(),
            )
        }
    }
}

object SuggestionBuilder {
    private val ignored = setOf("", "无", "不确定", "unknown")

    fun from(annotations: List<PhotoAnnotation>, limit: Int = 8): List<String> {
        require(limit >= 1)
        val counts = linkedMapOf<String, Pair<String, Int>>()
        annotations.forEach { annotation ->
            val terms = buildList {
                addAll(annotation.tags)
                add(annotation.facets.daylight)
                addAll(annotation.facets.sky)
                annotation.facets.objects.forEach { add(it.name) }
                annotation.facets.people.forEach { person ->
                    add(
                        when (person.presentation) {
                            "男性呈现" -> "男性"
                            "女性呈现" -> "女性"
                            else -> person.presentation
                        },
                    )
                }
                addAll(annotation.facets.actions)
                addAll(annotation.facets.scenes)
                addAll(annotation.facets.weather)
                annotation.recognizedSubjects.forEach { add(it.name) }
            }
            terms.map(String::trim)
                .filter { it.lowercase(Locale.ROOT) !in ignored }
                .distinctBy { it.lowercase(Locale.ROOT) }
                .forEach { term ->
                    val key = term.lowercase(Locale.ROOT)
                    val previous = counts[key]
                    counts[key] = term to ((previous?.second ?: 0) + 1)
                }
        }
        return counts.values
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second })
            .map { it.first }
            .take(limit)
    }
}

data class GalleryUiState(
    val query: String = "",
    val activeSearchQuery: String? = null,
    val results: List<PhotoCard> = emptyList(),
    val galleryPhotos: List<GalleryPhotoCard> = emptyList(),
    val search: SearchAvailability = SearchAvailability.derive(0, emptyList()),
    val accessibleCount: Int = 0,
    val selectedCount: Int = 0,
    val identityCount: Int = 0,
    val visualIndexedCount: Int = 0,
    val visualModelIssue: String? = null,
    val busy: Boolean = false,
    val status: String = "准备就绪",
)
