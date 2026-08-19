package com.jinof.apm

import java.util.Locale

data class CountedObject(
    val name: String,
    val count: Int,
    val attributes: List<String>,
)

data class PresentedPeople(
    val presentation: String,
    val count: Int,
)

data class RecognizedSubject(
    val name: String,
    val kind: String,
)

data class SubjectMention(
    val marker: String,
    val kind: String,
    val description: String,
    val actions: List<String>,
)

data class RecognitionProfile(
    val personNames: List<String> = emptyList(),
    val petNames: List<String> = emptyList(),
) {
    fun validate(subjects: List<RecognizedSubject>) {
        val people = personNames.toSet()
        val pets = petNames.toSet()
        subjects.forEach { subject ->
            val allowed = when (subject.kind) {
                "人物" -> subject.name in people
                "宠物" -> subject.name in pets
                else -> false
            }
            require(allowed) { "识别名称 ${subject.name} 不在对应的预设候选中" }
        }
    }

    companion object {
        private val separators = Regex("[,，;；\\n]+")

        fun fromText(personNames: String, petNames: String): RecognitionProfile = normalize(
            personNames = personNames.split(separators),
            petNames = petNames.split(separators),
        )

        fun normalize(personNames: List<String>, petNames: List<String>): RecognitionProfile {
            fun names(values: List<String>): List<String> {
                val seen = linkedSetOf<String>()
                return values.map(String::trim)
                    .filter(String::isNotEmpty)
                    .onEach { require(it.length <= 40) { "名称最多 40 个字符" } }
                    .filter { seen.add(it.lowercase(Locale.ROOT)) }
                    .also { require(it.size <= 20) { "人物或宠物名称最多 20 个" } }
            }

            val people = names(personNames)
            val pets = names(petNames)
            val peopleKeys = people.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
            require(pets.none { it.lowercase(Locale.ROOT) in peopleKeys }) {
                "同一名称不能同时属于人物和宠物"
            }
            return RecognitionProfile(people, pets)
        }
    }
}

data class PhotoFacets(
    val daylight: String,
    val sky: List<String>,
    val objects: List<CountedObject>,
    val people: List<PresentedPeople>,
    val actions: List<String>,
    val scenes: List<String>,
    val weather: List<String>,
)

data class PhotoAnnotation(
    val caption: String,
    val tags: List<String>,
    val visibleText: String,
    val facets: PhotoFacets,
    val subjectMentions: List<SubjectMention> = emptyList(),
    val recognizedSubjects: List<RecognizedSubject> = emptyList(),
)

data class InferenceConfig(
    val endpoint: String = EndpointPolicy.DEFAULT_ENDPOINT,
    val modelName: String = "qwen3-vl:4b",
    val allowRemote: Boolean = false,
)

data class ScannedPhoto(
    val uri: String,
    val mediaStoreId: Long,
    val photoId: String,
    val displayName: String,
    val mediaType: String,
    val byteSize: Long,
    val modifiedSeconds: Long,
    val dateTakenMillis: Long?,
)

data class KnownLocation(
    val photoId: String,
    val byteSize: Long,
    val modifiedSeconds: Long,
)

data class PendingPhoto(
    val photoId: String,
    val uri: String,
    val displayName: String,
)

data class PhotoCard(
    val photoId: String,
    val uri: String,
    val displayName: String,
    val caption: String,
    val annotation: PhotoAnnotation,
    val modelName: String,
    val promptVersion: String,
    val annotatedAt: String,
    val dateTakenMillis: Long?,
)

data class GalleryPhotoCard(
    val photoId: String,
    val uri: String,
    val displayName: String,
    val dateTakenMillis: Long?,
    val annotation: PhotoAnnotation?,
    val modelName: String?,
    val promptVersion: String?,
    val annotatedAt: String?,
)

data class ScanReport(
    val visible: Int,
    val hashed: Int,
    val reused: Int,
    val inaccessible: Int,
    val errors: List<String>,
)

data class SelectedScanReport(
    val scan: ScanReport,
    val photoIds: List<String>,
)

data class AnnotationReport(
    val selected: Int,
    val annotated: Int,
    val errors: List<String>,
)

object FacetRules {
    val daylightValues = setOf("天亮", "天黑", "日出日落", "室内", "不确定")
    val peopleValues = setOf("男性呈现", "女性呈现", "儿童", "多人混合", "不确定")

