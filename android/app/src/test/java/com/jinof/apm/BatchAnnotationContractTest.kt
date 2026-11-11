package com.jinof.apm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BatchAnnotationContractTest {
    private fun annotation(slot: Int) = JSONObject("""{"caption":"照片 $slot","tags":[],"visible_text":"",
        "facets":{"daylight":"不确定","sky":[],"objects":[],"people":[],"actions":[],"scenes":[],"weather":[]}}""")
    private fun response(count: Int) = JSONObject().put("photos", JSONArray((1..count).map {
        JSONObject().put("slot", it).put("annotation", annotation(it))
    }))

    @Test fun oneThroughEightSlotsMapByNumberEvenWhenReversed() {
        for (count in 1..8) {
            val items = response(count).getJSONArray("photos")
            val reversed = JSONObject().put("photos", JSONArray((count - 1 downTo 0).map { items.getJSONObject(it) }))
            assertEquals((1..count).map { "照片 $it" }, BatchAnnotationContract.parse(reversed, count).map { it.caption })
            val request = AnnotationRequestFactory.createBatch("model", "synthetic-image", count)
            assertEquals(1536 * count, request.getInt("max_tokens"))
            val content = request.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
            assertEquals(2, content.length())
            assertEquals("image_url", content.getJSONObject(1).getString("type"))
            val schema = request.getJSONObject("response_format").getJSONObject("json_schema").getJSONObject("schema")
                .getJSONObject("properties").getJSONObject("photos")
            assertEquals(count, schema.getInt("minItems")); assertEquals(count, schema.getInt("maxItems"))
        }
    }

    @Test fun missingDuplicateExtraUnknownOrCoercedSlotsRejectTheWholeGroup() {
        fun invalid(value: JSONObject) = assertThrows(RuntimeException::class.java) { BatchAnnotationContract.parse(value, 2) }
        invalid(response(1)); invalid(response(3)); invalid(response(2).put("unknown", true))
        for (slot in listOf<Any>(0, 3, 1, "2", 1.5, JSONObject.NULL, true, 4294967297L)) {
            val r = response(2);r.getJSONArray("photos").getJSONObject(1).put("slot", slot);invalid(r)
        }
        invalid(response(2).put("photos", "bad"))
        val missing = response(2);missing.getJSONArray("photos").getJSONObject(1).remove("annotation");invalid(missing)
        val extra = response(2);extra.getJSONArray("photos").getJSONObject(1).put("unknown", false);invalid(extra)
    }

    @Test fun invalidLastAnnotationDoesNotProducePartialResults() {
        val r = response(8)
        r.getJSONArray("photos").getJSONObject(7).getJSONObject("annotation").put("caption", 12)
        assertThrows(RuntimeException::class.java) { BatchAnnotationContract.parse(r, 8) }
        assertThrows(IllegalArgumentException::class.java) { BatchAnnotationContract.schema(9) }
        assertThrows(IllegalArgumentException::class.java) { BatchAnnotationContract.prompt(0) }
    }

    @Test fun boundedLayoutsPreserveRowMajorSlotGeometryAndFinalEmptyCells() {
        for (edge in listOf(768, 1024)) for (count in 1..8) {
            val grid = CollageLayout.create(count, edge)
            assertTrue(grid.width <= edge && grid.height <= edge)
            assertTrue(grid.columns * grid.rows >= count)
            assertTrue(grid.cell > grid.labelHeight + 2 * grid.padding)
        }
        val eight = CollageLayout.create(8, 1024)
        assertEquals(3, eight.columns); assertEquals(3, eight.rows)
        assertEquals(1023, eight.width); assertEquals(1023, eight.height)
    }
}
