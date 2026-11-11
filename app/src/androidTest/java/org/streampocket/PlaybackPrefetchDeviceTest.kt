package org.streampocket

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import android.net.Uri
import okhttp3.Dns
import okhttp3.mockwebserver.*
import org.junit.Test
import org.junit.Assert.*
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class PlaybackPrefetchDeviceTest {
    @Test fun nextSegmentLatencyOverlapsCurrentReadWithinBudget() {
        val server=MockWebServer();server.start()
        val base="http://fixture.test:${server.port}"
        val network=Network({true},object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) })
        val calls=MediaCallOwner(network.client)
        val upstream=OkHttpDataSource.Factory(calls)
        val requests=AtomicInteger()
        val lookAhead=HlsLookAhead(upstream,network.client)
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                requests.incrementAndGet()
                return MockResponse().setBody("a".repeat(8192)).setHeadersDelay(600,TimeUnit.MILLISECONDS)
            }
        }
        try {
            // The production streaming path: per-request latency must be hidden by look-ahead.
            val playlist="#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\ns0.ts\n#EXTINF:2,\ns1.ts\n#EXTINF:2,\ns2.ts\n#EXT-X-ENDLIST\n"
            lookAhead.observe(HlsPlaylistParser().parse(Uri.parse("$base/list.m3u8"),playlist.byteInputStream()))
            val factory=lookAhead.factory
            val started=System.nanoTime()
            repeat(3) { i ->
                val source=factory.createDataSource()
                source.open(DataSpec(Uri.parse("$base/s$i.ts")))
                val buffer=ByteArray(8192)
                while(source.read(buffer,0,buffer.size)!=-1) { }
                source.close()
                Thread.sleep(650) // media consumption while the next network request can progress
            }
            val elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply { putLong("three_segment_ms",elapsed) })
            assertEquals(3,requests.get())
            assertTrue("Three segments took ${elapsed}ms: serial headers cause repeated buffer waits",elapsed<3200)
        } finally { lookAhead.close();calls.close();server.shutdown();network.client.connectionPool.evictAll() }
    }
    @Test fun liveAndEncryptedPlaylistsDoNotSpeculate() {
        for(suffix in listOf("", "#EXT-X-ENDLIST\n")) {
            withFixture { base,server,lookAhead,_ ->
                val encrypted=if(suffix.isNotEmpty()) "#EXT-X-KEY:METHOD=AES-128,URI=\"key\",IV=0x00000000000000000000000000000001\n" else ""
                lookAhead.observe(HlsPlaylistParser().parse(Uri.parse("$base/list.m3u8"),("#EXTM3U\n#EXT-X-TARGETDURATION:2\n"+encrypted+"#EXTINF:2,\ns0.ts\n#EXTINF:2,\ns1.ts\n"+suffix).byteInputStream()))
                server.enqueue(MockResponse().setBody("current"))
                val data=lookAhead.factory.createDataSource();data.open(DataSpec(Uri.parse("$base/s0.ts")));data.close()
                assertEquals("/s0.ts",server.takeRequest(2,TimeUnit.SECONDS)!!.path)
                assertNull("Must not prefetch mutable/encrypted media",server.takeRequest(250,TimeUnit.MILLISECONDS))
            }
        }
    }
    @Test fun adaptivePlaylistsKeepNativeBandwidthLoading() {
        withFixture { base,server,lookAhead,_ ->
            val parser=lookAhead.parser(androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory())
            val master=parser.createPlaylistParser().parse(Uri.parse("$base/master.m3u8"),"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=300000\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\nhigh.m3u8\n".byteInputStream()) as androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
            parser.createPlaylistParser(master,null).parse(Uri.parse("$base/low.m3u8"),"#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\ns0.ts\n#EXTINF:2,\ns1.ts\n#EXT-X-ENDLIST\n".byteInputStream())
            server.enqueue(MockResponse().setBody("current"))
            val data=lookAhead.factory.createDataSource();data.open(DataSpec(Uri.parse("$base/s0.ts")));data.close()
            assertEquals("/s0.ts",server.takeRequest(2,TimeUnit.SECONDS)!!.path)
            assertNull(server.takeRequest(250,TimeUnit.MILLISECONDS))
        }
    }
    @Test fun cancellationClosesStalledPrefetchAndDoesNotRetainRequests() {
        withFixture { base,server,lookAhead,_ ->
            observeThree(base,lookAhead)
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest)=if(request.path=="/s1.ts") MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) else MockResponse().setBody("current")
            }
            val source=lookAhead.factory.createDataSource();source.open(DataSpec(Uri.parse("$base/s0.ts")));source.close()
            repeat(2) { assertNotNull(server.takeRequest(2,TimeUnit.SECONDS)) }
            lookAhead.close()
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
            while(MediaCallOwner.active.get()!=0 && System.nanoTime()<deadline) Thread.sleep(10)
            assertEquals(0,MediaCallOwner.active.get())
        }
    }
    @Test fun oversizedSpeculationFallsBackAndDisablesFurtherPrefetch() {
        withFixture { base,server,lookAhead,_ ->
            observeThree(base,lookAhead)
            val nextRequests=AtomicInteger()
            server.dispatcher=object:Dispatcher() {
                override fun dispatch(request:RecordedRequest):MockResponse {
                    if(request.path=="/s1.ts" && nextRequests.incrementAndGet()==1)
                        return MockResponse().setBody("x".repeat(HlsLookAhead.MAX_SEGMENT_BYTES+1))
                    return MockResponse().setBody("valid")
                }
            }
            val data=lookAhead.factory.createDataSource();data.open(DataSpec(Uri.parse("$base/s0.ts")));data.close()
            repeat(2) { assertNotNull(server.takeRequest(2,TimeUnit.SECONDS)) }
            Thread.sleep(100)
            val next=lookAhead.factory.createDataSource();next.open(DataSpec(Uri.parse("$base/s1.ts")))
            val bytes=ByteArray(32);val n=next.read(bytes,0,bytes.size);next.close()
            assertEquals("valid",String(bytes,0,n))
            assertEquals("/s1.ts",server.takeRequest(2,TimeUnit.SECONDS)!!.path)
            assertNull("Oversized speculation must stop",server.takeRequest(250,TimeUnit.MILLISECONDS))
        }
    }
    @Test fun actualHlsPlaybackReducesRebuffersOnHighLatencyLink() {
        fun run(prefetch:Boolean):Int {
            var stalls=0
            withFixture { base,server,lookAhead,owner ->
                val assets=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
                server.dispatcher=object:Dispatcher() {
                    override fun dispatch(request:RecordedRequest):MockResponse {
                        val path=request.requestUrl!!.encodedPath.removePrefix("/")
                        if(path=="test.m3u8") return MockResponse().setBody(assets.open(path).bufferedReader().use { it.readText() })
                        return MockResponse().setBody(okio.Buffer().write(assets.open(path).use { it.readBytes() })).setHeadersDelay(2300,TimeUnit.MILLISECONDS)
                    }
                }
                val instrument=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                val scenario=androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
                lateinit var surface:android.view.SurfaceView
                val surfaceReady=java.util.concurrent.CountDownLatch(1)
                scenario.onActivity { activity ->
                    surface=android.view.SurfaceView(activity)
                    surface.holder.addCallback(object:android.view.SurfaceHolder.Callback {
                        override fun surfaceCreated(holder:android.view.SurfaceHolder) { surfaceReady.countDown() }
                        override fun surfaceChanged(holder:android.view.SurfaceHolder,format:Int,width:Int,height:Int) { }
                        override fun surfaceDestroyed(holder:android.view.SurfaceHolder) { }
                    })
                    activity.setContentView(surface)
                }
                assertTrue(surfaceReady.await(5,TimeUnit.SECONDS))
                lateinit var player:androidx.media3.exoplayer.ExoPlayer
                var wasReady=false
                var error:androidx.media3.common.PlaybackException?=null
                instrument.runOnMainSync {
                    // Pin the same software decoder in both measurements: this API 37 emulator's
                    // goldfish H.264 codec rejects the fixture CSD (production retries another codec).
                    val renderers=androidx.media3.exoplayer.DefaultRenderersFactory(instrument.targetContext)
                        .setMediaCodecSelector { mime,secure,tunnel -> androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(mime,secure,tunnel).filter { it.softwareOnly } }
                    player=androidx.media3.exoplayer.ExoPlayer.Builder(instrument.targetContext,renderers).setLoadControl(playbackLoadControl()).build()
                    player.setVideoSurfaceView(surface)
                    player.addListener(object:androidx.media3.common.Player.Listener {
                        override fun onPlaybackStateChanged(state:Int) {
                            if(state==androidx.media3.common.Player.STATE_READY) wasReady=true
                            if(state==androidx.media3.common.Player.STATE_BUFFERING && wasReady) stalls++
                        }
                        override fun onPlayerError(value:androidx.media3.common.PlaybackException) { error=value }
                    })
                    val factory=androidx.media3.exoplayer.hls.HlsMediaSource.Factory(if(prefetch) lookAhead.factory else OkHttpDataSource.Factory(owner))
                    if(prefetch) factory.setPlaylistParserFactory(lookAhead.parser(androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory()))
                    player.setMediaSource(factory.createMediaSource(androidx.media3.common.MediaItem.fromUri("$base/test.m3u8")))
                    player.prepare();player.play()
                }
                try {
                    val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(35)
                    var reached=false
                    while(!reached && System.nanoTime()<deadline) {
                        instrument.runOnMainSync { assertNull(error);reached=player.currentPosition>=8000 }
                        Thread.sleep(50)
                    }
                    assertTrue("Real playback must advance",reached)
                } finally { instrument.runOnMainSync { player.release() };scenario.close() }
            }
            return stalls
        }
        val baseline=run(false);val optimized=run(true)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply { putInt("baseline_rebuffers",baseline);putInt("prefetch_rebuffers",optimized) })
        assertTrue("Trace must reproduce repeated buffering",baseline>0)
        assertTrue("Look-ahead must reduce actual buffering: $baseline -> $optimized",optimized<baseline)
    }
    private fun observeThree(base:String,lookAhead:HlsLookAhead) {
        lookAhead.observe(HlsPlaylistParser().parse(Uri.parse("$base/list.m3u8"),"#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\ns0.ts\n#EXTINF:2,\ns1.ts\n#EXTINF:2,\ns2.ts\n#EXT-X-ENDLIST\n".byteInputStream()))
    }
    private fun withFixture(block:(String,MockWebServer,HlsLookAhead,MediaCallOwner)->Unit) {
        val server=MockWebServer();server.start()
        val network=Network({true},object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) })
        val owner=MediaCallOwner(network.client);val lookAhead=HlsLookAhead(OkHttpDataSource.Factory(owner),network.client)
        try { block("http://fixture.test:${server.port}",server,lookAhead,owner) }
        finally { lookAhead.close();owner.close();server.shutdown();network.client.connectionPool.evictAll() }
    }

}
