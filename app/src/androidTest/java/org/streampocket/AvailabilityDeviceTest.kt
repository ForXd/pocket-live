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
    private val playlistRequests=java.util.concurrent.atomic.AtomicInteger()
    @Before fun setup() {
        server=MockWebServer().apply { start() }
        val base="http://probe.test:${server.port}"
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                val url=request.requestUrl!!
                return when(url.encodedPath) {
                    "/recommend" -> MockResponse().setBody("{\"subjects\":[]}")
                    "/duplicate" -> MockResponse().setBody("""{"code":0,"msg":"rejected"}""")
                    "/bad" -> MockResponse().setResponseCode(503)
                    "/api" -> {
                        val id=url.queryParameter("ids")
                        if(id!=null) {
                            details+=id
                            if(id=="slow") MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                            else MockResponse().setBody("""{"list":[{"vod_play_from":"主线路","vod_play_url":"第1集${'$'}$base/${if(id=="1") "bad-media.mp4" else "stream/master.m3u8"}"}]}""")
                        } else if(url.queryParameter("wd")=="慢速") MockResponse().setBody("""{"list":[{"vod_id":"slow","vod_name":"慢速剧集"}]}""")
                        else MockResponse().setBody("""{"list":[${(1..5).joinToString(",") { """{"vod_id":"$it","vod_name":"星河剧集"}""" }}]}""")
                    }
                    "/bad-media.mp4" -> MockResponse().setBody("<html>error</html>")
                    "/stream/master.m3u8" -> { playlistRequests.incrementAndGet();MockResponse().setHeader("Content-Type","application/vnd.apple.mpegurl")
                        .setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000\nchild.m3u8\n") }
                    "/stream/child.m3u8" -> MockResponse().setHeader("Content-Type","application/vnd.apple.mpegurl")
                        .setBody(okio.Buffer().write(mediaFixtureBytes("test.m3u8")))
                    in (0..7).map { "/stream/segment0$it.ts" } -> MockResponse().setHeader("Content-Type","video/mp2t")
                        .setBody(okio.Buffer().write(mediaFixtureBytes(url.encodedPath.substringAfterLast('/'))))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        ui.runOnUiThread { ui.activity.viewModelStore.clear() }
        val sources=listOf(Source("失败片库","http://failure.test:${server.port}/bad"),Source("星河片库","$base/api"),Source("未验证片库","$base/unknown"))
        runBlocking { Storage(ui.activity.application).write(Saved(allowHttp=true,disabled=setOf(sources.last().key),subscriptions=listOf(Subscription("probe","资源测试","$base/sub",1,sources),Subscription("duplicate","重复订阅","$base/sub2",1,listOf(Source("重复片库","$base/duplicate")))))) }
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
    private fun expandWork(id:String) {
        ui.waitUntil(10000) { !model.state.value.searching && model.state.value.results.any { it.id==id } }
        ui.onNodeWithTag("searchVideo:$id").performScrollTo().performClick()
    }
    @After fun close() { ui.runOnUiThread { ui.activity.viewModelStore.clear() };server.shutdown() }
    @Test fun expandedWorkChecksEveryCandidateAndKeepsSourceHealthSeparate() {
        ui.onNodeWithTag("openSearch").performClick()
        ui.onNodeWithTag("searchInput").performTextInput("星河")
        ui.onNodeWithTag("searchInput").performImeAction()
        expandWork("1")
        ui.waitUntil(45000) { model.state.value.probes.values.count { it==ProbeStatus.READY }==4 && !model.state.value.probeRunning && !model.state.value.searching }
        assertEquals(setOf("1","2","3","4","5"),details.toSet())
        assertEquals(1,model.state.value.probes.values.count { it==ProbeStatus.FAILED })

        assertEquals(5,model.state.value.results.size)
        assertEquals(3,model.state.value.total)
        assertEquals(2,model.state.value.failures)
        val first=model.state.value.results.first { model.state.value.probes[it.probeKey()]==ProbeStatus.READY }.id
        ui.onNodeWithTag("probe:$first",useUnmergedTree=true).assertTextEquals("可播放")
        val family=VideoGroups.familyKey(model.state.value.results.first())
        val sourceList=hasScrollAction() and hasAnyAncestor(hasTestTag("searchGroup:$family"))
        ui.onNode(sourceList).performScrollToIndex(4)
        ui.onNodeWithTag("probe:1",useUnmergedTree=true).assertTextEquals("不可播放")
        captureUi(ui,"search-probes")
        if(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("uxReview")=="true") {
            val done=java.io.File(ui.activity.filesDir,"availability-review-done");done.delete()
            val deadline=android.os.SystemClock.elapsedRealtime()+180000
            while(!done.exists() && android.os.SystemClock.elapsedRealtime()<deadline) {
                ui.mainClock.advanceTimeBy(100);ui.waitForIdle();android.os.SystemClock.sleep(100)
            }
            return
        }

        val before=playlistRequests.get()
        assertTrue(before>0)
        ui.onNodeWithContentDescription("开始搜索").performClick()
        expandWork("1")
        ui.waitUntil(45000) { !model.state.value.searching && !model.state.value.probeRunning && model.state.value.probes.values.count { it==ProbeStatus.READY }==4 }
        assertEquals("Successful trials reuse verified media",before,playlistRequests.get())

        ui.onNodeWithTag("nav:1").performClick()
        ui.onNodeWithText("资源测试").performClick()
        ui.onNodeWithText("● 最近搜索成功",useUnmergedTree=true).assertIsDisplayed()
        ui.onNodeWithText("● 最近搜索失败",useUnmergedTree=true).assertIsDisplayed()
        ui.onNodeWithText("● 待验证",useUnmergedTree=true).assertIsDisplayed()
        assertTrue(ui.onNodeWithText("星河片库",useUnmergedTree=true).getUnclippedBoundsInRoot().top<ui.onNodeWithText("失败片库",useUnmergedTree=true).getUnclippedBoundsInRoot().top)
        captureUi(ui,"source-health")
    }
    @Test fun leavingSearchCancelsActiveMediaWorkAndCannotCommitLateResults() {
        ui.onNodeWithTag("openSearch").performClick()
        ui.onNodeWithTag("searchInput").performTextInput("慢速")
        ui.onNodeWithTag("searchInput").performImeAction()
        expandWork("slow")
        ui.waitUntil(10000) { details.contains("slow") && model.state.value.probeRunning }
        ui.onNodeWithTag("nav:1").performClick()
        ui.waitUntil(10000) { !model.state.value.probeRunning && model.activeProbeCalls()==0 }
        assertFalse(model.state.value.probes.values.contains(ProbeStatus.CHECKING))
        assertEquals("慢速",model.state.value.searchQuery)
        assertEquals(1,model.state.value.results.size)
        assertEquals(true,model.state.value.sourceAvailability[model.state.value.results.single().source.key])
    }
    @Test fun newQueryCancelsOldProbeAndKeepsOnlyNewStatuses() {
        ui.onNodeWithTag("openSearch").performClick()
        ui.onNodeWithTag("searchInput").performTextInput("慢速")
        ui.onNodeWithTag("searchInput").performImeAction()
        expandWork("slow")
        ui.waitUntil(10000) { details.contains("slow") && model.state.value.probeRunning }
        ui.onNodeWithTag("searchInput").performTextReplacement("星河")
        ui.onNodeWithTag("searchInput").performImeAction()
        expandWork("1")
        ui.waitUntil(45000) { !model.state.value.searching && !model.state.value.probeRunning && model.state.value.probes.values.count { it==ProbeStatus.READY }==4 }
        assertEquals("星河",model.state.value.searchQuery)
        assertTrue(model.state.value.probes.keys.none { it.endsWith(":slow") })

        ui.waitUntil(10000) { model.activeProbeCalls()==0 }
    }

}
