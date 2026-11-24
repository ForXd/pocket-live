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

class SearchProbeParityDeviceTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private fun main(block:()->Unit)=instrument.runOnMainSync(block)
    private fun await(condition:()->Boolean) {
        val end=android.os.SystemClock.elapsedRealtime()+60000
        while(!condition()) { if(android.os.SystemClock.elapsedRealtime()>end) fail("Timed out waiting for search probes");Thread.sleep(20) }
    }
    @Test fun collapsedSearchWaitsThenExpandedWorkChecksEverySourceAndKeepsWorkingLine() {
        val started=CountDownLatch(1);val release=CountDownLatch(1)
        val server=MockWebServer().apply { start() };val base="http://search.test:${server.port}"
        val video=mediaFixtureBytes("test.mp4")
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                val url=request.requestUrl!!
                return when(url.encodedPath) {
                    "/recommend"->MockResponse().setBody("{\"subjects\":[]}")
                    "/api"-> {
                        val id=url.queryParameter("ids")
                        if(id==null) MockResponse().setBody("""{"list":[${(1..6).joinToString(",") { """{"vod_id":"$it","vod_name":"Film"}""" }}]}""")
                        else MockResponse().setBody("""{"list":[{"vod_play_from":"yun${'$'}${'$'}${'$'}m3u8${'$'}${'$'}${'$'}backup","vod_play_url":"1${'$'}$base/html${'$'}${'$'}${'$'}1${'$'}$base/media/$id${'$'}${'$'}${'$'}1${'$'}$base/backup/$id"}]}""")
                    }
                    "/html"->MockResponse().setResponseCode(404)
                    else->{ started.countDown();release.await(10,TimeUnit.SECONDS);MockResponse().setHeader("Content-Type","video/mp4").setBody(Buffer().write(video)) }
                }
            }
        }
        val app=instrument.targetContext.applicationContext as Application
        runBlocking { Storage(app).write(Saved(allowHttp=true,subscriptions=listOf(Subscription("probe","Probe","$base/sub",0,listOf(Source("Source","$base/api")))))) }
        val store=ViewModelStore();lateinit var model:AppModel
        main { model=AppModel(app,object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) },{_,_,_->"$base/recommend"});store.put("model",model) }
        try {
            await { !model.state.value.loading }
            main { model.search("Film");model.setSearchProbeActive(true) }
            await { !model.state.value.searching && model.state.value.results.size==6 }
            Thread.sleep(200)
            assertEquals(0,model.activeProbeCalls())
            assertTrue(model.state.value.probes.isEmpty())
            main { model.requestSourceProbe(VideoGroups.workKey(model.state.value.results.first())) }
            assertTrue("Expanded work starts automatic playback validation",started.await(8,TimeUnit.SECONDS))
            assertEquals(1,model.activeProbeCalls())
            release.countDown()
            await { !model.state.value.probeRunning && model.activeProbeCalls()==0 }
            assertEquals(6,model.state.value.probes.values.count { it==ProbeStatus.READY })
            val checked=model.state.value.probeEpisodes.values
            assertEquals(6,checked.size)
            assertTrue("Must keep the successful second line",checked.all { it.url.contains("/media/") })
            for(row in model.state.value.results) {
                val trials=model.state.value.lineTrials.getValue(row.probeKey())
                assertEquals(3,trials.size)
                assertEquals(ProbeStatus.FAILED,trials.getValue("$base/html").status)
                assertEquals(ProbeStatus.READY,trials.getValue("$base/media/${row.id}").status)
                assertEquals(ProbeStatus.READY,trials.getValue("$base/backup/${row.id}").status)
                assertEquals("m3u8",model.state.value.probeLines[row.probeKey()])
            }
            assertFalse(model.state.value.probes.containsValue(ProbeStatus.CHECKING))
            val count=server.requestCount;Thread.sleep(200);assertEquals(count,server.requestCount)
            main { model.setSearchProbeActive(false);model.cancelSearch() }
            await { model.activeProbeCalls()==0 }
            main { model.browseSources(query="Film");model.setSearchProbeActive(true,catalog=true) }
            await { !model.state.value.catalogLoading && !model.state.value.probeRunning && model.state.value.probes.values.count { it==ProbeStatus.READY }==6 }
            assertTrue(model.state.value.catalogVideos.all { model.state.value.probes[it.probeKey()]==ProbeStatus.READY })
            main { model.cancelCatalog();model.setSearchProbeActive(false,catalog=true) }
            await { model.activeProbeCalls()==0 }
        } finally { release.countDown();main {store.clear()};server.shutdown() }
    }
}
