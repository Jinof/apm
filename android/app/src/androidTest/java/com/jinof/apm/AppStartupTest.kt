package com.jinof.apm

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import org.junit.Rule
import org.junit.Test

class AppStartupTest {
    @get:Rule val rule = createComposeRule()

    @Test fun loadingTaglineAutomaticallyGivesWayToContentWithoutAnyButton() {
        var loading by mutableStateOf(true)
        rule.setContent {
            ApmTheme {
                AppStartupContent(loading) {
                    Text("首页已就绪", Modifier.testTag("startup_home_content"))
                }
            }
        }
        rule.onNodeWithTag("startup_loading").assertIsDisplayed()
        rule.onNodeWithTag("startup_tagline").assertTextEquals("让照片，\n说出自己的故事")
        rule.onAllNodes(hasClickAction()).assertCountEquals(0)
        rule.onNodeWithTag("startup_home_content").assertDoesNotExist()
        rule.onRoot().saveRoadmapScreenshot("startup-loading.png")

        rule.runOnIdle { loading = false }

        rule.onNodeWithTag("startup_home_content").assertIsDisplayed()
        rule.onNodeWithTag("startup_loading").assertDoesNotExist()
        rule.onNodeWithTag("startup_story_dialog").assertDoesNotExist()
        rule.onNodeWithTag("startup_story_continue").assertDoesNotExist()
        rule.onNodeWithTag("startup_story_skip").assertDoesNotExist()
    }
}
