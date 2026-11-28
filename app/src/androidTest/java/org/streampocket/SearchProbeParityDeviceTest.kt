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
    @Test fun expandedWorkStopsAtTwoPlayableSourcesWhileCatalogKeepsScanning() {
        val started=CountDownLatch(1);val release=CountDownLatch(1)
        val requestedMedia=java.util.concurrent.CopyOnWriteArrayList<String>()
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
                    else->{ requestedMedia+=url.encodedPath;started.countDown();release.await(10,TimeUnit.SECONDS);MockResponse().setHeader("Content-Type","video/mp4").setBody(Buffer().write(video)) }
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
            assertEquals(2,model.state.value.probes.values.count { it==ProbeStatus.READY })
            val checked=model.state.value.probeEpisodes.values
            assertEquals(2,checked.size)
            assertTrue("Must keep the successful second line",checked.all { it.url.contains("/media/") })
            val checkedRows=model.state.value.results.filter { model.state.value.probes[it.probeKey()]==ProbeStatus.READY }
            for((index,row) in checkedRows.withIndex()) {
                val trials=model.state.value.lineTrials.getValue(row.probeKey())
                assertEquals(ProbeStatus.FAILED,trials.getValue("$base/html").status)
                assertEquals(ProbeStatus.READY,trials.getValue("$base/media/${row.id}").status)
                if(index==0) assertEquals(ProbeStatus.READY,trials.getValue("$base/backup/${row.id}").status)
                else {
                    assertFalse("Stop on the second usable source, before starting its spare line",
                        "/backup/${row.id}" in requestedMedia)
                    assertFalse("$base/backup/${row.id}" in trials)
                }
                assertEquals("m3u8",model.state.value.probeLines[row.probeKey()])
            }
            assertTrue(model.state.value.results.filterNot { it in checkedRows }.none { it.probeKey() in model.state.value.lineTrials })
            assertFalse(model.state.value.probes.containsValue(ProbeStatus.CHECKING))
            val count=server.requestCount
            main {
                model.requestSourceProbe(null)
                model.requestSourceProbe(VideoGroups.workKey(checkedRows.first()))
            }
            await { !model.state.value.probeRunning && model.activeProbeCalls()==0 }
            assertEquals("Reopening a work with two verified choices leaves pending sources alone",count,server.requestCount)
            main { model.setSearchProbeActive(false);model.cancelSearch() }
            await { model.activeProbeCalls()==0 }
            main { model.browseSources(query="Film");model.setSearchProbeActive(true,catalog=true) }
            await { !model.state.value.catalogLoading && !model.state.value.probeRunning && model.state.value.probes.values.count { it==ProbeStatus.READY }==6 }
            assertTrue(model.state.value.catalogVideos.all { model.state.value.probes[it.probeKey()]==ProbeStatus.READY })
            assertTrue("The catalog still validates fallback lines",model.state.value.catalogVideos.all { row ->
                model.state.value.lineTrials[row.probeKey()]?.get("$base/backup/${row.id}")?.status==ProbeStatus.READY
            })
            main { model.cancelCatalog();model.setSearchProbeActive(false,catalog=true) }
            await { model.activeProbeCalls()==0 }
            val historyVideo=model.state.value.catalogVideos.first()
            main {
                model.dismissSearch()
                model.play(historyVideo,Episode("1","$base/history.mp4"))
                val session=checkNotNull(model.state.value.playback).sessionId
                model.playbackConfirmed(session)
                model.playbackFailed(session)
                model.stopPlayback()
            }
            assertTrue("Replaying a former result from history cannot repopulate a dismissed session",model.state.value.lineTrials.isEmpty())
        } finally { release.countDown();main {store.clear()};server.shutdown() }
    }
}
