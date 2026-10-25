package org.streampocket

import android.app.Application
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Dns
import okhttp3.mockwebserver.*
import org.junit.Test
import org.junit.Assert.*
import java.net.InetAddress

class AlternativesModelDeviceTest {
    @Test fun crossSourceMatchesEpisodePreservesProgressAndRejectsStaleChoice() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val server=MockWebServer();server.start()
        val base="http://fixture.test:${server.port}"
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                val path=request.requestUrl!!.encodedPath
                if(path=="/sub") return MockResponse().setBody("""{"sources":[{"name":"A","url":"$base/a"},{"name":"B","url":"$base/b"}]}""")
                if(request.requestUrl!!.queryParameter("ids")==null) return MockResponse().setBody("""{"list":[{"vod_id":"1","vod_name":"合成剧 第二季"},{"vod_id":"wrong","vod_name":"合成剧 第一季"}]}""")
                val episodes=if(path=="/a") "第1集${'$'}$base/a1.mp4#第2集${'$'}$base/a2.mp4" else "01${'$'}$base/b1.mp4#02${'$'}$base/b2.mp4"
                return MockResponse().setBody("""{"list":[{"vod_play_from":"线路$path","vod_play_url":"$episodes"}]}""")
            }
        }
        val store=ViewModelStore()
        lateinit var model:AppModel
        fun main(block:()->Unit)=instrument.runOnMainSync(block)
        fun await(test:()->Boolean) {
            val end=android.os.SystemClock.elapsedRealtime()+15000
            while(!test()) { if(android.os.SystemClock.elapsedRealtime()>end) fail("Timed out awaiting model state");Thread.sleep(20) }
        }
        main {
            model=ViewModelProvider(store,object:ViewModelProvider.Factory {
                override fun <T:ViewModel> create(clazz:Class<T>):T {
                    @Suppress("UNCHECKED_CAST")
                    return AppModel(instrument.targetContext.applicationContext as Application,object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) }) as T
                }
            })[AppModel::class.java]
        }
        try {
            await { !model.state.value.loading };main { model.allowHttp(true) };await { model.state.value.saved.allowHttp }
            main { model.importSubscription("$base/sub") };await { model.state.value.saved.subscriptions.any { it.url=="$base/sub" } }
            val a=Video(Source("A","$base/a"),"1","合成剧 第二季")
            main { model.open(a) };await { model.state.value.detail?.video?.source?.url=="$base/a" }
            main { model.play(a,model.state.value.detail!!.lines.first().episodes[1]);model.checkpoint(model.state.value.playback!!,84_000) }
            val original=model.state.value.playback!!
            main { model.openAlternatives(original.sessionId) }
            await { !model.state.value.alternativesLoading }
            val alternative=model.state.value.alternatives.single()
            assertEquals("$base/b2.mp4",alternative.episode.url)
            main { model.chooseAlternative(alternative,true) }
            assertEquals(84_000L,model.state.value.playback!!.position)
            val chosen=model.state.value.playback!!.sessionId
            assertNotEquals(original.sessionId,chosen)
            main { model.chooseAlternative(alternative,true) };assertEquals(chosen,model.state.value.playback!!.sessionId)
            await { model.state.value.detail?.video?.source?.url=="$base/b" }
            main { model.openAlternatives(chosen) };await { !model.state.value.alternativesLoading }
            main { model.chooseAlternative(model.state.value.alternatives.single(),false) }
            assertEquals(0L,model.state.value.playback!!.position)
            main { model.stopPlayback() }
            await { model.network.client.dispatcher.runningCallsCount()==0 }
            assertNull(model.state.value.alternativeSession)
        } finally { main { store.clear() };server.shutdown() }
    }
}
