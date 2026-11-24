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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AlternativeProbeDeviceTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private fun main(block:()->Unit)=instrument.runOnMainSync(block)
    private fun await(condition:()->Boolean) {
        val end=android.os.SystemClock.elapsedRealtime()+60000
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
    @Test fun automaticallyTriesEveryMatchingEpisodeOneAtATime() {
        val firstRequest=CountDownLatch(1)
        val release=CountDownLatch(1)
        val paths=java.util.concurrent.CopyOnWriteArrayList<String>()
        val video=mediaFixtureBytes("test.mp4")
        try {
            fixture({ path ->
                paths.add(path)
                firstRequest.countDown()
                release.await(10,TimeUnit.SECONDS)
                MockResponse().setHeader("Content-Type","video/mp4").setBody(Buffer().write(video))
            },slowSearch=true) { model,_ ->
                val original=model.state.value.playback!!
                main { model.openAlternatives(original.sessionId) }
                assertTrue("The visible panel starts its first trial",firstRequest.await(8,TimeUnit.SECONDS))
                assertEquals(1,model.activeAlternativeProbeCalls())
                val pending=model.state.value.alternatives.first { it.probeStatus!=ProbeStatus.READY }
                main { model.chooseAlternative(pending,true) }
                assertEquals(original.sessionId,model.state.value.playback!!.sessionId)
                await { model.activeSearchCalls()>0 }
                assertEquals(1,paths.distinct().size)
                release.countDown()
                await { !model.state.value.alternativesLoading && model.activeAlternativeProbeCalls()==0 && model.activeSearchCalls()==0 }
                val options=model.state.value.alternatives
                assertEquals(5,options.size)
                assertTrue("Every candidate must finish a real trial",options.all { it.probeStatus==ProbeStatus.READY })
                assertEquals(setOf("/a-2.mp4","/b-2.mp4","/c-2.mp4","/d-2.mp4","/e-2.mp4"),paths.toSet())
                assertEquals(original.sessionId,model.state.value.playback!!.sessionId)
                main { model.chooseAlternative(options.last(),true) }
                assertEquals(options.last().episode.url,model.state.value.playback!!.episode.url)
                assertEquals(84_000L,model.state.value.playback!!.position)
                assertNotEquals(original.sessionId,model.state.value.playback!!.sessionId)
            }
        } finally { release.countDown() }
    }

    @Test fun rapidReopenCancelsQueuedTrialsAndStaleSessionCleanupCannotCloseTheNewPanel() {
        val paths = java.util.concurrent.CopyOnWriteArrayList<String>()
        fixture({ path ->
            paths.add(path)
            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        }, slowSearch=true) { model,_ ->
            val original = model.state.value.playback!!
            main { model.openAlternatives(original.sessionId) }
            repeat(5) { round ->
                await { paths.size > round && model.activeAlternativeProbeCalls() == 1 && model.activeSearchCalls() > 0 }
                assertEquals(5, model.state.value.alternatives.size)
                main {
                    model.closeAlternatives(original.sessionId)
                    if (round < 4) model.openAlternatives(original.sessionId)
                }
            }
            await { model.activeAlternativeProbeCalls() == 0 && model.activeSearchCalls() == 0 && MediaCallOwner.active.get() == 0 }
            assertEquals("Cancelled candidates never start their requests", setOf("/a-2.mp4"), paths.toSet())
            assertEquals(original, model.state.value.playback)
            assertNull(model.state.value.alternativeSession)
            assertTrue(model.state.value.alternatives.isEmpty())
            val next = model.state.value.detail!!.lines.first().episodes.first()
            main {
                model.play(original.video, next)
                model.openAlternatives(model.state.value.playback!!.sessionId)
                model.closeAlternatives(original.sessionId)
            }
            await { "/a-1.mp4" in paths }
            assertEquals(model.state.value.playback!!.sessionId, model.state.value.alternativeSession)
            main { model.stopPlayback() }
            await { model.activeAlternativeProbeCalls() == 0 && model.activeSearchCalls() == 0 && MediaCallOwner.active.get() == 0 }
            assertTrue(model.state.value.alternatives.isEmpty())
            assertFalse(model.state.value.alternativesLoading)
        }
    }

    @Test fun recognizableMp4HeaderWithoutADecodableFrameIsUnavailable() {
        val video=mediaFixtureBytes("test.mp4")
        // The original ftyp and start of moov are intact. There are no tracks or media samples.
        val truncated=video.copyOf(64)
        fixture({ path -> when(path) {
            "/a-2.mp4" -> MockResponse().setHeader("Content-Type","video/mp4").setBody(Buffer().write(video))
            "/b-2.mp4" -> MockResponse().setHeader("Content-Type","video/mp4").setBody(Buffer().write(truncated))
            "/c-2.mp4" -> MockResponse().setBody("<html>Login required</html>")
            else -> MockResponse().setResponseCode(404)
        } }) { model,_ ->
            main { model.openAlternatives(model.state.value.playback!!.sessionId) }
            await { !model.state.value.alternativesLoading && model.activeAlternativeProbeCalls()==0 }
            val options=model.state.value.alternatives
            assertEquals(5,options.size)
            assertEquals(1,options.count { it.probeStatus==ProbeStatus.READY })
            assertEquals(ProbeStatus.FAILED,options.single { it.episode.url.endsWith("/b-2.mp4") }.probeStatus)
            assertEquals(4,options.count { it.probeStatus==ProbeStatus.FAILED })
            val session=model.state.value.playback!!.sessionId
            main { model.chooseAlternative(options.first { it.probeStatus==ProbeStatus.FAILED },true) }
            assertEquals(session,model.state.value.playback!!.sessionId)
        }
    }
}

internal fun mediaFixtureBytes(asset:String):ByteArray =
    InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { it.readBytes() }
