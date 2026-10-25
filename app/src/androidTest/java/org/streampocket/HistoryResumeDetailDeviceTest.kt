package org.streampocket

import android.app.Application
import android.content.Context
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Dns
import okhttp3.mockwebserver.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HistoryResumeDetailDeviceTest {
    @Test fun resumeImmediatelyAndOnlyCurrentCatalogMayCommit() {
        val instrument=InstrumentationRegistry.getInstrumentation();val base=instrument.targetContext
        val dir=File(base.cacheDir,"code7-resume-detail").apply { deleteRecursively();mkdirs() }
        val app=object:Application() {
            init { attachBaseContext(base) }
            override fun getFilesDir()=File(dir,"files").apply { mkdirs() }
            override fun getCacheDir()=File(dir,"cache").apply { mkdirs() }
            override fun getApplicationContext():Context=this
        }
        val server=MockWebServer();server.start(InetAddress.getByName("127.0.0.1"),0)
        val url="http://fixture.test:${server.port}";val failures=AtomicInteger()
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                val id=request.requestUrl!!.queryParameter("ids") ?: ""
                if(id=="fail" && failures.getAndIncrement()==0) return MockResponse().setResponseCode(503)
                val response=MockResponse().setBody("""{"list":[{"vod_id":"$id","vod_name":"同名剧","vod_play_from":"fixture","vod_play_url":"第1集${'$'}$url/$id/1.mp4#第2集${'$'}$url/$id/2.mp4"}]}""")
                return if(id=="slow") response.setBodyDelay(2,TimeUnit.SECONDS) else response
            }
        }
        val owner=object:ViewModelStoreOwner { override val viewModelStore=ViewModelStore() }
        lateinit var model:AppModel
        fun main(block:()->Unit)=instrument.runOnMainSync(block)
        fun await(test:()->Boolean) {
            val end=android.os.SystemClock.elapsedRealtime()+90000
            while(!test()) { if(android.os.SystemClock.elapsedRealtime()>end) fail("resume detail timeout");Thread.sleep(50) }
        }
        fun history(id:String)=History(Playback(Video(Source("本地源","$url/api"),id,"同名剧"),Episode("第1集","$url/$id/1.mp4"),42000),1)
        try {
            main { model=ViewModelProvider(owner,object:ViewModelProvider.Factory {
                override fun <T:ViewModel> create(modelClass:Class<T>):T {
                    @Suppress("UNCHECKED_CAST")
                    return AppModel(app,object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) }) as T
                }
            })[AppModel::class.java] }
            await { !model.state.value.loading };main { model.allowHttp(true) };await { model.state.value.saved.allowHttp }
            main { model.resume(history("slow"));assertEquals(42000L,model.state.value.playback!!.position);assertTrue(model.state.value.detailLoading);assertNull(model.state.value.detail) }
            assertNotNull(server.takeRequest(30,TimeUnit.SECONDS))
            main { model.resume(history("current")) }
            await { !model.state.value.detailLoading && model.state.value.detail?.video?.id=="current" }
            Thread.sleep(2300)
            main {
                assertEquals("current",model.state.value.detail!!.video.id)
                assertEquals("current",model.state.value.playback!!.video.id)
                assertEquals("第2集",adjacentEpisode(model.state.value.detail,model.state.value.playback!!,1)!!.name)
                model.open(history("slow").playback.video)
                model.resume(history("fail"))
            }
            await { !model.state.value.detailLoading && model.state.value.detailError!=null }
            lateinit var failedSession:Playback
            main { failedSession=model.state.value.playback!!;assertEquals("fail",failedSession.video.id);assertEquals(42000L,failedSession.position);assertNull(model.state.value.detail);model.retryPlaybackDetail() }
            await { !model.state.value.detailLoading && model.state.value.detail?.video?.id=="fail" }
            main { assertEquals(failedSession,model.state.value.playback);assertNull(model.state.value.detailError);model.resume(history("slow"));model.stopPlayback() }
            Thread.sleep(2300)
            assertNull(model.state.value.playback);assertNull(model.state.value.detail);assertFalse(model.state.value.detailLoading)
            instrument.sendStatus(0,android.os.Bundle().apply { putString("resume_detail_result","immediate breakpoint;current-only async detail;old open/resume cancellation;next episode available;failure preserves playback;retry same session;stop rejects late detail passed") })
        } finally { main { owner.viewModelStore.clear() };server.shutdown();dir.deleteRecursively() }
    }
}