    fun validate(annotation: PhotoAnnotation): PhotoAnnotation {
        require(annotation.caption.isNotBlank() && annotation.caption.length <= 500) {
            "caption 必须是 1 到 500 字的描述"
        }
        require(annotation.tags.size <= 40) { "tags 最多 40 项" }
        require(annotation.visibleText.length <= 4000) { "visible_text 最多 4000 字" }
        require(annotation.facets.daylight in daylightValues) { "daylight 取值不合法" }
        require(annotation.facets.sky.size <= 10) { "sky 最多 10 项" }
        require(annotation.facets.objects.size <= 30) { "objects 最多 30 项" }
        require(annotation.facets.people.size <= 10) { "people 最多 10 项" }
        require(annotation.facets.actions.size <= 20) { "actions 最多 20 项" }
        require(annotation.facets.scenes.size <= 10) { "scenes 最多 10 项" }
        require(annotation.facets.weather.size <= 10) { "weather 最多 10 项" }
        require(annotation.recognizedSubjects.size <= 20) { "recognized_subjects 最多 20 项" }
        require(annotation.subjectMentions.size <= 40) { "subject_mentions 最多 40 项" }
        annotation.facets.objects.forEach {
            require(it.name.isNotBlank() && it.name.length <= 80) { "object.name 不合法" }
            require(it.count in 1..999) { "object.count 必须大于 0" }
            require(it.attributes.size <= 10) { "object.attributes 最多 10 项" }
        }
        annotation.facets.people.forEach {
            require(it.presentation in peopleValues) { "people.presentation 取值不合法" }
            require(it.count in 1..999) { "people.count 必须大于 0" }
        }
        annotation.recognizedSubjects.forEach {
            require(it.kind == "人物" || it.kind == "宠物") { "recognized_subjects.kind 不合法" }
            require(it.name.isNotBlank() && it.name.length <= 40) { "recognized_subjects.name 不合法" }
        }
        annotation.subjectMentions.forEach {
            require(it.marker.matches(Regex("P[1-9][0-9]*|PET[1-9][0-9]*"))) {
                "subject_mentions.marker 不合法"
            }
            require(it.kind == "人物" || it.kind == "宠物") { "subject_mentions.kind 不合法" }
            require(it.description.isNotBlank() && it.description.length <= 160) {
                "subject_mentions.description 不合法"
            }
            require(it.actions.size <= 10) { "subject_mentions.actions 最多 10 项" }
        }
        allStrings(annotation).forEach {
            require(it.isNotBlank() && it.length <= 100) { "标签必须是 1 到 100 字" }
        }
        return annotation.copy(
            caption = annotation.caption.trim(),
            tags = deduplicate(annotation.tags),
            visibleText = annotation.visibleText.trim(),
            facets = annotation.facets.copy(
                sky = deduplicate(annotation.facets.sky),
                objects = annotation.facets.objects.map { item ->
                    item.copy(name = item.name.trim(), attributes = deduplicate(item.attributes))
                },
                actions = deduplicate(annotation.facets.actions),
                scenes = deduplicate(annotation.facets.scenes),
                weather = deduplicate(annotation.facets.weather),
            ),
            subjectMentions = deduplicateMentions(annotation.subjectMentions),
            recognizedSubjects = deduplicateSubjects(annotation.recognizedSubjects),
        )
    }

    fun searchText(annotation: PhotoAnnotation): String {
        val normalized = validate(annotation)
        val terms = mutableListOf(
            normalized.caption,
            normalized.visibleText,
            normalized.facets.daylight,
        )
        terms += normalized.tags
        terms += normalized.facets.sky
        normalized.facets.objects.forEach { item ->
            terms += item.name
            terms += "${item.count}只${item.name}"
            terms += "${item.count}个${item.name}"
            terms += "${item.name}${item.count}"
            chineseCount(item.count)?.let { count ->
                terms += "${count}只${item.name}"
                terms += "${count}个${item.name}"
            }
            terms += item.attributes
        }
        normalized.facets.people.forEach { item ->
            terms += item.presentation
            terms += "${item.presentation}${item.count}"
            when (item.presentation) {
                "男性呈现" -> terms += listOf("男人", "男性")
                "女性呈现" -> terms += listOf("女人", "女性")
                else -> Unit
            }
        }
        terms += normalized.facets.actions
        terms += normalized.facets.scenes
        terms += normalized.facets.weather
        normalized.recognizedSubjects.forEach { subject ->
            terms += subject.name
            terms += "${subject.kind}${subject.name}"
        }
        normalized.subjectMentions.forEach { mention ->
            terms += mention.description
            terms += mention.actions
        }
        return terms.joinToString(" ").lowercase(Locale.ROOT)
    }

    private fun allStrings(annotation: PhotoAnnotation): List<String> = buildList {
        addAll(annotation.tags)
        addAll(annotation.facets.sky)
        annotation.facets.objects.forEach {
            add(it.name)
            addAll(it.attributes)
        }
        annotation.subjectMentions.forEach {
            add(it.description)
            addAll(it.actions)
        }
        annotation.facets.people.forEach { add(it.presentation) }
        addAll(annotation.facets.actions)
        addAll(annotation.facets.scenes)
        addAll(annotation.facets.weather)
        annotation.recognizedSubjects.forEach {
            add(it.name)
            add(it.kind)
        }
    }

    private fun deduplicate(values: List<String>): List<String> {
        val seen = linkedSetOf<String>()
        return values.map(String::trim).filter { it.isNotEmpty() && seen.add(it.lowercase(Locale.ROOT)) }
    }

    private fun deduplicateSubjects(values: List<RecognizedSubject>): List<RecognizedSubject> {
        val seen = linkedSetOf<String>()
        return values.map { it.copy(name = it.name.trim()) }
            .filter { seen.add("${it.kind}:${it.name.lowercase(Locale.ROOT)}") }
    }

    private fun deduplicateMentions(values: List<SubjectMention>): List<SubjectMention> {
        val seen = linkedSetOf<String>()
        return values.map { mention ->
            mention.copy(
                marker = mention.marker.trim(),
                kind = mention.kind.trim(),
                description = mention.description.trim(),
                actions = deduplicate(mention.actions),
            )
        }.filter { seen.add("${it.kind}:${it.marker}") }
    }

    private fun chineseCount(count: Int): String? = when (count) {
        1 -> "一"
        2 -> "两"
        3 -> "三"
        4 -> "四"
        5 -> "五"
        6 -> "六"
        7 -> "七"
        8 -> "八"
        9 -> "九"
        10 -> "十"
        else -> null
    }
}
