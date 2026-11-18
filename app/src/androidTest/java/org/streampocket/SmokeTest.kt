package org.streampocket
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun emptyStartAndTabs() {
        ui.waitUntil(10000) { ui.onAllNodesWithTag("openSearch").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("nav:1").performClick()
        ui.onNodeWithContentDescription("添加订阅").performClick()
        ui.onNodeWithTag("subscriptionInput").assertExists().performTextInput("https://example.org/sub.json")
        ui.onNode(hasText("添加订阅") and hasClickAction()).assertIsEnabled()
        ui.onNodeWithContentDescription("关闭").performClick()
        ui.onNodeWithTag("nav:2").performClick()
        ui.onNodeWithTag("historyGroups").assertExists()
    }
    @Test fun rotationRecreatesWithoutCrash() {
        ui.waitUntil(10000) { ui.onAllNodesWithTag("openSearch").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("openSearch").performClick()
        ui.onNodeWithTag("searchInput").performTextInput("Synthetic title")
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("searchInput").assertTextContains("Synthetic title")
    }
}
