package org.streampocket

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger

class AppUpdateDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun updateChecksAreSingleFlightThrottledAndManualRetryWorks() {
        val calls=AtomicInteger()
        lateinit var model:AppModel
        ui.runOnUiThread {
            ui.activity.viewModelStore.clear()
            model=ViewModelProvider(ui.activity,object:ViewModelProvider.Factory {
                override fun <T:ViewModel> create(clazz:Class<T>):T {
                    @Suppress("UNCHECKED_CAST")
                    return AppModel(ui.activity.application,updateDocument={
                        val call=calls.incrementAndGet();kotlinx.coroutines.delay(100)
                        if(call==2) throw java.io.IOException("fixture offline")
                        """{"draft":false,"prerelease":false,"tag_name":"v0.6.0","html_url":"https://github.com/ForXd/pocket-live/releases/tag/v0.6.0","assets":[{"name":"pocket-live-0.6.0-release.apk","state":"uploaded","size":1234,"browser_download_url":"https://github.com/ForXd/pocket-live/releases/download/v0.6.0/pocket-live-0.6.0-release.apk"}]}"""
                    }) as T
                }
            })[AppModel::class.java]
            ui.activity.setContent { PocketTheme { Pocket(model) } }
        }
        try {
            ui.waitUntil(10000) { !model.state.value.loading }
            ui.runOnIdle { repeat(5) { model.checkUpdates() } }
            ui.waitUntil(5000) { model.state.value.appRelease!=null }
            assertEquals(1,calls.get())
            ui.onNodeWithText("有新版").performClick()
            ui.onNodeWithText("下载更新").assertExists()
            assertEquals(1,calls.get())
            val instrument=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val download=java.util.concurrent.atomic.AtomicReference<String>()
            val monitor=object:android.app.Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent:android.content.Intent):android.app.Instrumentation.ActivityResult? {
                    if(intent.action!=android.content.Intent.ACTION_VIEW) return null
                    download.set(intent.dataString)
                    return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK,null)
                }
            }
            instrument.addMonitor(monitor)
            try {
                ui.onNodeWithText("下载更新").performClick()
                assertEquals("https://github.com/ForXd/pocket-live/releases/download/v0.6.0/pocket-live-0.6.0-release.apk",download.get())
            } finally { instrument.removeMonitor(monitor) }
            ui.onNodeWithText("重新检查").performClick()
            ui.waitUntil(5000) { model.state.value.updateError!=null }
            assertFalse(model.state.value.updateChecking)
            ui.onNodeWithText("重新检查").performClick()
            ui.waitUntil(5000) { calls.get()==3 && !model.state.value.updateChecking }
            assertNull(model.state.value.updateError)
            ui.onNodeWithText("关闭").performClick()
            ui.onNodeWithTag("searchInput").assertExists()
        } finally { ui.runOnUiThread { ui.activity.viewModelStore.clear() } }
    }
}
