package org.streampocket

import androidx.activity.compose.setContent
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.*
import org.junit.Assert.*

class AppearanceDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun switchesImmediatelyAndPersistsThemeChoice() {
        lateinit var model:AppModel
        var observedDark=false
        ui.runOnUiThread {
            model=ViewModelProvider(ui.activity)[AppModel::class.java]
            ui.activity.setContent { PocketTheme {
                val dark=LocalPocketDark.current
                SideEffect { observedDark=dark }
                Pocket(model)
            } }
        }
        try {
            ui.waitUntil(10000) { !model.state.value.loading }
            ui.onNodeWithContentDescription("外观设置").performClick()
            ui.onNodeWithText("夜间模式").performClick()
            ui.waitUntil(5000) { observedDark }
            assertEquals(ThemeMode.DARK,ThemePreference.read(ui.activity))
            ui.onNodeWithText("日间模式").performClick()
            ui.waitUntil(5000) { !observedDark }
            assertEquals(ThemeMode.LIGHT,ThemePreference.read(ui.activity))
            captureUi(ui,"appearance-light")
            ui.onNodeWithText("完成").performClick()
            ui.onNodeWithContentDescription("外观设置").performClick()
            ui.onNodeWithText("日间模式").assertIsSelected()
            ui.onNodeWithText("跟随系统").performClick()
            ui.waitForIdle()
            assertEquals(ui.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK==android.content.res.Configuration.UI_MODE_NIGHT_YES,observedDark)
            ui.onNodeWithText("完成").performClick()
        } finally { ui.runOnUiThread { ui.activity.viewModelStore.clear() } }
    }
}
