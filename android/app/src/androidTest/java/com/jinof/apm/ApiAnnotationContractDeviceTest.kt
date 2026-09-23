package com.jinof.apm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Exercises Android's coercive org.json getters without a service, photo or database mutation. */
class ApiAnnotationContractDeviceTest {
    @Test fun newApiRejectsCoercionAndSchemaViolationsWhileLegacyRowsRemainReadable() {
        assertEquals(1, AnnotationContract.parseVlm(valid()).facets.objects.single().count)
        for (raw in listOf("1.5", "\"2\"", "4294967297", "1e100", "true", "null")) {
            for (field in listOf("objects", "people")) {
                val value = valid()
                value.getJSONObject("facets").getJSONArray(field).getJSONObject(0)
                    .put("count", JSONObject("{\"count\":$raw}").get("count"))
                assertInvalid(value)
            }
        }
        assertInvalid(valid().put("caption", 42))
        assertInvalid(valid().put("visible_text", true))
        assertInvalid(valid().put("tags", JSONArray().put(42)))
        assertInvalid(valid().put("unexpected", "value"))
        val nested = valid()
        nested.getJSONObject("facets").getJSONArray("objects").getJSONObject(0).put("name", 42)
        assertInvalid(nested)
        val historical = valid().put("visible_text", "字".repeat(513))
        assertInvalid(historical)
        assertEquals(513, AnnotationContract.parse(historical).visibleText.length)
        assertEquals(512, AnnotationContract.parseVlm(valid().put("visible_text", "字".repeat(512))).visibleText.length)
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
