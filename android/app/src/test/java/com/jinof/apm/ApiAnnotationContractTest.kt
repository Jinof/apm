package com.jinof.apm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ApiAnnotationContractTest {
    @Test fun rejectsFractionalCoercedAndOverflowingCountsBeforeIntegerConversion() {
        for (field in listOf("objects", "people")) {
            for (raw in listOf("1.5", "\"2\"", "2147483648", "4294967297", "1e100", "true", "null", "0", "-1", "1.00000000000000000001")) {
                val value = valid()
                value.getJSONObject("facets").getJSONArray(field).getJSONObject(0)
                    .put("count", JSONObject("{\"count\":$raw}").get("count"))
                assertThrows("$field count $raw", IllegalArgumentException::class.java) {
                    AnnotationContract.parseVlm(value)
                }
            }
        }
        val integralDecimal = valid()
        integralDecimal.getJSONObject("facets").getJSONArray("objects").getJSONObject(0).put("count", 2.0)
        assertEquals(2, AnnotationContract.parseVlm(integralDecimal).facets.objects.single().count)
    }

    @Test fun rejectsNonStringFieldsAndArrayMembersIncludingNestedValues() {
        for (field in listOf("caption", "visible_text")) {
            for (raw in listOf<Any>(42, true, JSONObject.NULL, JSONObject())) {
                assertInvalid(valid().put(field, raw))
            }
        }
        for (field in listOf("tags", "facets", "subject_mentions")) assertInvalid(valid().put(field, "wrong type"))
        for (field in listOf("sky", "actions", "scenes", "weather")) {
            val value = valid()
            value.getJSONObject("facets").put(field, JSONArray().put(42))
            assertInvalid(value)
        }
        assertInvalid(valid().put("tags", JSONArray().put(42)))
        for ((array, field) in listOf("objects" to "name", "people" to "presentation")) {
            val value = valid()
            value.getJSONObject("facets").getJSONArray(array).getJSONObject(0).put(field, 42)
            assertInvalid(value)
        }
        val daylight = valid()
        daylight.getJSONObject("facets").put("daylight", 42)
        assertInvalid(daylight)
        val attributes = valid()
        attributes.getJSONObject("facets").getJSONArray("objects").getJSONObject(0).put("attributes", JSONArray().put(true))
        assertInvalid(attributes)
    }

    @Test fun enforcesNewApiSchemaLimitsWhileReadingLegacyRowsUnchanged() {
        val boundary = valid().put("visible_text", "字".repeat(512))
        assertEquals(512, AnnotationContract.parseVlm(boundary).visibleText.length)
        val historical = valid().put("visible_text", "字".repeat(513))
        assertInvalid(historical)
        assertEquals(513, AnnotationContract.parse(historical).visibleText.length)
        assertInvalid(valid().put("caption", "字".repeat(501)))
        assertInvalid(valid().put("tags", JSONArray().put("字".repeat(101))))
        assertInvalid(valid().put("tags", JSONArray(List(41) { "标签" })))
        val sky = valid()
        sky.getJSONObject("facets").put("sky", JSONArray(List(11) { "云" }))
        assertInvalid(sky)
        val longName = valid()
        longName.getJSONObject("facets").getJSONArray("objects").getJSONObject(0).put("name", "字".repeat(81))
        assertInvalid(longName)
    }

    @Test fun rejectsUnknownMissingAndInvalidEnumFieldsAtEveryDepth() {
        assertInvalid(valid().put("unexpected", "value"))
        val missing = valid().apply { remove("caption") }
        assertInvalid(missing)
        val nested = valid()
        nested.getJSONObject("facets").getJSONArray("objects").getJSONObject(0).put("unexpected", "value")
        assertInvalid(nested)
        val daylight = valid()
        daylight.getJSONObject("facets").put("daylight", "invalid")
        assertInvalid(daylight)
    }

    @Test fun newApiOmitsIdentityFieldsWhileLegacyAnnotationsRemainReadable() {
        val old = valid().put("subject_mentions", JSONArray().put(JSONObject(
            """{"marker":"P1","kind":"人物","description":"站立的人物","actions":[]}""")))
        assertInvalid(old)
        assertEquals("P1", AnnotationContract.parse(old).subjectMentions.single().marker)
        assertInvalid(valid().put("recognized_subjects", JSONArray()))
    }

    private fun assertInvalid(value: JSONObject) {
        assertThrows(IllegalArgumentException::class.java) { AnnotationContract.parseVlm(value) }
    }

    private fun valid(): JSONObject = JSONObject("""{
      "caption":"一只猫在室内","tags":["猫"],"visible_text":"",
      "facets":{"daylight":"室内","sky":[],"objects":[{"name":"猫","count":1,"attributes":[]}],
      "people":[{"presentation":"不确定","count":1}],"actions":[],"scenes":[],"weather":[]}
    }""")
}
