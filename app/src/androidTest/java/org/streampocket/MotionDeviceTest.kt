package org.streampocket

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

/** Interrupt expansion before it finishes: the latest tap must win without ghost content. */
class MotionDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()

    @Test fun interruptedSettingsExpansionSettlesAndRemainsInteractive() {
        ui.runOnUiThread {
            ui.activity.viewModelStore.clear()
            ui.activity.setContent {
                PocketTheme {
                    var enabled by remember { mutableStateOf(false) }
                    Column(Modifier.safeDrawingPadding()) {
                        SettingsGroup("动效测试","快速展开与收起") {
                            SettingSwitch("测试选项","可在展开后正常操作",enabled,{enabled=it})
                        }
                        Text(if(enabled) "已开启" else "已关闭")
                    }
                }
            }
        }
        ui.waitForIdle()
        ui.mainClock.autoAdvance=false
        try {
            ui.onNodeWithText("动效测试").performClick()
            ui.mainClock.advanceTimeBy(64)
            ui.onNodeWithText("动效测试").performClick()
            ui.mainClock.advanceTimeBy(400)
            ui.onNodeWithText("测试选项").assertDoesNotExist()
            ui.onNodeWithText("动效测试").performClick()
            ui.mainClock.advanceTimeBy(400)
            ui.onNodeWithText("测试选项").assertIsDisplayed().performClick()
            ui.mainClock.advanceTimeBy(400)
            ui.onNodeWithText("已开启").assertExists()
        } finally { ui.mainClock.autoAdvance=true }
    }
}
