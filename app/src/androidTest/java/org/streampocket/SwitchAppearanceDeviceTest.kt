// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SwitchAppearanceDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun searchHistoryUsesNeutralColorsInDayMode() {
        verifySwitch(ThemeMode.LIGHT, Color(0xff171717), Color(0xfff2f2f2), Color(0xff808080))
    }

    @Test fun searchHistoryUsesNeutralColorsInNightMode() {
        verifySwitch(ThemeMode.DARK, Color.White, Color(0xff313131), Color(0xff8b8b8b))
    }

    private fun verifySwitch(mode: ThemeMode, active: Color, inactive: Color, outline: Color) {
        ui.runOnUiThread {
            ui.activity.setContent {
                PocketTheme(mode) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        var enabled by remember { mutableStateOf(true) }
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Surface { SettingSwitch("搜索历史", "在本机保留搜索词", enabled) { enabled = it } }
                        }
                    }
                }
            }
        }
        val row = ui.onNodeWithText("搜索历史")
        fun assertCenters(left: Color, right: Color) {
            ui.waitForIdle()
            val image = row.captureToImage().toPixelMap()
            // Sample the two thumb positions on the 52dp track, away from antialiased edges.
            fun center(fromEnd: Int) = image[image.width - with(ui.density) { fromEnd.dp.roundToPx() }, image.height / 2]
            val start = center(36)
            val end = center(16)
            assertNeutralPixel("Start of the track", left, start)
            assertNeutralPixel("End of the track", right, end)
            val lighter = maxOf(start.luminance(), end.luminance())
            val darker = minOf(start.luminance(), end.luminance())
            assertTrue("Thumb and track should have 3:1 contrast", (lighter + .05f) / (darker + .05f) >= 3f)
        }
        row.assertIsOn()
        assertCenters(active, inactive)
        row.performClick().assertIsOff()
        ui.mainClock.advanceTimeBy(750L)
        assertCenters(outline, inactive)
    }

    private fun assertNeutralPixel(label: String, expected: Color, actual: Color) {
        // Permit small compositing differences in brightness while checking hue independently.
        val brightest = maxOf(actual.red, actual.green, actual.blue)
        val darkest = minOf(actual.red, actual.green, actual.blue)
        val message = "$label: expected %08x, sampled %08x".format(expected.toArgb(), actual.toArgb())
        assertTrue(message, brightest - darkest <= 1.1f / 255f)
        assertEquals(message, expected.red, actual.red, 3.1f / 255f)
        assertEquals(message, 1f, actual.alpha, 0f)
    }
}
