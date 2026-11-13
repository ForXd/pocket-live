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
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import java.net.InetAddress

class ProbePlaybackHandoffDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun extensionlessHlsThatPassesProbeActuallyRenders() = scenario(false)
    @Test fun expandedSearchKeepsSuccessfulSecondLineAndRedirectedHls() = scenario(true)
    @Test fun closingDuringFormatDetectionCancelsNetworkAndPlayer() {
        val server=MockWebServer().apply { start();enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
        val network=Network({true},dns=object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) })
        lateinit var controller:PlaybackController
        val before=PlaybackMetrics.created-PlaybackMetrics.released
        try {
            ui.runOnUiThread {
                controller=PlaybackController(ui.activity.application,network,
                    Playback(Video(Source("测试","https://fixture.test/api"),"1","测试"),Episode("正片","http://handoff.test:${server.port}/unknown")),
                    {_,_->},{},{},{},{})
                controller.start()
            }
            assertNotNull(server.takeRequest(5,java.util.concurrent.TimeUnit.SECONDS))
            ui.runOnUiThread { controller.close(false) }
            ui.waitUntil(10000) { network.client.dispatcher.runningCallsCount()==0 && PlaybackMetrics.created-PlaybackMetrics.released==before }
            assertEquals(0,MediaCallOwner.active.get())
        } finally { ui.runOnUiThread { controller.close(false) };network.close();server.shutdown() }
    }
    @Test fun refreshedProviderTokenDropsOldReadinessAndKeepsWorkingLine() = scenario(true,true)
    private fun scenario(search:Boolean,rotateToken:Boolean=false) {
        val server=MockWebServer().apply { start() };val base="http://handoff.test:${server.port}"
        val assets=InstrumentationRegistry.getInstrumentation().context.assets
        val dns=object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) }
        val details=java.util.concurrent.atomic.AtomicInteger()
        val requested=java.util.concurrent.CopyOnWriteArrayList<String>()
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                val path=request.requestUrl!!.encodedPath
                requested.add(request.path.orEmpty())
                return when {
                    path=="/recommend" -> MockResponse().setBody("{\"subjects\":[]}")
                    path=="/api" -> if(request.requestUrl!!.queryParameter("ids")==null) MockResponse().setBody("""{"list":[{"vod_id":"1","vod_name":"后会友期"}]}""") else {
                        val detailNumber=details.incrementAndGet()
                        val token=if(rotateToken && detailNumber>1) "fresh" else "fixture"
                        val response=MockResponse().setBody("""{"list":[{"vod_content":"简介","vod_play_from":"网页线路${'$'}${'$'}${'$'}可用线路","vod_play_url":"正片${'$'}$base/html${'$'}${'$'}${'$'}正片${'$'}$base/play?token=$token"}]}""")
                        if(detailNumber>1) response.setBodyDelay(1800,java.util.concurrent.TimeUnit.MILLISECONDS) else response
                    }
                    path=="/html" -> MockResponse().setBody("<html>not media</html>")
                    path=="/play" && search -> MockResponse().setResponseCode(302).setHeader("Location","$base/nested/index")
                    path=="/play" || path=="/nested/index" -> MockResponse().setHeader("Content-Type","application/vnd.apple.mpegurl").setBody(assets.open("test.m3u8").bufferedReader().use { it.readText() })
                    path.endsWith(".ts") -> MockResponse().setHeader("Content-Type","video/mp2t").setBody(Buffer().write(assets.open(path.substringAfterLast("/")).use { it.readBytes() }))
                    else -> MockResponse().setBody("{\"list\":[]}")
                }
            }
        }
        ui.runOnUiThread { ui.activity.viewModelStore.clear() }
        runBlocking { Storage(ui.activity.application).write(Saved(allowHttp=true,subscriptions=listOf(Subscription("handoff","测试","$base/sub",0,listOf(Source("测试来源","$base/api")))))) }
        lateinit var model:AppModel
        ui.runOnUiThread {
            model=ViewModelProvider(ui.activity,object:ViewModelProvider.Factory {
                override fun <T:ViewModel> create(clazz:Class<T>):T {
                    @Suppress("UNCHECKED_CAST") return AppModel(ui.activity.application,dns,{_,_,_->"$base/recommend"}) as T
                }
            })[AppModel::class.java]
            ui.activity.setContent { PocketTheme { Pocket(model) } }
        }
        val network=Network({true},dns=dns)
        try {
            ui.waitUntil(10000) { !model.state.value.loading }
            val episode=Episode("正片","$base/play?token=fixture")
            if(!search) assertEquals(ProbeStatus.READY,runBlocking { PlaybackProbe(network).inspect(episode) }.status)
            val frames=PlaybackMetrics.frames
            if(search) {
                ui.onNodeWithTag("openSearch").performClick()
                ui.onNodeWithTag("searchInput").performTextReplacement("后会友期")
                ui.onNodeWithContentDescription("开始搜索").performClick()
                ui.waitUntil(10000) { !model.state.value.searching && model.state.value.results.size==1 }
                assertEquals(0,details.get())
                ui.onNodeWithTag("searchVideo:1").performClick()
                ui.waitUntil(10000) { model.state.value.probeEpisodes.isNotEmpty() && !model.state.value.probeRunning }
                val checked=model.state.value.probeEpisodes.values.single()
                assertEquals(episode.url,checked.url)
                assertEquals("application/x-mpegURL",checked.mimeType)
                ui.onNodeWithTag("sourceChoice:${Source("测试来源","$base/api").key}:1").performScrollTo().performClick()
                ui.waitUntil(5000) { model.state.value.detailLoading }
                val area=ui.onNodeWithTag("detailLoading").getUnclippedBoundsInRoot()
                val spinner=ui.onNode(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate)).getUnclippedBoundsInRoot()
                assertEquals((area.left.value+area.right.value)/2,(spinner.left.value+spinner.right.value)/2,2f)
                assertTrue(kotlin.math.abs((area.top.value+area.bottom.value)/2-(spinner.top.value+spinner.bottom.value)/2)<100f)
                val instrument=InstrumentationRegistry.getInstrumentation()
                val dir=java.io.File(instrument.targetContext.getExternalFilesDir(null),"handoff").apply { mkdirs() }
                instrument.uiAutomation.takeScreenshot()?.let { bitmap -> try { java.io.File(dir,"loading.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } } finally { bitmap.recycle() } }
                ui.waitUntil(10000) { model.state.value.detail!=null }
                ui.onNodeWithContentDescription("播放线路：可用线路").assertExists()
                ui.onNodeWithText("立即播放").performScrollTo().performClick()
                assertEquals(if(rotateToken) "$base/play?token=fresh" else checked.url,model.state.value.playback!!.episode.url)
                if(rotateToken) {
                    assertEquals(ProbeStatus.UNKNOWN,model.state.value.probes.values.single())
                    assertNull(model.state.value.playback!!.episode.mimeType)
                } else assertEquals(checked.mimeType,model.state.value.playback!!.episode.mimeType)
            } else ui.runOnIdle { model.play(Video(Source("测试来源","$base/api"),"1","后会友期"),episode) }
            ui.waitUntil(15000) { PlaybackMetrics.frames>frames || ui.onAllNodes(hasText("播放失败",substring=true)).fetchSemanticsNodes().isNotEmpty() }
            val errors=ui.onAllNodes(hasText("播放失败",substring=true)).fetchSemanticsNodes().map { it.config.toString() }
            assertTrue("Probe reported READY but no video frame; player error: $errors",PlaybackMetrics.frames>frames)
            if(search) {
                assertTrue(requested.count { it.startsWith("/play?token=") }>=2)
                assertTrue(requested.any { it.startsWith("/nested/segment") })
                assertEquals(1,requested.count { it=="/html" })
            }
        } finally { ui.runOnUiThread { ui.activity.viewModelStore.clear() };network.close();server.shutdown() }
    }
}
