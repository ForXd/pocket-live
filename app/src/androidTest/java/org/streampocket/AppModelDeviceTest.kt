package org.streampocket

import android.app.Application
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Dns
import okhttp3.mockwebserver.*
import org.junit.Test
import org.junit.Assert.*
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real ViewModel/network/cache/storage; controlled server only, no production policy changes. */
class AppModelDeviceTest {
    @Test fun cancelAndReplaceSearchThenClearInflightCache() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val server=MockWebServer(); server.start()
        val base="http://fixture.test:${server.port}"
        val delayed=AtomicInteger()
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.requestUrl!!.encodedPath=="/sub") return MockResponse().setBody("""{"sources":[{"name":"test","url":"$base/api"}]}""")
                val query=request.requestUrl!!.queryParameter("wd") ?: ""
                val response=MockResponse().setBody("""{"list":[{"vod_id":"$query","vod_name":"$query"}]}""")
                if(query.startsWith("slow")) { delayed.incrementAndGet();response.setBodyDelay(3,TimeUnit.SECONDS) }
                return response
            }
        }
        val owner=object:ViewModelStoreOwner { override val viewModelStore=ViewModelStore() }
        lateinit var model:AppModel
        fun main(action:()->Unit)=instrument.runOnMainSync(action)
        fun awaitCondition(condition:()->Boolean) {
            val deadline=android.os.SystemClock.elapsedRealtime()+60000
            while(!condition()) { if(android.os.SystemClock.elapsedRealtime()>deadline) fail("Timed out awaiting state");Thread.sleep(50) }
        }
        try {
            main { model=ViewModelProvider(owner,object:ViewModelProvider.Factory {
                override fun <T:ViewModel> create(clazz:Class<T>):T {
                    @Suppress("UNCHECKED_CAST")
                    return AppModel(instrument.targetContext.applicationContext as Application,
                        object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) }) as T
                }
            })[AppModel::class.java] }
            awaitCondition { !model.state.value.loading }
            main { model.allowHttp(true) };awaitCondition { model.state.value.saved.allowHttp }
            main { model.importSubscription("$base/sub") };awaitCondition { model.state.value.saved.subscriptions.isNotEmpty() }
            repeat(10) { round ->
                val before=delayed.get()
                main { model.search("slow$round") };awaitCondition { delayed.get()>before }
                main { model.cancelSearch();model.search("fresh$round") }
                awaitCondition { !model.state.value.searching && model.state.value.results.singleOrNull()?.id=="fresh$round" }
            }
            Thread.sleep(3500)
            assertEquals("fresh9",model.state.value.results.single().id)
            val before=delayed.get()
            main { model.search("slow-clear") };awaitCondition { delayed.get()>before }
            main { model.clearCache() }
            awaitCondition { !model.state.value.searching }
            main { model.refreshStorage() }
            awaitCondition { model.state.value.storageUsage?.responseBytes==0L }
            assertEquals("slow-clear",model.state.value.results.single().id)
            assertEquals(0L,java.io.File(instrument.targetContext.cacheDir,"responses-v1").listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() })
            main { model.cancelSearch() }
            awaitCondition { model.network.client.dispatcher.runningCallsCount()==0 }
            val video=model.state.value.results.single()
            val episode=Episode("owner-check","https://example.org/owner-check.mp4")
            lateinit var previous:Playback
            lateinit var current:Playback
            main {
                model.play(video,episode);previous=model.state.value.playback!!
                model.checkpoint(previous,5000)
                model.play(video,episode);current=model.state.value.playback!!
                model.checkpoint(current,9000)
                model.checkpoint(previous,12345) // Late callback from the replaced player.
            }
            assertNotEquals(previous.sessionId,current.sessionId)
            awaitCondition { model.state.value.saved.history.firstOrNull { it.playback.key==current.key }?.playback?.position==9000L }
            assertEquals(9000L,model.state.value.playback!!.position)
            val persisted=kotlinx.coroutines.runBlocking { Storage(instrument.targetContext).read() }
            assertEquals(9000L,persisted.history.first { it.playback.key==current.key }.playback.position)
            main { model.stopPlayback() }
        } finally { main { owner.viewModelStore.clear() };server.shutdown() }
    }
}
