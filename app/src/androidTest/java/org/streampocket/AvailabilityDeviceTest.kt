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
    private val pendingRequests=java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var firstMediaGate:java.util.concurrent.CountDownLatch?=null
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
                            if(id=="lifecycle") MockResponse().setBody("""{"list":[{"vod_play_from":"高清${'$'}${'$'}${'$'}备用","vod_play_url":"第1集${'$'}$base/stream/master.m3u8${'$'}${'$'}${'$'}第1集${'$'}$base/pending.m3u8"}]}""")
                            else if(id=="slow") MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                            else MockResponse().setBody("""{"list":[{"vod_play_from":"主线路","vod_play_url":"第1集${'$'}$base/${if(id=="1") "bad-media.mp4" else "stream/master.m3u8"}"}]}""")
                        } else if(url.queryParameter("wd")=="切换") {
                            val ids=listOf("slow","old-backup","new-first","new-second","new-backup")
                            MockResponse().setBody(ids.joinToString(",","{\"list\":[","]}") { candidate ->
                                val title=if(candidate.startsWith("new-")) "切换乙" else "切换甲"
                                """{"vod_id":"$candidate","vod_name":"$title"}"""
                            })
                        } else if(url.queryParameter("wd")=="生命周期") MockResponse().setBody("""{"list":[{"vod_id":"lifecycle","vod_name":"生命周期"}]}""")
                        else if(url.queryParameter("wd")=="慢速") MockResponse().setBody("""{"list":[{"vod_id":"slow","vod_name":"慢速剧集"}]}""")
                        else MockResponse().setBody("""{"list":[${(1..5).joinToString(",") { """{"vod_id":"$it","vod_name":"星河剧集"}""" }}]}""")
                    }
                    "/bad-media.mp4" -> {
                        firstMediaGate?.await(10,java.util.concurrent.TimeUnit.SECONDS)
                        MockResponse().setBody("<html>error</html>")
                    }
                    "/pending.m3u8" -> { pendingRequests.incrementAndGet();MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
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
    @After fun close() { firstMediaGate?.countDown();ui.runOnUiThread { ui.activity.viewModelStore.clear() };server.shutdown() }
    @Test fun twoVerifiedChoicesStayInPlaceAndUntriedSourcesOpenManually() {
        firstMediaGate=java.util.concurrent.CountDownLatch(1)
        ui.onNodeWithTag("openSearch").performClick()
        ui.onNodeWithTag("searchInput").performTextInput("星河")
        ui.onNodeWithTag("searchInput").performImeAction()
        expandWork("1")
        val sourceKey=model.state.value.results.first().source.key
        val secondRow=ui.onNodeWithTag("sourceChoice:$sourceKey:2").assertIsNotEnabled()
        val initialBounds=secondRow.getUnclippedBoundsInRoot()
        firstMediaGate?.countDown()
        ui.waitUntil(45000) { model.state.value.probes.values.count { it==ProbeStatus.READY }==2 && !model.state.value.probeRunning }
        assertEquals(setOf("1","2","3"),details.toSet())
        assertEquals(0,model.activeProbeCalls())
        assertFalse(model.state.value.probes.containsValue(ProbeStatus.CHECKING))
        assertEquals(1,model.state.value.probes.values.count { it==ProbeStatus.FAILED })

        assertEquals(5,model.state.value.results.size)
        assertEquals(3,model.state.value.total)
        assertEquals(2,model.state.value.failures)
        val first=model.state.value.results.first { model.state.value.probes[it.probeKey()]==ProbeStatus.READY }.id
        ui.onNodeWithTag("probe:$first",useUnmergedTree=true).assertTextEquals("可播放")
        assertEquals("Trial completion may enable a source without moving its target",
            initialBounds,secondRow.assertIsEnabled().getUnclippedBoundsInRoot())
        val failedRow=ui.onNodeWithTag("sourceChoice:$sourceKey:1").assertIsNotEnabled()
        assertTrue("Failed source stays in its original first row",failedRow.getUnclippedBoundsInRoot().top<initialBounds.top)
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

        val untried=model.state.value.results.single { it.id=="4" }
        assertFalse(untried.probeKey() in model.state.value.probes)
        val manual=ui.onNodeWithTag("sourceChoice:$sourceKey:4").performScrollTo().assertIsEnabled()
        ui.onNode(hasText("未试播") and hasAnyAncestor(hasTestTag("sourceChoice:$sourceKey:4")),useUnmergedTree=true).assertIsDisplayed()
        manual.performClick()
        ui.waitUntil(10000) { model.state.value.detail?.video?.id=="4" }
        assertNull("An untested source opens its details",model.state.value.playback)
        assertFalse("Browsing it cannot report a successful trial",untried.probeKey() in model.state.value.probeEpisodes)
        ui.onNodeWithContentDescription("关闭详情").performClick()
        val before=playlistRequests.get()
        assertTrue(before>0)
        ui.onNodeWithContentDescription("开始搜索").performClick()
        expandWork("1")
        ui.waitUntil(45000) { !model.state.value.searching && !model.state.value.probeRunning && model.state.value.probes.values.count { it==ProbeStatus.READY }==2 }
        assertEquals("Successful trials reuse verified media",before,playlistRequests.get())

        ui.onNodeWithTag("nav:1").performClick()
        ui.onNodeWithText("资源测试").performClick()
        ui.onNodeWithText("最近搜索成功",substring=true,useUnmergedTree=true).assertIsDisplayed()
        ui.onNodeWithText("最近搜索失败",substring=true,useUnmergedTree=true).assertIsDisplayed()
        ui.onNodeWithText("暂无搜索记录",substring=true,useUnmergedTree=true).assertIsDisplayed()
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
    @Test fun expandingAnotherWorkDropsItsPredecessorsRunningAndQueuedTrials() {
        ui.onNodeWithTag("openSearch").performClick()
        ui.onNodeWithTag("searchInput").performTextInput("切换")
        ui.onNodeWithTag("searchInput").performImeAction()
        ui.waitUntil(10000) { !model.state.value.searching && model.state.value.results.size==5 }
        val oldGroup=model.state.value.results.filter { it.title=="切换甲" }
        val nextGroup=model.state.value.results.filter { it.title=="切换乙" }
        val oldFirst=VideoGroups.searchSources(VideoGroups.workKey(oldGroup.first()),oldGroup).first()
        val nextFirst=VideoGroups.searchSources(VideoGroups.workKey(nextGroup.first()),nextGroup).first()
        assertEquals("The first fixture must stall until cancelled","slow",oldFirst.id)
        expandWork(oldFirst.id)
        ui.waitUntil(10000) { "slow" in details && model.state.value.probeRunning }
        ui.onNodeWithTag("searchResults").performScrollToNode(hasTestTag("searchVideo:${nextFirst.id}"))
        ui.onNodeWithTag("searchVideo:${nextFirst.id}").performClick()
        ui.waitUntil(30000) { nextGroup.count { model.state.value.probes[it.probeKey()]==ProbeStatus.READY }==2 &&
            !model.state.value.probeRunning && model.activeProbeCalls()==0 }
        assertTrue(oldGroup.none { it.probeKey() in model.state.value.probes })
        assertFalse("Queued work from the abandoned card must stay unrequested","old-backup" in details)
        assertEquals(2,details.distinct().count { it.startsWith("new-") })
    }
    @Test fun searchTrialsReleaseOnPauseAndPreviewThenDiscardWhenSearchCloses() {
        ui.onNodeWithTag("openSearch").performClick()
        ui.onNodeWithTag("searchInput").performTextInput("生命周期")
        ui.onNodeWithTag("searchInput").performImeAction()
        expandWork("lifecycle")
        ui.waitUntil(15000) { pendingRequests.get()>0 && model.state.value.probeEpisodes.isNotEmpty() }
        val verified=model.state.value.probeEpisodes
        val before=playlistRequests.get()
        val firstPending=pendingRequests.get()
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        ui.waitUntil(5000) { model.activeProbeCalls()==0 && MediaCallOwner.active.get()==0 }
        assertFalse(model.state.value.probeRunning)
        assertEquals(verified,model.state.value.probeEpisodes)
        ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        ui.waitUntil(10000) { pendingRequests.get()>firstPending }
        val resumedPending=pendingRequests.get()
        ui.onNodeWithTag("searchPoster:lifecycle").performScrollTo().performClick()
        ui.onNodeWithContentDescription("关闭详情").assertIsDisplayed()
        ui.waitUntil(5000) { model.activeProbeCalls()==0 && MediaCallOwner.active.get()==0 }
        assertEquals(verified,model.state.value.probeEpisodes)
        ui.onNodeWithContentDescription("关闭详情").performClick()
        ui.waitUntil(10000) { pendingRequests.get()>resumedPending }
        assertEquals("Resuming must not decode the known-good line again",before,playlistRequests.get())
        ui.onNodeWithContentDescription("返回推荐").performClick()
        ui.waitUntil(5000) { model.activeProbeCalls()==0 && MediaCallOwner.active.get()==0 }
        val closed=model.state.value
        assertTrue(closed.results.isEmpty() && closed.searchQuery.isEmpty())
        assertTrue(closed.probeEpisodes.isEmpty() && closed.lineTrials.isEmpty() && closed.probes.isEmpty())
        assertTrue(closed.probeLines.isEmpty() && closed.probeResolutions.isEmpty())
        assertNull(closed.probeWork)
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
        ui.waitUntil(45000) { !model.state.value.searching && !model.state.value.probeRunning && model.state.value.probes.values.count { it==ProbeStatus.READY }==2 }
        assertEquals("星河",model.state.value.searchQuery)
        assertTrue(model.state.value.probes.keys.none { it.endsWith(":slow") })

        ui.waitUntil(10000) { model.activeProbeCalls()==0 }
    }

}
