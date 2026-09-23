package com.jinof.apm

import org.json.JSONArray
import org.json.JSONObject

object AnnotationContract {
    const val PROMPT_VERSION = "photo-annotation-zh-v7"
    const val VISIBLE_TEXT_SCHEMA_MAX_LENGTH = 512
    const val GRAMMAR_MAX_REPETITION_EXCLUSIVE = 2000


    private const val BASE_PROMPT = """你是私人相册检索系统的照片标注器。只描述图中可见内容，不猜测人物或宠物身份、关系、地点、族裔、健康等敏感属性。
人物的男性/女性标签只表示可见外观呈现，不代表实际性别；无法判断时必须输出“不确定”。数量看不清时按明确可见的最小数量记录。
请用中文输出一句客观 caption，并分别给出：
- tags：通用检索词，不含姓名；
- visible_text：清晰可辨的原文，最多 512 字符，没有则为空字符串；
- facets.daylight：天亮、天黑、日出日落、室内、不确定之一；
- facets.sky：天空现象，如蓝天、云、晚霞；
- facets.objects：物体 name、正整数 count、可见 attributes；
- facets.people：男性呈现、女性呈现、儿童、多人混合、不确定及正整数 count；
- facets.actions：可见动作；facets.scenes：场景；facets.weather：天气；
没有内容的数组使用 []。严格符合 JSON Schema，不添加解释或身份字段。"""

    fun prompt(): String = BASE_PROMPT

    fun schema(): JSONObject {
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
                        JSONObject().put("type", "string").put(
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
                        JSONObject().put("type", "string").put(
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

        return JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("caption", stringSchema(500))
                    .put("tags", stringArray(40))
                    .put("visible_text", stringSchema(VISIBLE_TEXT_SCHEMA_MAX_LENGTH))
                    .put("facets", facets),
            )
            .put(
                "required",
                JSONArray(listOf("caption", "tags", "visible_text", "facets")),
            )
            .put("additionalProperties", false)
    }

    fun parseVlm(value: JSONObject): PhotoAnnotation {
        validateApiValue(value, schema(), "标注")
        return parseLegacy(value)
    }

    /** Check new API output before org.json getters can coerce types; stored legacy rows keep their parser. */
    private fun validateApiValue(value: Any?, schema: JSONObject, path: String) {
        when (schema.getString("type")) {
            "object" -> {
                require(value is JSONObject) { "$path 必须是对象" }
                val properties = schema.getJSONObject("properties")
                requireExactKeys(value, properties.keys().asSequence().toSet(), path)
                properties.keys().forEach { key ->
                    validateApiValue(value.get(key), properties.getJSONObject(key), "$path.$key")
                }
            }
            "array" -> {
                require(value is JSONArray) { "$path 必须是数组" }
                require(value.length() <= schema.getInt("maxItems")) { "$path 项目数量超出限制" }
                for (index in 0 until value.length()) {
                    validateApiValue(value.get(index), schema.getJSONObject("items"), "$path[$index]")
                }
            }
            "string" -> {
                require(value is String) { "$path 必须是字符串" }
                if (schema.has("maxLength")) {
                    require(value.codePointCount(0, value.length) <= schema.getInt("maxLength")) { "$path 长度超出限制" }
                }
                if (schema.has("enum")) {
                    val choices = schema.getJSONArray("enum")
                    require((0 until choices.length()).any { value == choices.getString(it) }) { "$path 取值不合法" }
                }
            }
            "integer" -> {
                val number = (value as? Number)?.toString()?.toBigDecimalOrNull()
                require(number != null && number.stripTrailingZeros().scale() <= 0 &&
                    number >= schema.getInt("minimum").toBigDecimal() && number <= Int.MAX_VALUE.toBigDecimal()) {
                    "$path 必须是范围内的整数"
                }
            }
            else -> error("标注 schema 类型不受支持")
        }
    }

    fun parse(value: JSONObject): PhotoAnnotation {
        val keys = value.keys().asSequence().toSet()
        return when (keys) {
            setOf("caption", "tags", "visible_text", "facets", "subject_mentions") -> parseCurrent(value)
            setOf("caption", "tags", "visible_text", "facets", "recognized_subjects"),
            setOf("caption", "tags", "visible_text", "facets") -> parseLegacy(value)
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
