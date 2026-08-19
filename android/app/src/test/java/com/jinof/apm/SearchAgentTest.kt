package com.jinof.apm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SearchAgentTest {
    private val first = photo("first")
    private val second = photo("second")

    @Test
    fun allPlanInvokesSearchSkillAndIntersectsResults() {
        val calls = mutableListOf<String>()
        val skill = fakeSkill(calls) { query ->
            when (query) {
                "天黑" -> listOf(first, second)
                "旺财" -> listOf(second)
                else -> emptyList()
            }
        }
        val agent = SearchAgent(
            planner = AgentPlanner { AgentSearchPlan("夜晚的旺财", listOf("天黑", "旺财"), "all") },
            searchSkill = skill,
        )

        val result = agent.run("找夜晚有旺财的照片")

        assertEquals(listOf("天黑", "旺财"), calls)
        assertEquals(listOf("second"), result.photos.map(PhotoCard::photoId))
        assertEquals(listOf(2, 1), result.invocations.map(SearchSkillInvocation::resultCount))
    }

    @Test
    fun anyPlanDeduplicatesPhotos() {
        val agent = SearchAgent(
            planner = AgentPlanner { AgentSearchPlan("宠物", listOf("狗", "猫"), "any") },
            searchSkill = fakeSkill(mutableListOf()) { listOf(first, second) },
        )

        assertEquals(listOf("first", "second"), agent.run("狗或猫").photos.map(PhotoCard::photoId))
    }

    @Test
    fun rejectsEmptyOrOverBroadPlans() {
        val skill = fakeSkill(mutableListOf()) { emptyList() }
        assertThrows(IllegalArgumentException::class.java) {
            SearchAgent(AgentPlanner { AgentSearchPlan("", emptyList(), "all") }, skill).run("照片")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SearchAgent(AgentPlanner { AgentSearchPlan("", listOf("照片"), "delete") }, skill).run("照片")
        }
    }

    private fun fakeSkill(
        calls: MutableList<String>,
        block: (String) -> List<PhotoCard>,
    ): PhotoSearchSkill = object : PhotoSearchSkill {
        override val name = "search_photos"
        override val description = "test"
        override fun invoke(query: String, limit: Int): List<PhotoCard> {
            calls += query
            return block(query)
        }
    }

    private fun photo(id: String) = PhotoCard(
        photoId = id,
        uri = "content://photo/$id",
        displayName = "$id.jpg",
        caption = id,
        annotation = PhotoAnnotation(
            caption = id,
            tags = listOf(id),
            visibleText = "",
            facets = PhotoFacets("天亮", emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
        ),
        modelName = "model",
        promptVersion = "prompt",
        annotatedAt = "2026-08-13T00:00:00Z",
        dateTakenMillis = null,
    )
}
