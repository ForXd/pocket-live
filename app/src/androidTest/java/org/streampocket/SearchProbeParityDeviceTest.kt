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
        val end=android.os.SystemClock.elapsedRealtime()+15000
        while(!condition()) { if(android.os.SystemClock.elapsedRealtime()>end) fail("Timed out waiting for search probes");Thread.sleep(20) }
    }
    @Test fun firstLineFailureDoesNotHidePlayableLinesAndThreeRequestsStopAtTwo() {
        val started=CountDownLatch(3);val release=CountDownLatch(1)
        val server=MockWebServer().apply { start() };val base="http://search.test:${server.port}"
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(r:RecordedRequest):MockResponse {
                val url=r.requestUrl!!
                return when(url.encodedPath) {
                    "/recommend"->MockResponse().setBody("{\"subjects\":[]}")
                    "/api"-> {
                        val id=url.queryParameter("ids")
                        if(id==null) MockResponse().setBody("""{"list":[${(1..6).joinToString(",") { """{"vod_id":"$it","vod_name":"Film $it"}""" }}]}""")
                        else MockResponse().setBody("""{"list":[{"vod_play_from":"dead${'$'}${'$'}${'$'}working","vod_play_url":"1${'$'}$base/html${'$'}${'$'}${'$'}1${'$'}$base/media/$id"}]}""")
                    }
                    "/html"->MockResponse().setBody("<html>unavailable</html>")
                    else->{started.countDown();if(url.encodedPath.endsWith("/3")) MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) else {release.await(10,TimeUnit.SECONDS);MockResponse().setBody(Buffer().write(byteArrayOf(0,0,0,24)+"ftypisom".toByteArray()))}}
                }
            }
        }
        val app=instrument.targetContext.applicationContext as Application
        runBlocking { Storage(app).write(Saved(allowHttp=true,subscriptions=listOf(Subscription("probe","Probe","$base/sub",0,listOf(Source("Source","$base/api")))))) }
        val store=ViewModelStore();lateinit var model:AppModel
        main { model=AppModel(app,object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) },{_,_,_->"$base/recommend"});store.put("model",model) }
        try {
            await { !model.state.value.loading }
            val created=PlaybackMetrics.created
            main { model.search("Film");model.setSearchProbeActive(true) }
            assertTrue("Search must start three media probes concurrently",started.await(8,TimeUnit.SECONDS))
            assertEquals(3,model.activeProbeCalls())
            release.countDown()
            await { !model.state.value.probeRunning && model.activeProbeCalls()==0 }
            assertEquals(2,model.state.value.probes.values.count { it==ProbeStatus.READY })
            assertTrue(model.state.value.results.take(2).all { model.state.value.probes[it.probeKey()]==ProbeStatus.READY })
            assertFalse(model.state.value.probes.containsValue(ProbeStatus.CHECKING))
            assertEquals(created,PlaybackMetrics.created)
            val count=server.requestCount;Thread.sleep(200);assertEquals(count,server.requestCount)
            main { model.setSearchProbeActive(false);model.cancelSearch() }
            await { model.activeProbeCalls()==0 }
            main { model.browseSources(query="Film");model.setSearchProbeActive(true,catalog=true) }
            await { !model.state.value.catalogLoading && !model.state.value.probeRunning && model.state.value.probes.values.count { it==ProbeStatus.READY }==2 }
            assertTrue(model.state.value.catalogVideos.take(2).all { model.state.value.probes[it.probeKey()]==ProbeStatus.READY })
            main { model.cancelCatalog();model.setSearchProbeActive(false,catalog=true) }
            await { model.activeProbeCalls()==0 }
        } finally { release.countDown();main {store.clear()};server.shutdown() }
    }
}
