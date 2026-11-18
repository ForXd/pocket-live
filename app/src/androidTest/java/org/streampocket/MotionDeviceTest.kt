package org.streampocket

import androidx.activity.compose.setContent
import androidx.activity.BackEventCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import org.junit.Assert.assertTrue
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

    @Test fun forwardIsImmediateAndBackRevealsTheParentFromEitherEdge() {
        var detail by mutableStateOf(false)
        ui.runOnUiThread {
            ui.activity.setContent {
                CardRouteHost(if(detail) listOf("home","detail") else listOf("home")) {
                    CardBackHandler(enabled=detail) { detail=false }
                    Box(Modifier.fillMaxSize().testTag("route").background(if(detail) Color.Blue else Color.Red)
                        .clickable { detail=true })
                }
            }
        }
        fun colorAt(fraction: Float): Color {
            val pixels=ui.onRoot().captureToImage().toPixelMap()
            return pixels[(pixels.width*fraction).toInt(),pixels.height/2]
        }
        assertTrue(colorAt(.1f).red>.8f)
        ui.onNodeWithTag("route").performClick()
        assertTrue("Forward route fills both edges",colorAt(.05f).blue>.8f && colorAt(.95f).blue>.8f)
        val dispatcher=ui.activity.onBackPressedDispatcher
        fun drag(edge:Int) {
            ui.runOnIdle {
                val width=ui.activity.window.decorView.width.toFloat()
                dispatcher.dispatchOnBackStarted(BackEventCompat(if(edge==BackEventCompat.EDGE_LEFT) 0f else width,0f,0f,edge))
                dispatcher.dispatchOnBackProgressed(BackEventCompat(width*.5f,0f,.5f,edge))
            }
        }
        drag(BackEventCompat.EDGE_LEFT)
        assertTrue("Parent visible behind left swipe",colorAt(.2f).red>.7f && colorAt(.8f).blue>.8f)
        ui.runOnIdle { dispatcher.dispatchOnBackCancelled() }
        assertTrue("Cancel restores detail",colorAt(.05f).blue>.8f && detail)
        drag(BackEventCompat.EDGE_RIGHT)
        assertTrue("Right swipe reveals opposite edge",colorAt(.8f).red>.7f && colorAt(.2f).blue>.8f)
        ui.runOnIdle { dispatcher.onBackPressed() }
        ui.waitUntil(5000) { !detail }
        assertTrue(colorAt(.5f).red>.8f)
    }

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
