package com.jinof.apm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUiStateTest {
    @Test
    fun partialAndroid14AccessAlwaysRequestsReselection() {
        assertEquals(
            PhotoAccessAction.REQUEST_RESELECTION,
            PhotoAccessPolicy.nextScanAction(34, hasFullAccess = false, hasPartialAccess = true),
        )
        assertEquals(
            PhotoAccessAction.REQUEST_RESELECTION,
            PhotoAccessPolicy.nextScanAction(35, hasFullAccess = false, hasPartialAccess = true),
        )
    }

    @Test
    fun fullAccessScansWithoutPermissionPrompt() {
        assertEquals(
            PhotoAccessAction.SCAN_NOW,
            PhotoAccessPolicy.nextScanAction(35, hasFullAccess = true, hasPartialAccess = true),
        )
    }

    @Test
    fun searchStartsDisabledWithoutPresetSuggestions() {
        val state = SearchAvailability.derive(0, listOf("天黑", "狗"))

        assertFalse(state.enabled)
        assertTrue(state.suggestions.isEmpty())
    }

    @Test
    fun firstAnnotationEnablesOnlyDerivedSuggestions() {
        val annotation = PhotoAnnotation(
            caption = "夜晚的海边有两只狗在跑步",
            tags = listOf("海边", "狗"),
            visibleText = "",
            facets = PhotoFacets(
                daylight = "天黑",
                sky = listOf("云"),
                objects = listOf(CountedObject("狗", 2, emptyList())),
                people = emptyList(),
                actions = listOf("跑步"),
                scenes = listOf("海边"),
                weather = listOf("不确定"),
            ),
            recognizedSubjects = listOf(RecognizedSubject("旺财", "宠物")),
        )

        val suggestions = SuggestionBuilder.from(listOf(annotation))
        val state = SearchAvailability.derive(1, suggestions)

        assertTrue(state.enabled)
        assertEquals(listOf("海边", "狗", "天黑", "云", "跑步", "旺财"), state.suggestions)
        assertFalse(state.suggestions.contains("不确定"))
    }
}
