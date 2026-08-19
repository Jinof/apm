package com.jinof.apm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FacetRulesTest {
    @Test
    fun indexesDaylightCountsActionsAndPeopleSearchAliases() {
        val annotation = sampleAnnotation()

        val text = FacetRules.searchText(annotation)

        assertTrue(text.contains("天黑"))
        assertTrue(text.contains("狗"))
        assertTrue(text.contains("两只狗"))
        assertTrue(text.contains("女人"))
        assertTrue(text.contains("跑步"))
        assertTrue(text.contains("公园"))
        assertTrue(text.contains("旺财"))
    }

    @Test
    fun rejectsZeroObjectCount() {
        val invalid = sampleAnnotation().copy(
            facets = sampleAnnotation().facets.copy(
                objects = listOf(CountedObject("狗", 0, emptyList())),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            FacetRules.validate(invalid)
        }
    }

    @Test
    fun rejectsUnknownPeoplePresentationInsteadOfGuessing() {
        val invalid = sampleAnnotation().copy(
            facets = sampleAnnotation().facets.copy(
                people = listOf(PresentedPeople("女人", 1)),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            FacetRules.validate(invalid)
        }
    }

    @Test
    fun recognitionProfileNormalizesAndSeparatesKinds() {
        val profile = RecognitionProfile.fromText(" 小明，小红，小明 ", "旺财\n咪咪")

        assertEquals(listOf("小明", "小红"), profile.personNames)
        assertEquals(listOf("旺财", "咪咪"), profile.petNames)
        assertThrows(IllegalArgumentException::class.java) {
            RecognitionProfile.fromText("小明", "小明")
        }
    }

    @Test
    fun annotationContractRejectsNameOutsidePreset() {
        val profile = RecognitionProfile(personNames = listOf("小明"), petNames = listOf("旺财"))

        assertThrows(IllegalArgumentException::class.java) {
            profile.validate(listOf(RecognizedSubject("陌生人", "人物")))
        }
        profile.validate(listOf(RecognizedSubject("旺财", "宠物")))
    }

    private fun sampleAnnotation() = PhotoAnnotation(
        caption = "夜晚的公园里，一位女性呈现的人带着两只狗跑步",
        tags = listOf("夜晚", "狗", "公园"),
        visibleText = "",
        facets = PhotoFacets(
            daylight = "天黑",
            sky = emptyList(),
            objects = listOf(CountedObject("狗", 2, listOf("棕色"))),
            people = listOf(PresentedPeople("女性呈现", 1)),
            actions = listOf("跑步"),
            scenes = listOf("公园"),
            weather = emptyList(),
        ),
        recognizedSubjects = listOf(RecognizedSubject("旺财", "宠物")),
    )
}
