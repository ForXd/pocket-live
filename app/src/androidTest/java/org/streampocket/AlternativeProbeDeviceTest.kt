package org.streampocket

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AlternativeProbeDeviceTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private fun main(block:()->Unit)=instrument.runOnMainSync(block)
    private fun await(condition:()->Boolean) {
        val end=android.os.SystemClock.elapsedRealtime()+15000
        while(!condition()) {
            if(android.os.SystemClock.elapsedRealtime()>end) fail("Timed out waiting for alternative probe state")
            Thread.sleep(20)
        }
    }
    private fun fixture(media:(String)->MockResponse,slowSearch:Boolean=false,run:(AppModel,String)->Unit) {
        val server=MockWebServer().apply { start() }
        val base="http://alternatives.test:${server.port}"
        val names=listOf("current","a","b","c","d","e")
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                val path=request.requestUrl!!.encodedPath
                if(path=="/slow") return if(request.requestUrl!!.queryParameter("wd")!=null) MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) else MockResponse().setBody("{\"list\":[]}")
                if(path=="/recommend") return MockResponse().setBody("{\"subjects\":[]}")
                if(path=="/api") {
                    val lines=names.joinToString("${'$'}${'$'}${'$'}")
                    val episodes=names.joinToString("${'$'}${'$'}${'$'}") { "1${'$'}$base/$it-1.mp4#2${'$'}$base/$it-2.mp4" }
                    return MockResponse().setBody("""{"list":[{"vod_play_from":"$lines","vod_play_url":"$episodes"}]}""")
                }
                return media(path)
            }
        }
        val app=instrument.targetContext.applicationContext as Application
        runBlocking { Storage(app).write(Saved(allowHttp=true,subscriptions=if(slowSearch) listOf(Subscription("slow","Slow","$base/sub",0,listOf(Source("Slow","http://slow.test:${server.port}/slow")))) else emptyList())) }
        val store=ViewModelStore()
        lateinit var model:AppModel
        main {
            model=AppModel(app,object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) },{_,_,_->"$base/recommend"})
            store.put("model",model)
        }
        try {
            await { !model.state.value.loading }
            val video=Video(Source("Fixture","$base/api"),"1","Fixture")
            main { model.open(video) }
            await { model.state.value.detail!=null }
            main { model.play(video,model.state.value.detail!!.lines.first().episodes[1]);model.checkpoint(model.state.value.playback!!,84_000) }
            run(model,base)
        } finally { main { store.clear() };server.shutdown() }
    }
    private fun mp4()=MockResponse().setBody(Buffer().write(byteArrayOf(0,0,0,24)+"ftypisom".toByteArray()))

    @Test fun probesCurrentEpisodeConcurrentlyAndStopsAtTwoReady() {
        val started=CountDownLatch(3)
        val release=CountDownLatch(1)
        val paths=Collections.synchronizedList(mutableListOf<String>())
        try {
            fixture({ path ->
                paths.add(path);started.countDown()
                if(path in listOf("/a-2.mp4","/b-2.mp4")) {
                    release.await(10,TimeUnit.SECONDS);mp4()
                } else MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            },slowSearch=true) { model,_ ->
                val original=model.state.value.playback!!
                main { model.openAlternatives(original.sessionId) }
                assertTrue("Three probes must start without waiting for the first to finish",started.await(5,TimeUnit.SECONDS))
                assertEquals(3,model.activeAlternativeProbeCalls())
                await { model.activeSearchCalls()>0 }
                release.countDown()
                await { !model.state.value.alternativesLoading && model.activeAlternativeProbeCalls()==0 && model.activeSearchCalls()==0 }
                val options=model.state.value.alternatives
                assertEquals(2,options.count { it.probeStatus==ProbeStatus.READY })
                assertTrue(options.take(2).all { it.probeStatus==ProbeStatus.READY })
                assertTrue(options.none { it.probeStatus==ProbeStatus.CHECKING })
                assertTrue(paths.none { it.endsWith("-1.mp4") })
                val count=paths.size;Thread.sleep(200);assertEquals("No new probe after the success limit",count,paths.size)
                assertEquals(original.sessionId,model.state.value.playback!!.sessionId)
                main { model.chooseAlternative(options.first(),true) }
                assertEquals(84_000L,model.state.value.playback!!.position)
                assertNotEquals(original.sessionId,model.state.value.playback!!.sessionId)
            }
        } finally { release.countDown() }
    }

    @Test fun closingAndLeavingPlaybackCancelPendingProbesWithoutLateResults() {
        fixture({ MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }) { model,_ ->
            repeat(2) { iteration ->
                main { model.openAlternatives(model.state.value.playback!!.sessionId) }
                await { model.activeAlternativeProbeCalls()==3 }
                main { if(iteration==0) model.closeAlternatives() else model.stopPlayback() }
                await { model.activeAlternativeProbeCalls()==0 }
                assertNull(model.state.value.alternativeSession)
                assertTrue(model.state.value.alternatives.isEmpty())
                assertFalse(model.state.value.alternativesLoading)
            }
        }
    }

    @Test fun oneReadyDoesNotCountHtmlOrEncryptedMediaAsSecondSuccess() {
        fixture({ path -> when(path) {
            "/a-2.mp4" -> mp4()
            "/b-2.mp4" -> MockResponse().setBody("<html>Login required</html>")
            "/c-2.mp4" -> MockResponse().setBody("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:3,\nsegment.ts\n")
            else -> MockResponse().setResponseCode(404)
        } }) { model,_ ->
            main { model.openAlternatives(model.state.value.playback!!.sessionId) }
            await { !model.state.value.alternativesLoading && model.activeAlternativeProbeCalls()==0 }
            val options=model.state.value.alternatives
            assertEquals(5,options.size)
            assertEquals(1,options.count { it.probeStatus==ProbeStatus.READY })
            assertEquals(1,options.count { it.probeStatus==ProbeStatus.UNKNOWN })
            assertEquals(3,options.count { it.probeStatus==ProbeStatus.FAILED })
        }
    }
}
