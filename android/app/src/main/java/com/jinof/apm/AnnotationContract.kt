package com.jinof.apm

import org.json.JSONArray
import org.json.JSONObject

object AnnotationContract {
    const val PROMPT_VERSION = "photo-annotation-zh-v6-grammar-safe"
    const val VISIBLE_TEXT_SCHEMA_MAX_LENGTH = 512
    const val GRAMMAR_MAX_REPETITION_EXCLUSIVE = 2000

    private val markerPattern = Regex("(?<![A-Z0-9])(?:PET[1-9][0-9]*|P[1-9][0-9]*)(?![A-Z0-9])")

    private const val BASE_PROMPT = """你是私人相册检索系统的照片标注器。只描述图中可见内容，不猜测人物或宠物身份、关系、地点、族裔、健康等敏感属性。
图片上可能有手机本地绘制的匿名主体框：P1、P2 表示人物，PET1、PET2 表示宠物。它们不是姓名。只能使用下方明确提供的标记，禁止输出、猜测或新增任何真实姓名。
人物的男性/女性标签只表示可见外观呈现，不代表实际性别；无法判断时必须输出“不确定”。数量看不清时按明确可见的最小数量记录。
请用中文输出一句客观 caption。提到有标记的主体时必须直接使用该标记，例如“P1抱着PET1”，不要改写标记。并分别给出：
- tags：通用检索词，不含姓名；
- visible_text：清晰可辨的原文，最多 512 字符，没有则为空字符串；
- facets.daylight：天亮、天黑、日出日落、室内、不确定之一；
- facets.sky：天空现象，如蓝天、云、晚霞；
- facets.objects：物体 name、正整数 count、可见 attributes；
- facets.people：男性呈现、女性呈现、儿童、多人混合、不确定及正整数 count；
- facets.actions：可见动作；facets.scenes：场景；facets.weather：天气；
- subject_mentions：只记录已提供匿名标记的可见描述和动作。marker 必须原样使用，kind 必须与 P/PET 类型一致。没有则输出 []。
没有内容的数组使用 []。严格符合 JSON Schema，不添加解释或身份字段。"""

    fun prompt(markers: List<LocalSubjectMarker>): String {
        val legend = markers.joinToString("、") { marker ->
            "${marker.marker}=${marker.kind.displayName}"
        }.ifEmpty { "（无匿名主体标记）" }
        return "$BASE_PROMPT\n本图允许使用的匿名标记：$legend"
    }

    @Deprecated("Android VLM no longer receives identity candidates")
    fun prompt(profile: RecognitionProfile): String = prompt(emptyList())

