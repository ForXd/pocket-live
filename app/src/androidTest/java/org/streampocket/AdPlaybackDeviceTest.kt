package org.streampocket

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Dns
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

class AdPlaybackDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun filteredHlsRendersAcrossMidrollWithoutRequestingAd() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val assets=instrument.context.assets
        val server=MockWebServer();server.start()
        val base="http://fixture.test:${server.port}"
        val ads=AtomicInteger()
        val original=assets.open("test.m3u8").bufferedReader().use { it.readText() }
        val manifest=original.replace("#EXTINF:2.000000,\nsegment02.ts", "#EXT-X-DISCONTINUITY\n#EXTINF:2.000000,\n/ads/insert.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:2.000000,\nsegment02.ts")
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                val path=request.requestUrl!!.encodedPath
                if(path.startsWith("/ads/")) { ads.incrementAndGet();return MockResponse().setResponseCode(404) }
                if(path.endsWith(".m3u8")) return MockResponse().setBody(manifest).setHeader("Content-Type","application/vnd.apple.mpegurl")
                return try { MockResponse().setBody(Buffer().write(assets.open(path.removePrefix("/")).use { it.readBytes() })).setHeader("Content-Type","video/mp2t") }
                    catch(_:Exception) { MockResponse().setResponseCode(404) }
            }
        }
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
        try {
            ui.waitUntil(15000) { !model.state.value.loading }
            ui.runOnIdle { model.allowHttp(true);model.filterAds(true) }
            ui.waitUntil(15000) { model.state.value.saved.allowHttp && model.state.value.saved.adFilter }
            val frames=PlaybackMetrics.frames
            ui.runOnIdle { model.play(Video(Source("广告测试","$base/api"),"ad-test","过滤播放"),Episode("第一集","$base/test.m3u8")) }
            ui.waitUntil(30000) { PlaybackMetrics.frames>frames }
            ui.waitUntil(30000) { ui.onAllNodes(hasText("已移除 1 个疑似广告切片",substring=true)).fetchSemanticsNodes().isNotEmpty() }
            ui.waitUntil(30000) { (model.state.value.playback?.position ?: 0)>10_000 }
            assertEquals("Filtered ad must never be downloaded",0,ads.get())
            ui.runOnIdle { model.stopPlayback() }
            ui.waitUntil(15000) { PlaybackMetrics.created==PlaybackMetrics.released }
            assertEquals(0,PlaybackMetrics.samplers)
        } finally { ui.runOnUiThread { model.stopPlayback();ui.activity.viewModelStore.clear() };server.shutdown() }
    }
}
