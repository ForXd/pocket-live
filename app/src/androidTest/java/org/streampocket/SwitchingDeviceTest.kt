package org.streampocket

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Dns
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import java.net.InetAddress

class SwitchingDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Before fun resetSaved() { kotlinx.coroutines.runBlocking { Storage(ui.activity.application).write(Saved()) } }
    @Test fun failureShowsQuickSwitchAndPlaysAlternate() {
        val server=MockWebServer();server.start()
        val base="http://fixture.test:${server.port}"
        val assets=InstrumentationRegistry.getInstrumentation().context.assets
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse = when(request.requestUrl!!.encodedPath) {
                "/sub" -> MockResponse().setBody("""{"name":"切换测试","sources":[{"name":"测试来源","url":"$base/api"}]}""")
                "/api" -> if(request.requestUrl!!.queryParameter("ids")==null) MockResponse().setBody("""{"list":[{"vod_id":"1","vod_name":"切换测试剧"}]}""") else
                    MockResponse().setBody("""{"list":[{"vod_play_from":"失效线路${'$'}${'$'}${'$'}备用线路","vod_play_url":"第1集${'$'}$base/bad.mp4${'$'}${'$'}${'$'}第1集${'$'}$base/test.mp4"}]}""")
                "/test.mp4" -> MockResponse().setBody(Buffer().write(assets.open("test.mp4").use { it.readBytes() })).setHeader("Content-Type","video/mp4")
                else -> MockResponse().setResponseCode(404)
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
            ui.waitUntil(15000) { !model.state.value.loading }
            ui.runOnIdle { model.allowHttp(true) }
            ui.waitUntil(10000) { model.state.value.saved.allowHttp }
            ui.runOnIdle { model.importSubscription("$base/sub") }
            ui.waitUntil(15000) { model.state.value.saved.subscriptions.isNotEmpty() }
            ui.runOnIdle { model.search("切换测试剧") }
            ui.waitUntil(15000) { !model.state.value.searching && model.state.value.results.isNotEmpty() }
            ui.runOnIdle { model.open(model.state.value.results.first()) }
            ui.waitUntil(15000) { model.state.value.detail!=null }
            ui.runOnIdle { val d=model.state.value.detail!!;model.play(d.video,d.lines.first().episodes.first()) }
            ui.waitUntil(20000) { ui.onAllNodes(hasText("播放失败",substring=true)).fetchSemanticsNodes().isNotEmpty() }
            val instrument=InstrumentationRegistry.getInstrumentation()
            fun capture(name:String) {
                val bitmap=instrument.uiAutomation.takeScreenshot()
                try { java.io.File(instrument.targetContext.getExternalFilesDir(null),name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
                finally { bitmap.recycle() }
            }
            capture("failure.png")
            ui.onNodeWithText("快速换源").assertExists().performClick()
            ui.onNodeWithText("备用线路 · 第1集").assertExists()
            capture("quick-switch.png")
            val failedSession=model.state.value.playback!!.sessionId
            instrument.uiAutomation.executeShellCommand("input keyevent 4").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
            ui.waitUntil(5000) { model.state.value.alternativeSession==null }
            assertEquals(failedSession,model.state.value.playback!!.sessionId)
            ui.onNodeWithText("快速换源").performClick()
            ui.onNodeWithText("备用线路 · 第1集").assertExists().performClick()
            val before=PlaybackMetrics.frames
            ui.waitUntil(30000) { model.state.value.playback?.episode?.url=="$base/test.mp4" && PlaybackMetrics.frames>before }
            ui.runOnIdle { model.stopPlayback() }
            ui.waitUntil(10000) { PlaybackMetrics.created==PlaybackMetrics.released }
        } finally { ui.runOnUiThread { ui.activity.viewModelStore.clear() };server.shutdown() }
    }
    @Test fun stalledSourceOffersRecoveryBeforeTerminalFailure() {
        val server=MockWebServer();server.start()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val base="http://fixture.test:${server.port}"
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
            ui.waitUntil(15000) { !model.state.value.loading }
            ui.runOnIdle { model.allowHttp(true) }
            ui.waitUntil(10000) { model.state.value.saved.allowHttp }
            ui.runOnIdle { model.play(Video(Source("慢速来源","$base/api"),"slow","缓冲测试"),Episode("第1集","$base/slow.mp4")) }
            ui.waitUntil(15000) { ui.onAllNodes(hasText("缓冲较慢",substring=true)).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithContentDescription("更多播放选项").performClick()
            ui.onNodeWithText("流畅优先").assertExists()
            ui.onNodeWithText("关闭").performClick()
            ui.onNodeWithText("快速换源").assertExists()
            ui.runOnIdle { model.stopPlayback() }
            ui.waitUntil(5000) { PlaybackMetrics.created==PlaybackMetrics.released && PlaybackMetrics.samplers==0 }
        } finally { ui.runOnUiThread { ui.activity.viewModelStore.clear() };server.shutdown() }
    }
}
