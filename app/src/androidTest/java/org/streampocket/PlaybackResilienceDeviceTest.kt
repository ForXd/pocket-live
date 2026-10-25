package org.streampocket

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Dns
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import java.lang.ref.WeakReference
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class PlaybackResilienceDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun model():AppModel {
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
        ui.waitUntil(15000) { !model.state.value.loading }
        ui.runOnIdle { model.allowHttp(true) }
        ui.waitUntil(10000) { model.state.value.saved.allowHttp }
        return model
    }
    private fun player(view:android.view.View):androidx.media3.common.Player? {
        if(view is androidx.media3.ui.PlayerView) return view.player
        if(view is android.view.ViewGroup) for(i in 0 until view.childCount) player(view.getChildAt(i))?.let { return it }
        return null
    }
    @Test fun smoothModeChoosesLowerRendition() = renditionCase(false)
    @Test fun transientServerFailureRetriesWithoutChangingSource() = renditionCase(true)
    private fun renditionCase(transient:Boolean) {
        val server=MockWebServer();server.start()
        val base="http://fixture.test:${server.port}"
        val assets=InstrumentationRegistry.getInstrumentation().context.assets
        val segments=AtomicInteger();val high=AtomicInteger();val low=AtomicInteger()
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                val path=request.requestUrl!!.encodedPath
                if(path=="/master.m3u8") return MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=320x180\nlow.m3u8\n"+if(transient) "" else "#EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=1920x1080\nhigh.m3u8\n")
                if(path=="/high.m3u8") high.incrementAndGet()
                if(path=="/low.m3u8") low.incrementAndGet()
                if(path.endsWith(".m3u8")) return MockResponse().setBody(assets.open("test.m3u8").bufferedReader().use { it.readText() })
                if(path=="/segment00.ts" && segments.incrementAndGet()==1 && transient) return MockResponse().setResponseCode(503)
                return MockResponse().setBody(Buffer().write(assets.open(path.removePrefix("/")).use { it.readBytes() }))
                    .setHeader("Content-Type","video/mp2t").throttleBody(64*1024,100,TimeUnit.MILLISECONDS)
            }
        }
        val model=model()
        try {
            val frames=PlaybackMetrics.frames
            ui.runOnIdle { model.smoothPlayback(true);model.play(Video(Source("合成多码率","$base/api"),"abr","合成视频"),Episode("第1集","$base/master.m3u8")) }
            ui.waitUntil(30000) { PlaybackMetrics.frames>frames }
            ui.waitUntil(15000) { (model.state.value.playback?.position ?: 0)>1500 }
            assertTrue("Must select lower advertised rendition",low.get()>0)
            assertEquals("Smooth mode should not request the 1080p rendition",0,high.get())
            if(transient) assertTrue("Temporary 503 should be retried",segments.get()>=2)
            ui.runOnIdle { model.stopPlayback() }
            ui.waitUntil(10000) { PlaybackMetrics.created==PlaybackMetrics.released && MediaCallOwner.active.get()==0 }
        } finally { ui.runOnUiThread { ui.activity.viewModelStore.clear() };server.shutdown() }
    }
    @Test fun repeatedSwitchBackgroundAndCancellationReleaseResources() {
        val server=MockWebServer();server.start()
        val base="http://fixture.test:${server.port}"
        val assets=InstrumentationRegistry.getInstrumentation().context.assets
        val slowRequests=AtomicInteger()
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                return when(request.requestUrl!!.encodedPath) {
                    "/sub" -> MockResponse().setBody("""{"name":"压力测试","sources":[{"name":"正常来源","url":"$base/api"},{"name":"慢来源","url":"$base/slow"}]}""")
                    "/slow" -> { slowRequests.incrementAndGet();MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
                    "/api" -> if(request.requestUrl!!.queryParameter("ids")==null) MockResponse().setBody("""{"list":[{"vod_id":"1","vod_name":"合成视频"}]}""") else
                        MockResponse().setBody("""{"list":[{"vod_play_from":"A${'$'}${'$'}${'$'}B","vod_play_url":"第1集${'$'}$base/a.mp4${'$'}${'$'}${'$'}第1集${'$'}$base/b.mp4"}]}""")
                    else -> MockResponse().setBody(Buffer().write(assets.open("test.mp4").use { it.readBytes() })).setHeader("Content-Type","video/mp4")
                }
            }
        }
        val model=model()
        val players=mutableListOf<WeakReference<Any>>()
        val javaHeap=mutableListOf<Long>();val pss=mutableListOf<Int>()
        try {
            ui.runOnIdle { model.importSubscription("$base/sub") }
            ui.waitUntil(15000) { model.state.value.saved.subscriptions.any { it.url=="$base/sub" } }
            val video=Video(Source("正常来源","$base/api"),"1","合成视频")
            ui.runOnIdle { model.open(video) }
            ui.waitUntil(15000) { model.state.value.detail!=null }
            ui.runOnIdle { model.play(video,model.state.value.detail!!.lines.first().episodes.first()) }
            repeat(20) { cycle ->
                ui.waitUntil(15000) { var ready=false;ui.runOnIdle { ready=player(ui.activity.window.decorView)?.playbackState==androidx.media3.common.Player.STATE_READY };ready }
                ui.runOnIdle { player(ui.activity.window.decorView)?.let { players.add(WeakReference(it)) } }
                val session=model.state.value.playback!!.sessionId
                val count=slowRequests.get()
                ui.runOnIdle { model.openAlternatives(session) }
                ui.waitUntil(15000) { model.state.value.alternatives.isNotEmpty() && slowRequests.get()>count }
                val option=model.state.value.alternatives.first()
                val before=PlaybackMetrics.frames
                ui.runOnIdle { model.chooseAlternative(option,true);model.chooseAlternative(option,true) }
                ui.waitUntil(15000) { model.state.value.playback!!.sessionId!=session && PlaybackMetrics.frames>before }
                ui.waitUntil(5000) { model.network.client.dispatcher.runningCallsCount()==0 }
                assertNull(model.state.value.alternativeSession)
                assertEquals(1L,PlaybackMetrics.created-PlaybackMetrics.released)
                if(cycle%5==4) {
                    ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                    assertEquals(0,MediaCallOwner.active.get());assertEquals(0,PlaybackMetrics.samplers)
                    assertEquals(PlaybackMetrics.created,PlaybackMetrics.released)
                    ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                    ui.waitUntil(15000) { PlaybackMetrics.created-PlaybackMetrics.released==1L }
                    System.gc();System.runFinalization()
                    javaHeap.add(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
                    val memory=android.os.Debug.MemoryInfo();android.os.Debug.getMemoryInfo(memory);pss.add(memory.totalPss)
                }
            }
            // Cancel without selecting, then leave playback while the next lookup is blocked.
            repeat(3) {
                val count=slowRequests.get()
                ui.runOnIdle { model.openAlternatives(model.state.value.playback!!.sessionId) }
                ui.waitUntil(15000) { slowRequests.get()>count }
                ui.runOnIdle { model.closeAlternatives() }
                ui.waitUntil(5000) { model.network.client.dispatcher.runningCallsCount()==0 }
            }
            val before=slowRequests.get()
            ui.runOnIdle { model.openAlternatives(model.state.value.playback!!.sessionId) }
            ui.waitUntil(15000) { slowRequests.get()>before }
            ui.runOnIdle { model.stopPlayback() }
            ui.waitUntil(5000) { model.network.client.dispatcher.runningCallsCount()==0 && MediaCallOwner.active.get()==0 && PlaybackMetrics.samplers==0 }
            assertEquals(PlaybackMetrics.created,PlaybackMetrics.released)
            assertEquals(0L,PlaybackMetrics.releaseFailures);assertEquals(0L,PlaybackMetrics.releaseTimeouts)
            assertEquals(0L,PlaybackMetrics.cleanupFailures)
            ui.runOnUiThread { ui.activity.viewModelStore.clear();ui.activity.setContent {} }
            ui.waitUntil(10000) { System.gc();System.runFinalization();players.all { it.get()==null } }
            assertTrue("Managed heap should plateau after warmup: $javaHeap",javaHeap.last()-javaHeap.first()<24*1024*1024)
            InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {
                putString("java_heap_after_5_10_15_20",javaHeap.toString());putString("pss_kib_after_5_10_15_20",pss.toString())
                putInt("weak_players_remaining",players.count { it.get()!=null });putLong("players_created",PlaybackMetrics.created)
                putLong("players_released",PlaybackMetrics.released);putInt("active_media_calls",MediaCallOwner.active.get())
                putLong("max_release_ms",PlaybackMetrics.maxReleaseMs)
            })
        } finally { ui.runOnUiThread { model.stopPlayback();ui.activity.viewModelStore.clear() };server.shutdown() }
    }
}
