package org.streampocket

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.net.InetAddress

class UiLayoutDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun primaryActionsAndSettingsRemainReachable() {
        val server=MockWebServer();server.start()
        val base="http://fixture.test:${server.port}"
        val source=Source("示例来源","$base/api")
        val video=Video(source,"1","星河漫游", "更新至第12集",poster="$base/poster.png")
        val history=History(Playback(video,Episode("第3集","$base/video.mp4"),72000),1)
        ui.runOnUiThread { ui.activity.viewModelStore.clear() }
        runBlocking { Storage(ui.activity.application).write(Saved(subscriptions=listOf(Subscription("fixture","我的订阅","$base/sub",System.currentTimeMillis(),listOf(source))),history=listOf(history),allowHttp=true)) }
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse = if(request.path=="/poster.png") MockResponse().setHeader("Content-Type","image/jpeg").setBody(okio.Buffer().write(InstrumentationRegistry.getInstrumentation().context.assets.open("editorial-poster.jpg").use { it.readBytes() }))
            else if(request.path!!.startsWith("/recommend")) MockResponse().setBody("""{"subjects":[{"id":"1","title":"星河漫游","rate":"8.5","cover":"$base/poster.png"},{"id":"2","title":"山海之间","rate":"8.2","cover":"$base/poster.png"}]}""")
            else if(request.requestUrl!!.queryParameter("ids")!=null) MockResponse().setBody("""{"list":[{"vod_content":"一次穿越群星的旅程。每个故事，都值得被认真观看。","vod_play_from":"高清线路","vod_play_url":"第1集${'$'}$base/video.mp4#第2集${'$'}$base/video2.mp4#第3集${'$'}$base/video3.mp4#第4集${'$'}$base/video4.mp4#第5集${'$'}$base/video5.mp4#第6集${'$'}$base/video6.mp4"}]}""")
            else MockResponse().setBody("""{"list":[{"vod_id":"1","vod_name":"星河漫游","vod_remarks":"更新至第12集","vod_pic":"$base/poster.png"},{"vod_id":"2","vod_name":"山海之间","vod_remarks":"高清","vod_pic":"$base/poster.png"}]}""")
        }
        lateinit var model:AppModel
        ui.runOnUiThread {
            ui.activity.viewModelStore.clear()
            model=ViewModelProvider(ui.activity,object:ViewModelProvider.Factory {
                override fun <T:ViewModel> create(modelClass:Class<T>):T {
                    @Suppress("UNCHECKED_CAST")
                    return AppModel(ui.activity.application,object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) },{_,_,_->"$base/recommend"}) as T
                }
            })[AppModel::class.java]
            ThemePreference.write(ui.activity,if(InstrumentationRegistry.getArguments().getString("appearance")=="dark") ThemeMode.DARK else ThemeMode.LIGHT)
            ui.activity.setContent { PocketTheme { Pocket(model) } }
        }
        val instrument=InstrumentationRegistry.getInstrumentation()
        fun capture(name:String) {
            ui.waitForIdle()
            android.os.SystemClock.sleep(450) // Let platform IME/inset and navigation animations settle before pixels.
            ui.waitForIdle()
            instrument.waitForIdleSync()
            val dir=java.io.File(instrument.targetContext.getExternalFilesDir(null),"ui-v0100-${InstrumentationRegistry.getArguments().getString("appearance","light")}").apply { mkdirs() }
            instrument.uiAutomation.takeScreenshot()?.let { b -> try { java.io.File(dir,"$name.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } } finally { b.recycle() } }
        }
        try {
            ui.waitUntil(10000) { !model.state.value.loading }
            assertEquals("History artwork survives persistence",video.poster,model.state.value.saved.history.single().playback.video.poster)
            if(ui.activity.resources.configuration.screenWidthDp>=600 && ui.activity.resources.configuration.screenHeightDp>=480) ui.onNodeWithTag("navigationRail").assertIsDisplayed()
            else ui.onNodeWithTag("navigationRail").assertDoesNotExist()
            ui.onNodeWithTag("searchInput").performTextInput("星河")
            capture("00-keyboard")
            ui.onNodeWithContentDescription("开始搜索").assertHeightIsAtLeast(48.dp).performClick()
            ui.waitUntil(10000) { !model.state.value.searching && model.state.value.results.size==2 }
            ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("星河漫游 封面").fetchSemanticsNodes().isNotEmpty() }
            capture("01-search")
            ui.onNodeWithText("星河漫游").performClick()
            ui.waitUntil(10000) { model.state.value.detail!=null }
            capture("06-detail")
            ui.onNodeWithContentDescription("返回",useUnmergedTree=true).performClick()
            ui.waitUntil(5000) { model.state.value.detail==null }
            ui.onNodeWithText("订阅",useUnmergedTree=true).performClick()
            capture("02-subscriptions")
            ui.onNodeWithTag("subscriptionsList").performScrollToNode(hasText("播放与网络"))
            ui.onNodeWithText("播放与网络").performClick()
            ui.onNodeWithTag("subscriptionsList").performScrollToNode(hasText("允许未加密 HTTP"))
            ui.onNodeWithText("允许未加密 HTTP").assertIsDisplayed()
            capture("03-settings")
            ui.onNodeWithTag("subscriptionsList").performScrollToNode(hasText("自动 HLS 广告过滤"))
            ui.onNodeWithText("自动 HLS 广告过滤").performClick()
            ui.waitUntil(5000) { model.state.value.saved.adFilter }
            ui.onNodeWithText("自动 HLS 广告过滤").performClick()
            ui.waitUntil(5000) { !model.state.value.saved.adFilter }
            ui.onNodeWithTag("subscriptionsList").performScrollToNode(hasText("存储与缓存"))
            ui.onNodeWithText("存储与缓存").performClick()
            ui.onNodeWithTag("subscriptionsList").performScrollToNode(hasText("清缓存（保留配置）"))
            ui.onNodeWithText("清缓存（保留配置）").assertHeightIsAtLeast(48.dp)
            ui.onNodeWithText("历史",useUnmergedTree=true).performClick()
            ui.onNodeWithTag("historyGroups").performScrollToNode(hasTestTag("continue:${HistoryGroups.key(video).stableKey}"))
            ui.onNodeWithTag("continue:${HistoryGroups.key(video).stableKey}").assertHeightIsAtLeast(48.dp)
            ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("星河漫游 封面").fetchSemanticsNodes().isNotEmpty() }
            capture("04-history")
            ui.onNodeWithText("发现",useUnmergedTree=true).performClick()
            ui.waitUntil(10000) { !model.state.value.recommendLoading && model.state.value.recommendations.size==2 }
            ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("星河漫游 封面").fetchSemanticsNodes().isNotEmpty() }
            capture("05-discovery")
            ui.onNodeWithContentDescription("刷新推荐").assertHeightIsAtLeast(48.dp).performClick()
            ui.waitUntil(10000) { !model.state.value.recommendLoading }
        } finally { ui.runOnUiThread { ui.activity.viewModelStore.clear() };server.shutdown() }
    }
}
