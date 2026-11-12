package org.streampocket

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class CollectionModelDeviceTest {
    @Test fun discoversUnsearchedSeasonsRetriesAndDiscardsCancelledFamily() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        fun main(block:()->Unit)=instrument.runOnMainSync(block)
        fun await(condition:()->Boolean) {
            val end=android.os.SystemClock.elapsedRealtime()+12000
            while(!condition()) { if(android.os.SystemClock.elapsedRealtime()>end) fail("Collection state timed out");Thread.sleep(20) }
        }
        val server=MockWebServer().apply { start() };val base="http://series.test:${server.port}"
        val source=Source("片库","$base/api");val failing=AtomicBoolean(false)
        val requests=java.util.concurrent.CopyOnWriteArrayList<String>()
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(r:RecordedRequest):MockResponse {
                val url=r.requestUrl!!
                if(url.encodedPath=="/recommend") return MockResponse().setBody("{\"subjects\":[]}")
                requests.add(url.queryParameter("wd").orEmpty())
                if(failing.get()) return MockResponse().setResponseCode(503)
                val title=if(url.queryParameter("wd")=="Other") "Other" else "Show"
                return MockResponse().setBody("""{"list":[{"vod_id":"1","vod_name":"$title Season 1"},{"vod_id":"2","vod_name":"$title Season 2"},{"vod_id":"x","vod_name":"Unrelated Season 2"}]}""")
            }
        }
        val app=instrument.targetContext.applicationContext as Application
        runBlocking { Storage(app).write(Saved(allowHttp=true,subscriptions=listOf(Subscription("s","来源","$base/sub",0,listOf(source))))) }
        val store=ViewModelStore();lateinit var model:AppModel
        main { model=AppModel(app,object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) },{_,_,_->"$base/recommend"});store.put("m",model) }
        try {
            await { !model.state.value.loading }
            val video=Video(source,"1","Show Season 1")
            main { model.related(video) }
            await { !model.state.value.relatedLoading }
            assertTrue(requests.contains("Show"));assertEquals(2,model.state.value.relatedVideos.size)
            assertTrue(model.state.value.results.isEmpty())
            failing.set(true)
            main { model.related(Video(source,"3","Failed Season 1"),true) }
            await { !model.state.value.relatedLoading }
            assertNotNull(model.state.value.relatedError)
            assertEquals(1,model.state.value.relatedVideos.size)
            failing.set(false)
            main { model.related(video,true) }
            await { !model.state.value.relatedLoading }
            assertNull(model.state.value.relatedError)
            main { model.related(Video(source,"1","Other Season 1"));model.closeDetail() }
            Thread.sleep(200)
            assertTrue(model.state.value.relatedVideos.isEmpty());assertFalse(model.state.value.relatedLoading)
        } finally { main { store.clear() };server.shutdown() }
    }
}