    fun schema(markers: List<LocalSubjectMarker> = emptyList()): JSONObject {
        fun stringSchema(maxLength: Int? = null) = JSONObject().put("type", "string").also {
            if (maxLength != null) it.put("maxLength", maxLength)
        }
        fun stringArray(maxItems: Int) = JSONObject()
            .put("type", "array")
            .put("items", stringSchema(100))
            .put("maxItems", maxItems)

        val countedObject = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("name", stringSchema(80))
                    .put("count", JSONObject().put("type", "integer").put("minimum", 1))
                    .put("attributes", stringArray(10)),
            )
            .put("required", JSONArray(listOf("name", "count", "attributes")))
            .put("additionalProperties", false)
        val people = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "presentation",
                        JSONObject().put(
                            "enum",
                            JSONArray(listOf("男性呈现", "女性呈现", "儿童", "多人混合", "不确定")),
                        ),
                    )
                    .put("count", JSONObject().put("type", "integer").put("minimum", 1)),
            )
            .put("required", JSONArray(listOf("presentation", "count")))
            .put("additionalProperties", false)
        val facets = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "daylight",
                        JSONObject().put(
                            "enum",
                            JSONArray(listOf("天亮", "天黑", "日出日落", "室内", "不确定")),
                        ),
                    )
                    .put("sky", stringArray(10))
                    .put(
                        "objects",
                        JSONObject().put("type", "array").put("items", countedObject).put("maxItems", 30),
                    )
                    .put(
                        "people",
                        JSONObject().put("type", "array").put("items", people).put("maxItems", 10),
                    )
                    .put("actions", stringArray(20))
                    .put("scenes", stringArray(10))
                    .put("weather", stringArray(10)),
            )
            .put(
                "required",
                JSONArray(listOf("daylight", "sky", "objects", "people", "actions", "scenes", "weather")),
            )
            .put("additionalProperties", false)

        val markerNames = markers.map(LocalSubjectMarker::marker)
        val markerSchema = if (markerNames.isEmpty()) {
            stringSchema(12)
        } else {
            JSONObject().put("enum", JSONArray(markerNames))
        }
        val subjectMention = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("marker", markerSchema)
                    .put("kind", JSONObject().put("enum", JSONArray(listOf("人物", "宠物"))))
                    .put("description", stringSchema(100))
                    .put("actions", stringArray(10)),
            )
            .put("required", JSONArray(listOf("marker", "kind", "description", "actions")))
            .put("additionalProperties", false)
        val subjectMentions = JSONObject()
            .put("type", "array")
            .put("items", subjectMention)
            .put("maxItems", if (markerNames.isEmpty()) 0 else markerNames.size)

        return JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("caption", stringSchema(500))
                    .put("tags", stringArray(40))
                    .put("visible_text", stringSchema(VISIBLE_TEXT_SCHEMA_MAX_LENGTH))
                    .put("facets", facets)
                    .put("subject_mentions", subjectMentions),
            )
            .put(
                "required",
                JSONArray(listOf("caption", "tags", "visible_text", "facets", "subject_mentions")),
            )
            .put("additionalProperties", false)
    }

    @Deprecated("Android VLM no longer receives identity candidates")
    fun schema(profile: RecognitionProfile): JSONObject = schema(emptyList())

    fun parseVlm(value: JSONObject, markers: List<LocalSubjectMarker>): PhotoAnnotation {
        requireExactKeys(
            value,
            setOf("caption", "tags", "visible_text", "facets", "subject_mentions"),
            "标注",
        )
        val annotation = parseCurrent(value)
        val offered = markers.associateBy(LocalSubjectMarker::marker)
        require(annotation.subjectMentions.map(SubjectMention::marker).distinct().size == annotation.subjectMentions.size) {
            "同一匿名标记只能出现一次"
        }
        annotation.subjectMentions.forEach { mention ->
            val marker = offered[mention.marker]
                ?: throw IllegalArgumentException("VLM 返回了未提供的匿名标记 ${mention.marker}")
            require(mention.kind == marker.kind.displayName) {
                "匿名标记 ${mention.marker} 的 kind 与手机本地类型不一致"
            }
        }
        markerPattern.findAll(value.toString()).forEach { match ->
            require(match.value in offered) { "标注包含未提供的匿名标记 ${match.value}" }
        }
        val responseText = value.toString().lowercase()
        markers.mapNotNull(LocalSubjectMarker::matchedName)
            .filter { it.length >= 2 }
            .forEach { localName ->
                require(!responseText.contains(localName.lowercase())) {
                    "VLM 输出包含本地身份名称；姓名只能由手机本地替换"
                }
        }
        return annotation
    }

    fun parse(value: JSONObject, profile: RecognitionProfile? = null): PhotoAnnotation {
        val keys = value.keys().asSequence().toSet()
        return when (keys) {
            setOf("caption", "tags", "visible_text", "facets", "subject_mentions") -> parseCurrent(value)
            setOf("caption", "tags", "visible_text", "facets", "recognized_subjects") -> {
                val legacy = parseLegacy(value)
                profile?.validate(legacy.recognizedSubjects)
                legacy
            }
            setOf("caption", "tags", "visible_text", "facets") -> {
                require(profile == null) { "标注字段不完整" }
                parseLegacy(value)
            }
            else -> throw IllegalArgumentException("标注字段不完整或包含未知字段")
        }
    }

    fun toJson(annotation: PhotoAnnotation): JSONObject {
        val valid = FacetRules.validate(annotation)
        return JSONObject()
            .put("caption", valid.caption)
            .put("tags", JSONArray(valid.tags))
            .put("visible_text", valid.visibleText)
            .put("facets", facetsJson(valid.facets))
            .put("subject_mentions", mentionsJson(valid.subjectMentions))
            .put("recognized_subjects", subjectsJson(valid.recognizedSubjects))
    }

    private fun parseCurrent(value: JSONObject): PhotoAnnotation = FacetRules.validate(
        PhotoAnnotation(
            caption = value.getString("caption"),
            tags = strings(value.getJSONArray("tags"), "tags"),
            visibleText = value.getString("visible_text"),
            facets = parseFacets(value.getJSONObject("facets")),
            subjectMentions = mentions(value.getJSONArray("subject_mentions")),
        ),
    )

    private fun parseLegacy(value: JSONObject): PhotoAnnotation = FacetRules.validate(
        PhotoAnnotation(
            caption = value.getString("caption"),
            tags = strings(value.getJSONArray("tags"), "tags"),
            visibleText = value.getString("visible_text"),
            facets = parseFacets(value.getJSONObject("facets")),
            recognizedSubjects = if (value.has("recognized_subjects")) {
                recognizedSubjects(value.getJSONArray("recognized_subjects"))
            } else {
                emptyList()
            },
        ),
    )

    private fun parseFacets(facets: JSONObject): PhotoFacets {
        requireExactKeys(
            facets,
            setOf("daylight", "sky", "objects", "people", "actions", "scenes", "weather"),
            "facets",
        )
        return PhotoFacets(
            daylight = facets.getString("daylight"),
            sky = strings(facets.getJSONArray("sky"), "sky"),
            objects = objects(facets.getJSONArray("objects")),
            people = people(facets.getJSONArray("people")),
            actions = strings(facets.getJSONArray("actions"), "actions"),
            scenes = strings(facets.getJSONArray("scenes"), "scenes"),
            weather = strings(facets.getJSONArray("weather"), "weather"),
        )
    }

    private fun facetsJson(facets: PhotoFacets): JSONObject = JSONObject()
        .put("daylight", facets.daylight)
        .put("sky", JSONArray(facets.sky))
        .put(
            "objects",
            JSONArray().also { array ->
                facets.objects.forEach { item ->
                    array.put(
                        JSONObject()
                            .put("name", item.name)
                            .put("count", item.count)
                            .put("attributes", JSONArray(item.attributes)),
                    )
                }
            },
        )
        .put(
            "people",
            JSONArray().also { array ->
                facets.people.forEach { item ->
                    array.put(JSONObject().put("presentation", item.presentation).put("count", item.count))
                }
            },
        )
        .put("actions", JSONArray(facets.actions))
        .put("scenes", JSONArray(facets.scenes))
        .put("weather", JSONArray(facets.weather))

    private fun mentionsJson(mentions: List<SubjectMention>): JSONArray = JSONArray().also { array ->
        mentions.forEach { mention ->
            array.put(
                JSONObject()
                    .put("marker", mention.marker)
                    .put("kind", mention.kind)
                    .put("description", mention.description)
                    .put("actions", JSONArray(mention.actions)),
            )
        }
    }

    private fun subjectsJson(subjects: List<RecognizedSubject>): JSONArray = JSONArray().also { array ->
        subjects.forEach { subject ->
            array.put(JSONObject().put("name", subject.name).put("kind", subject.kind))
        }
    }

    private fun objects(array: JSONArray): List<CountedObject> = buildList {
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            requireExactKeys(item, setOf("name", "count", "attributes"), "objects[$index]")
            add(
                CountedObject(
                    name = item.getString("name"),
                    count = item.getInt("count"),
                    attributes = strings(item.getJSONArray("attributes"), "objects[$index].attributes"),
                ),
            )
        }
    }

    private fun people(array: JSONArray): List<PresentedPeople> = buildList {
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            requireExactKeys(item, setOf("presentation", "count"), "people[$index]")
            add(PresentedPeople(item.getString("presentation"), item.getInt("count")))
        }
    }

    private fun mentions(array: JSONArray): List<SubjectMention> = buildList {
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            requireExactKeys(item, setOf("marker", "kind", "description", "actions"), "subject_mentions[$index]")
            add(
                SubjectMention(
                    marker = item.getString("marker"),
                    kind = item.getString("kind"),
                    description = item.getString("description"),
                    actions = strings(item.getJSONArray("actions"), "subject_mentions[$index].actions"),
                ),
            )
        }
    }

    private fun recognizedSubjects(array: JSONArray): List<RecognizedSubject> = buildList {
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            requireExactKeys(item, setOf("name", "kind"), "recognized_subjects[$index]")
            add(RecognizedSubject(name = item.getString("name"), kind = item.getString("kind")))
        }
    }

    private fun strings(array: JSONArray, name: String): List<String> = buildList {
        for (index in 0 until array.length()) {
            val value = array.get(index)
            require(value is String) { "$name[$index] 必须是字符串" }
            add(value)
        }
    }

    private fun requireExactKeys(value: JSONObject, keys: Set<String>, name: String) {
        val actual = value.keys().asSequence().toSet()
        require(actual == keys) { "$name 字段不完整或包含未知字段" }
    }
}
