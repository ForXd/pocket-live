package org.streampocket

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList

class AvailabilityDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var server:MockWebServer
    private lateinit var model:AppModel
    private val details=CopyOnWriteArrayList<String>()
    @Before fun setup() {
        server=MockWebServer().apply { start() }
        val base="http://probe.test:${server.port}"
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                val url=request.requestUrl!!
                return when(url.encodedPath) {
                    "/recommend" -> MockResponse().setBody("{\"subjects\":[]}")
                    "/bad" -> MockResponse().setResponseCode(503)
                    "/api" -> {
                        val id=url.queryParameter("ids")
                        if(id!=null) {
                            details+=id
                            if(id=="slow") MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                            else MockResponse().setBody("""{"list":[{"vod_play_from":"主线路","vod_play_url":"第1集${'$'}$base/${if(id=="1") "bad-media" else "redirect"}"}]}""")
                        } else if(url.queryParameter("wd")=="慢速") MockResponse().setBody("""{"list":[{"vod_id":"slow","vod_name":"慢速剧集"}]}""")
                        else MockResponse().setBody("""{"list":[${(1..5).joinToString(",") { """{"vod_id":"$it","vod_name":"星河剧集 $it"}""" }}]}""")
                    }
                    "/bad-media" -> MockResponse().setBody("<html>error</html>")
                    "/redirect" -> MockResponse().setResponseCode(302).setHeader("Location","/stream/master.m3u8")
                    "/stream/master.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=10000\nchild.m3u8\n")
                    "/stream/child.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXTINF:4,\nsegment.ts\n#EXT-X-ENDLIST\n")
                    "/stream/segment.ts" -> { val b=ByteArray(564);b[0]=0x47;b[188]=0x47;b[376]=0x47;MockResponse().setBody(okio.Buffer().write(b)) }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        ui.runOnUiThread { ui.activity.viewModelStore.clear() }
        val sources=listOf(Source("失败片库","$base/bad"),Source("星河片库","$base/api"),Source("未验证片库","$base/unknown"))
        runBlocking { Storage(ui.activity.application).write(Saved(allowHttp=true,disabled=setOf(sources.last().key),subscriptions=listOf(Subscription("probe","资源测试","$base/sub",1,sources)))) }
        ui.runOnUiThread {
            model=ViewModelProvider(ui.activity,object:ViewModelProvider.Factory {
                override fun <T:ViewModel> create(modelClass:Class<T>):T {
                    @Suppress("UNCHECKED_CAST") return AppModel(ui.activity.application,object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) },{_,_,_->"$base/recommend"}) as T
                }
            })[AppModel::class.java]
            ui.activity.setContent { PocketTheme { Pocket(model) } }
        }
        ui.waitUntil(10000) { !model.state.value.loading }
    }
    @After fun close() { ui.runOnUiThread { ui.activity.viewModelStore.clear() };server.shutdown() }
    @Test fun probesTwoMediaResultsThenStopsAndShowsHealthWithoutPlayers() {
        val created=PlaybackMetrics.created
        ui.onNodeWithTag("searchInput").performTextInput("星河")
        ui.onNodeWithTag("searchInput").performImeAction()
        ui.waitUntil(15000) { model.state.value.probes.values.count { it==ProbeStatus.READY }==2 && !model.state.value.probeRunning && !model.state.value.searching }
        assertEquals(listOf("1","2","3"),details.toList())
        assertEquals(listOf("2","3"),model.state.value.results.take(2).map { it.id })
        assertEquals(5,model.state.value.results.size)
        assertEquals(created,PlaybackMetrics.created)
        ui.onNodeWithTag("probe:2",useUnmergedTree=true).assertTextEquals("媒体可用")
        captureUi(ui,"search-probes")
        if(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("uxReview")=="true") {
            val done=java.io.File(ui.activity.filesDir,"availability-review-done");done.delete()
            val deadline=android.os.SystemClock.elapsedRealtime()+180000
            while(!done.exists() && android.os.SystemClock.elapsedRealtime()<deadline) {
                ui.mainClock.advanceTimeBy(100);ui.waitForIdle();android.os.SystemClock.sleep(100)
            }
            return
        }

        ui.onNodeWithContentDescription("开始搜索").performClick()
        ui.waitUntil(10000) { !model.state.value.searching && !model.state.value.probeRunning && model.state.value.probes.values.count { it==ProbeStatus.READY }==2 }
        assertEquals("Repeated query reuses bounded probe cache",listOf("1","2","3"),details.toList())

        ui.onNodeWithTag("nav:1").performClick()
        ui.onNodeWithText("资源测试").performClick()
        ui.onNodeWithText("● 最近搜索成功",useUnmergedTree=true).assertIsDisplayed()
        ui.onNodeWithText("● 最近搜索失败",useUnmergedTree=true).assertIsDisplayed()
        ui.onNodeWithText("● 待验证",useUnmergedTree=true).assertIsDisplayed()
        assertTrue(ui.onNodeWithText("星河片库",useUnmergedTree=true).getUnclippedBoundsInRoot().top<ui.onNodeWithText("失败片库",useUnmergedTree=true).getUnclippedBoundsInRoot().top)
        captureUi(ui,"source-health")
    }
    @Test fun leavingSearchCancelsActiveMediaWorkAndCannotCommitLateResults() {
        ui.onNodeWithTag("searchInput").performTextInput("慢速")
        ui.onNodeWithTag("searchInput").performImeAction()
        ui.waitUntil(10000) { details.contains("slow") && model.state.value.probeRunning }
        ui.onNodeWithTag("nav:1").performClick()
        ui.waitUntil(10000) { !model.state.value.probeRunning && model.activeProbeCalls()==0 }
        assertFalse(model.state.value.probes.values.contains(ProbeStatus.CHECKING))
        assertEquals("慢速",model.state.value.searchQuery)
        assertEquals(1,model.state.value.results.size)
        assertEquals(true,model.state.value.sourceAvailability[model.state.value.results.single().source.key])
    }
    @Test fun newQueryCancelsOldProbeAndKeepsOnlyNewStatuses() {
        ui.onNodeWithTag("searchInput").performTextInput("慢速")
        ui.onNodeWithTag("searchInput").performImeAction()
        ui.waitUntil(10000) { details.contains("slow") && model.state.value.probeRunning }
        ui.onNodeWithTag("searchInput").performTextReplacement("星河")
        ui.onNodeWithTag("searchInput").performImeAction()
        ui.waitUntil(15000) { !model.state.value.searching && !model.state.value.probeRunning && model.state.value.probes.values.count { it==ProbeStatus.READY }==2 }
        assertEquals("星河",model.state.value.searchQuery)
        assertTrue(model.state.value.probes.keys.none { it.endsWith(":slow") })
        assertEquals(listOf("2","3"),model.state.value.results.take(2).map { it.id })
        ui.waitUntil(10000) { model.activeProbeCalls()==0 }
    }

}
