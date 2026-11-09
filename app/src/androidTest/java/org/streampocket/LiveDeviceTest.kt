package org.streampocket

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

class LiveDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun liveEntrySearchGroupsSourceSwitchPlaybackAndRelease() {
        val server=MockWebServer().apply { start() }
        val base="http://live.test:${server.port}"
        val calls=CopyOnWriteArrayList<String>()
        val assets=InstrumentationRegistry.getInstrumentation().context.assets
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                val path=request.requestUrl!!.encodedPath;calls+=path
                return when(path) {
                    "/recommend" -> MockResponse().setBody("{\"subjects\":[]}")
                    "/one.m3u" -> MockResponse().setBody("#EXTM3U\n#EXTINF:-1 group-title=\"新闻\",新闻频道\n$base/test.m3u8\n#EXTINF:-1 group-title=\"体育\",体育频道\n$base/test.m3u8?channel=2\n")
                    "/two.m3u" -> MockResponse().setBody("#EXTM3U\n#EXTINF:-1,文化频道\n$base/test.m3u8?channel=3\n")
                    "/bad.m3u" -> MockResponse().setResponseCode(503)
                    "/slow.m3u" -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    else -> try { MockResponse().setBody(okio.Buffer().write(assets.open(path.removePrefix("/")).use { it.readBytes() })) } catch(_:Exception) { MockResponse().setResponseCode(404) }
                }
            }
        }
        val first=Source("电视直播","$base/one.m3u",SourceKind.LIVE)
        val second=Source("地方频道","$base/two.m3u",SourceKind.LIVE)
        val bad=Source("暂时不可用","$base/bad.m3u",SourceKind.LIVE)
        val slow=Source("加载中的来源","$base/slow.m3u",SourceKind.LIVE)
        val disabled=Source("停用来源","$base/disabled.m3u",SourceKind.LIVE)
        ui.runOnUiThread { ui.activity.viewModelStore.clear() }
        runBlocking { Storage(ui.activity.application).write(Saved(allowHttp=true,subscriptions=listOf(Subscription("live","IPTV","$base/sub",1,listOf(first,second,bad,slow,disabled))),disabled=setOf(disabled.key))) }
        lateinit var model:AppModel
        ui.runOnUiThread {
            model=ViewModelProvider(ui.activity,object:ViewModelProvider.Factory {
                override fun <T:ViewModel> create(modelClass:Class<T>):T {
                    @Suppress("UNCHECKED_CAST") return AppModel(ui.activity.application,object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) },{_,_,_->"$base/recommend"}) as T
                }
            })[AppModel::class.java]
            ThemePreference.write(ui.activity,if(InstrumentationRegistry.getArguments().getString("appearance")=="dark") ThemeMode.DARK else ThemeMode.LIGHT)
            ui.activity.setContent { PocketTheme { Pocket(model) } }
        }
        try {
            ui.waitUntil(10000) { !model.state.value.loading }
            ui.onNodeWithTag("liveTab").performClick()
            ui.waitUntil(10000) { !model.state.value.liveLoading && model.state.value.liveChannels.size==2 }
            ui.onNodeWithTag("liveChannel:新闻频道").assertIsDisplayed()
            captureUi(ui,"live-channels")
            ui.onNodeWithTag("searchInput").performTextInput("体育")
            ui.onNodeWithTag("searchInput").performImeAction()
            ui.onNodeWithTag("liveChannel:新闻频道").assertDoesNotExist()
            ui.onNodeWithTag("liveChannel:体育频道").assertIsDisplayed()
            assertFalse(model.state.value.searching)
            ui.onNodeWithContentDescription("清空频道搜索").performClick()
            ui.onNodeWithTag("liveGroup:新闻").performClick()
            ui.onNodeWithTag("liveChannel:体育频道").assertDoesNotExist()
            val frames=PlaybackMetrics.frames
            ui.onNodeWithTag("liveChannel:新闻频道").performClick()
            ui.waitUntil(30000) { PlaybackMetrics.frames>frames }
            assertEquals(SourceKind.LIVE,model.state.value.playback?.video?.source?.kind)
            ui.onNodeWithContentDescription("返回频道").performClick()
            ui.waitUntil(15000) { model.state.value.playback==null && PlaybackMetrics.created==PlaybackMetrics.released && PlaybackMetrics.samplers==0 && MediaCallOwner.active.get()==0 }
            ui.onNodeWithTag("liveChannel:新闻频道").assertIsDisplayed()
            assertEquals(1,calls.count { it=="/one.m3u" })
            ui.onNodeWithTag("liveSourcePicker").performClick()
            ui.onNodeWithTag("liveSource:${disabled.key}").assertDoesNotExist()
            captureUi(ui,"live-sources")
            ui.onNodeWithTag("liveSource:${second.key}").performClick()
            ui.waitUntil(10000) { !model.state.value.liveLoading && model.state.value.liveSourceKey==second.key }
            ui.onNodeWithTag("liveChannel:文化频道").assertIsDisplayed()
            ui.onNodeWithTag("liveRefresh").performClick()
            ui.waitUntil(10000) { !model.state.value.liveLoading && calls.count { it=="/two.m3u" }==2 }
            ui.onNodeWithTag("liveSourcePicker").performClick();ui.onNodeWithTag("liveSource:${bad.key}").performClick()
            ui.waitUntil(10000) { model.state.value.liveError!=null }
            ui.onNodeWithText("重试").assertIsDisplayed()
            assertFalse(calls.contains("/disabled.m3u"))
            ui.onNodeWithTag("liveSourcePicker").performClick();ui.onNodeWithTag("liveSource:${slow.key}").performClick()
            ui.waitUntil(5000) { calls.contains("/slow.m3u") && model.state.value.liveLoading }
            ui.onNodeWithTag("nav:1").performClick()
            ui.waitUntil(5000) { !model.state.value.liveLoading && model.activeSearchCalls()==0 }
        } finally { ui.runOnUiThread { model.stopPlayback();ui.activity.viewModelStore.clear() };server.shutdown() }
    }
}
