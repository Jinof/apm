package com.jinof.apm

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AlbumSearchLayoutTest {
    @get:Rule val rule = createComposeRule()

    private val longTag = "城市公园里的步行路线".repeat(5)
    private val facets = PhotoFacets("天亮", emptyList(), emptyList(), emptyList(),
        emptyList(), listOf("公园"), listOf("晴天"))
    private val first = photo(1, listOf("公园", longTag, "湖边", "步道", "树木", "倒影"))
    private val second = photo(2, listOf("公园", longTag))

    @Test fun resultRowsShowOneThumbnailAndOnlyWrappingTagsAndCategories() {
        val results = listOf(first, second)
        showSearch(GalleryUiState(query = "公园", activeSearchQuery = "公园", results = results,
            galleryPhotos = results.map(::galleryPhoto), search = SearchAvailability.derive(2, emptyList())))
        val row = rule.onNodeWithTag("search_result_p1").fetchSemanticsNode().boundsInRoot
        val next = rule.onNodeWithTag("search_result_p2").fetchSemanticsNode().boundsInRoot
        assertTrue(next.top >= row.bottom)
        assertEquals(row.left, next.left, 1f)
        val image = rule.onNodeWithTag("result_thumbnail_p1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val tags = first.annotation.tags.joinToString("、")
        val tagNode = rule.onNodeWithText(tags, useUnmergedTree = true)
        assertTrue(tagNode.fetchSemanticsNode().boundsInRoot.left >= image.right)
        assertWrappedInFull(tagNode, tags)
        val category = "天色 · 天亮\n场景 · 公园\n天气 · 晴天"
        val categoryNode = rule.onNode(hasText(category) and hasAnyAncestor(hasTestTag("search_result_p1")), true)
        assertTrue(categoryNode.fetchSemanticsNode().boundsInRoot.left >= image.right)
        for (other in listOf(first.caption, first.displayName, first.annotation.visibleText, first.modelName, "历史身份")) {
            rule.onNodeWithText(other, useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test fun allTagsOpensBesideSubmitWrapsLongLabelsAndCanReachTheLastTag() {
        val rest = (3..66).map { photo(it, listOf("公园", "编号${it.toString().padStart(2, '0')}")) }
        val photos = (listOf(first, second) + rest).map(::galleryPhoto)
        var selected = ""
        showSearch(GalleryUiState(query = "原查询", activeSearchQuery = "原查询", results = listOf(first),
            galleryPhotos = photos, search = SearchAvailability.fromGallery(photos)), onTag = { selected = it })
        val button = rule.onNodeWithTag("all_tags_button")
        val submit = rule.onNodeWithContentDescription("提交搜索", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val browse = button.fetchSemanticsNode().boundsInRoot
        assertTrue(browse.right <= submit.left && browse.top < submit.center.y && browse.bottom > submit.center.y)
        button.performClick()
        val ranked = rule.onNodeWithTag("ranked_tags")
        val park = rule.onNode(hasText("公园") and hasText("66 张") and hasAnyAncestor(hasTestTag("ranked_tags")))
        val long = rule.onNode(hasText(longTag) and hasText("2 张") and hasAnyAncestor(hasTestTag("ranked_tags")))
        val firstBounds = park.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val secondBounds = long.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(firstBounds.top < secondBounds.top || firstBounds.top == secondBounds.top && firstBounds.left < secondBounds.left)
        val label = rule.onNode(hasText(longTag) and hasAnyAncestor(hasTestTag("ranked_tags")), useUnmergedTree = true)
        assertWrappedInFull(label, longTag)

        ranked.performScrollToKey("编号66")
        rule.onNode(hasText("编号66") and hasAnyAncestor(hasTestTag("ranked_tags"))).assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.onNodeWithTag("all_tags_dialog").assertDoesNotExist()
        rule.onNodeWithTag("search_input").assertTextContains("原查询")
        rule.onNodeWithTag("search_result_p1").assertIsDisplayed()

        button.performClick()
        park.performClick()
        rule.onNodeWithTag("all_tags_dialog").assertDoesNotExist()
        rule.runOnIdle { assertEquals("公园", selected) }
    }

    @Test fun emptyAlbumHasAnEmptyTagDialog() {
        showSearch(GalleryUiState())
        rule.onNodeWithTag("all_tags_button").performClick()
        rule.onNodeWithText("暂无标签").assertIsDisplayed()
        rule.onNodeWithText("关闭").performClick()
        rule.onNodeWithTag("search_input").assertIsNotEnabled()
    }

    @Test fun agentResultsUseThePhotoRowAndOpenTheSelectedPhoto() {
        val result = AgentSearchResult(AgentSearchPlan("查找公园", listOf("公园"), "any"),
            listOf(SearchSkillInvocation("公园", 2)), listOf(first, second))
        var opened: PhotoCard? = null
        rule.setContent {
            ApmTheme {
                AgentSearchScreen(AgentUiState(result = result), GalleryUiState(), {}, {}, {}, {}, {},
                    onPhoto = { opened = it })
            }
        }
        rule.onNodeWithTag("agent_content").performScrollToKey(second.photoId)
        rule.onNodeWithTag("result_thumbnail_p2", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("search_result_p2").performClick()
        rule.runOnIdle { assertEquals(second, opened) }
    }

    private fun assertWrappedInFull(node: SemanticsNodeInteraction, text: String) {
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getLayout ->
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(getLayout(layouts))
            val layout = layouts.single()
            assertTrue(layout.lineCount > 1)
            assertFalse(layout.hasVisualOverflow)
            assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
        }
    }

    private fun showSearch(state: GalleryUiState, onTag: (String) -> Unit = {}) {
        rule.setContent {
            ApmTheme { GalleryScreen(state, {}, {}, onTag, {}, null, {}, { _, _ -> }) }
        }
        rule.onNodeWithTag("open_album_search").performClick()
    }

    private fun photo(index: Int, tags: List<String>): PhotoCard {
        val annotation = PhotoAnnotation("完整描述$index", tags, "识别文字$index", facets,
            recognizedSubjects = listOf(RecognizedSubject("历史身份", "人物")))
        return PhotoCard("p$index", "content://apm.test/$index", "照片$index.jpg", annotation.caption,
            annotation, "测试模型", "测试版本", "2026-11-21", null)
    }

    private fun galleryPhoto(photo: PhotoCard) = GalleryPhotoCard(photo.photoId, photo.uri, photo.displayName,
        photo.dateTakenMillis, photo.annotation, photo.modelName, photo.promptVersion, photo.annotatedAt)
}
