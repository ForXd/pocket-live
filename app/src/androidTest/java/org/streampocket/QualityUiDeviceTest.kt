package org.streampocket

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Dns
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class QualityUiDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun labelsStableQualityOrderAndScreens() {
        androidx.test.espresso.IdlingPolicies.setMasterPolicyTimeout(120,TimeUnit.SECONDS)
        val server=MockWebServer();server.start()
        val base="http://quality.test:${server.port}"
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                val path=request.requestUrl!!.encodedPath
                return when(path) {
                    "/sub" -> MockResponse().setBody("""{"name":"公开演示片源","sources":[{"name":"山野纪录","url":"$base/720"},{"name":"城市光影","url":"$base/1080"},{"name":"开放影像","url":"$base/unknown"}]}""")
                    "/720" -> MockResponse().setBody("""{"list":[{"vod_id":"720","vod_name":"山野之间","vod_remarks":"720p"}]}""")
                    "/1080" -> MockResponse().setBody("""{"list":[{"vod_id":"1080","vod_name":"城市的清晨","vod_width":1920,"vod_height":1080}]}""")
                    else -> MockResponse().setBody("""{"list":[{"vod_id":"unknown","vod_name":"4K 是片名，不是清晰度","vod_remarks":"更新至第3集"}]}""")
                }
            }
        }
        lateinit var model:AppModel
        ui.runOnUiThread {
            ui.activity.viewModelStore.clear()
            model=ViewModelProvider(ui.activity,object:ViewModelProvider.Factory {
                override fun <T:ViewModel> create(clazz:Class<T>):T {
                    @Suppress("UNCHECKED_CAST")
                    return AppModel(ui.activity.application,object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) }) as T
                }
            })[AppModel::class.java]
            ui.activity.setContent { PocketTheme { Pocket(model) } }
        }
        try {
            ui.waitUntil(60000) { !model.state.value.loading }
            capture("search-empty")
            ui.runOnIdle { model.allowHttp(true) }
            ui.waitUntil(60000) { model.state.value.saved.allowHttp }
            ui.onNodeWithText("订阅").performClick()
            capture("subscriptions-empty")
            ui.onNodeWithTag("subscriptionInput").performTextReplacement("$base/sub")
            ui.onNodeWithText("添加订阅").performScrollTo().performClick()
            ui.waitUntil(60000) { model.state.value.saved.subscriptions.size==1 }
            capture("subscriptions")
            ui.onNodeWithText("搜索",useUnmergedTree=true).performClick()
            ui.onNodeWithTag("searchInput").performTextReplacement("演示")
            ui.onNodeWithText("开始搜索").performClick()
            ui.waitUntil(60000) { !model.state.value.searching && model.state.value.results.size==3 }
            assertEquals(listOf("1080","720","unknown"),model.state.value.results.map { it.id })
            ui.onNodeWithText("1920×1080 · 接口自报").assertIsDisplayed()
            ui.onNodeWithText("720p · 源站自报").assertIsDisplayed()
            capture("quality-results")
            ui.onNodeWithText("清晰度未知").performScrollTo().assertIsDisplayed()
            capture("quality-unknown")
            ui.onNodeWithText("历史").performClick()
            capture("history-empty")
        } finally { server.shutdown() }
    }
    private fun capture(name:String) {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val dir=java.io.File(instrument.targetContext.getExternalFilesDir(null),"ui-quality").apply { mkdirs() }
        instrument.uiAutomation.takeScreenshot()?.let { b ->
            try { java.io.File(dir,"$name.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } } finally { b.recycle() }
        }
    }
}
