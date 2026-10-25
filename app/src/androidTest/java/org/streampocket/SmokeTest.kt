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
        ui.waitUntil(5000) { ui.onAllNodesWithTag("searchInput").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("订阅").performClick()
        ui.onNodeWithTag("subscriptionInput").assertExists().performTextInput("https://example.org/sub.json")
        ui.onNodeWithText("添加订阅").assertIsEnabled()
        ui.onNodeWithText("历史").performClick()
        ui.onNodeWithText("继续观看").assertExists()
    }
    @Test fun rotationRecreatesWithoutCrash() {
        ui.waitUntil(5000) { ui.onAllNodesWithTag("searchInput").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("searchInput").performTextInput("Synthetic title")
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("searchInput").assertTextContains("Synthetic title")
    }
}
