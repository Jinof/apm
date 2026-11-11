package com.jinof.apm

import org.json.JSONArray
import org.json.JSONObject

/** Slots are request-local labels, never persistent photo IDs or array-position guesses. */
internal object BatchAnnotationContract {
    const val PROMPT_VERSION = "photo-annotation-collage-zh-v1"
    val compatiblePromptVersions get() = listOf(AnnotationContract.PROMPT_VERSION, PROMPT_VERSION)

    fun prompt(count: Int): String {
        require(count in 1..8)
        return """输入是一张由 $count 张独立照片组成的合图，每张照片上方黑色边框内标有 #1 至 #$count。
按从左到右、从上到下的编号逐张独立标注，不把不同照片中的物体、文字、场景合并。
黑色边框、编号和留白不是原照片内容，不得写入 caption、tags、visible_text 或 facets；无编号的空格忽略。
输出 photos 数组，恰好包含 $count 项。每项包含整数 slot 和 annotation，slot 对应照片上方的编号，必须覆盖 1 至 $count 且不能重复。
每项 annotation 严格遵守下面的单张照片规则和提供的 Schema：
${AnnotationContract.prompt()}"""
    }

    fun schema(count: Int): JSONObject {
        require(count in 1..8)
        val item = JSONObject().put("type", "object").put("properties", JSONObject()
            .put("slot", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", count))
            .put("annotation", AnnotationContract.schema()))
            .put("required", JSONArray(listOf("slot", "annotation"))).put("additionalProperties", false)
        return JSONObject().put("type", "object").put("properties", JSONObject()
            .put("photos", JSONObject().put("type", "array").put("minItems", count).put("maxItems", count).put("items", item)))
            .put("required", JSONArray(listOf("photos"))).put("additionalProperties", false)
    }

    fun parse(value: JSONObject, count: Int): List<PhotoAnnotation> {
        require(count in 1..8)
        require(value.keys().asSequence().toSet() == setOf("photos")) { "合图结果字段不完整" }
        val entries = value.opt("photos") as? JSONArray ?: error("合图结果必须为数组")
        require(entries.length() == count) { "合图结果数量与照片数量不符" }
        val bySlot = mutableMapOf<Int, PhotoAnnotation>()
        for (index in 0 until entries.length()) {
            val item = entries.opt(index) as? JSONObject ?: error("合图结果条目必须为对象")
            require(item.keys().asSequence().toSet() == setOf("slot", "annotation")) { "合图条目字段不完整" }
            val slot = (item.opt("slot") as? Number)?.toString()?.toBigDecimalOrNull()
            require(slot != null && slot.stripTrailingZeros().scale() <= 0 &&
                slot >= 1.toBigDecimal() && slot <= count.toBigDecimal()) { "合图编号无效" }
            val number = slot.toInt()
            require(number !in bySlot) { "合图编号重复" }
            val annotation = item.opt("annotation") as? JSONObject ?: error("合图标注必须为对象")
            bySlot[number] = AnnotationContract.parseVlm(annotation)
        }
        // Count, range and uniqueness together prove complete coverage, independent of response order.
        return (1..count).map { bySlot.getValue(it) }
    }
}
