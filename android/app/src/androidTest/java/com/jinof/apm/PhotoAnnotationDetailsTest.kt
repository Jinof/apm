package com.jinof.apm

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PhotoAnnotationDetailsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun localSearchDetailsShowFullSavedFieldsAndDoNotOpenViewer() {
        val annotation = PhotoAnnotation("这是完整的历史描述。".repeat(30), listOf("猫", "午后"), "完整 OCR 文本\n".repeat(80),
            PhotoFacets("天亮", listOf("蓝天"), listOf(CountedObject("猫", 2, listOf("白色", "坐着"))),
                listOf(PresentedPeople("儿童", 1)), listOf("阅读"), listOf("公园"), listOf("晴天")),
            listOf(SubjectMention("PET1", "宠物", "历史白猫", listOf("坐着"))),
            listOf(RecognizedSubject("历史名字", "宠物")))
        val card = PhotoCard("search-photo", "content://apm.test/search", "搜索中的照片.jpg", annotation.caption,
            annotation, "saved-model", "saved-prompt", "2026-09-20T12:00:00Z", null)
        var opened = 0
        rule.setContent { MaterialTheme { PhotoResultCard(card) { opened++ } } }
        rule.onNodeWithTag("photo_annotation_badge", useUnmergedTree = true).performClick()
        for ((tag, text) in listOf(
            "filename" to card.displayName, "caption" to annotation.caption, "tags" to "猫、午后",
            "ocr" to annotation.visibleText, "daylight" to "天亮", "sky" to "蓝天",
            "objects" to "猫 × 2 · 白色、坐着", "people" to "儿童 × 1", "actions" to "阅读",
            "scenes" to "公园", "weather" to "晴天", "legacy_identities" to "宠物 · 历史名字",
            "legacy_subjects" to "PET1 · 宠物 · 历史白猫 · 坐着", "model" to "saved-model",
            "prompt" to "saved-prompt", "time" to card.annotatedAt,
        )) rule.onNodeWithTag("annotation_detail_$tag").performScrollTo().assertTextEquals(text)
        rule.onNodeWithTag("annotation_detail_filename").performScrollTo()
        rule.onNodeWithTag("annotation_details_dialog").saveRoadmapScreenshot("annotation-details.png")
        rule.runOnIdle { assertEquals(0, opened) }
        rule.onNodeWithTag("close_annotation_details").performClick()
        rule.onNodeWithTag("annotation_details_dialog").assertDoesNotExist()
        rule.onNodeWithText(annotation.caption, substring = false).performClick()
        rule.runOnIdle { assertEquals(1, opened) }
    }
}
