// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class PlaybackTrialReleaseDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val localDns = object : Dns {
        override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
    }

    @Test fun cancelAtFirstRenderedFrameReleasesCodecPlayerSurfacesAndThread() = runBlocking {
        val server = MockWebServer().apply { start() }
        val network = Network({ true }, localDns)
        val fixture = mediaFixtureBytes("test.mp4")
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(fixture))
        }
        val episode = Episode("1", "http://trial.test:${server.port}/clip.mp4")
        val players = mutableListOf<WeakReference<ExoPlayer>>()
        val collectedPlayers = ReferenceQueue<ExoPlayer>()
        var releasedTotal = 0
        var codecTotal = 0
        try {
            for (cancelAtFrame in listOf(true, true, false)) {
                val threads = mutableListOf<WeakReference<Thread>>()
                val outputs = mutableListOf<WeakReference<Surface>>()
                val textures = mutableListOf<WeakReference<SurfaceTexture>>()
                val counters = mutableListOf<DecoderCounters>()
                val released = AtomicInteger()
                val frames = AtomicInteger()
                lateinit var run: Deferred<ProbeResult>
                run = async(start = CoroutineStart.LAZY) {
                    runPlaybackTrial(context, network, episode, false, emptySet(), false) { player, surface, texture ->
                        players.add(WeakReference(player, collectedPlayers))
                        threads.add(WeakReference(Thread.currentThread()))
                        outputs.add(WeakReference(surface))
                        textures.add(WeakReference(texture))
                        player.addAnalyticsListener(object : AnalyticsListener {
                            override fun onVideoEnabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
                                counters.add(decoderCounters)
                            }
                            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                                frames.incrementAndGet()
                                if (cancelAtFrame) run.cancel()
                            }
                            override fun onPlayerReleased(eventTime: AnalyticsListener.EventTime) { released.incrementAndGet() }
                        })
                    }
                }
                run.start()
                run.join()
                assertTrue("A real video frame must precede cancellation or success", frames.get() > 0)
                if (cancelAtFrame) assertTrue(run.isCancelled) else assertEquals(ProbeStatus.READY, run.await().status)
                assertEquals("Every attempted player released, including codec retries", outputs.size, released.get())
                assertTrue(outputs.all { it.get()?.isValid != true })
                assertTrue(textures.all { it.get()?.isReleased != false })
                assertTrue(threads.all { it.get()?.isAlive != true })
                counters.forEach { it.ensureUpdated() }
                assertTrue(counters.sumOf { it.decoderInitCount } > 0)
                assertEquals(counters.sumOf { it.decoderInitCount }, counters.sumOf { it.decoderReleaseCount })
                assertEquals(0, MediaCallOwner.active.get())
                releasedTotal += released.get()
                codecTotal += counters.sumOf { it.decoderReleaseCount }
            }
            // All trial coroutines, surfaces and application loopers have finished.
            // The test retains only weak player references from each codec attempt.
            val instrument = InstrumentationRegistry.getInstrumentation()
            instrument.waitForIdleSync()
            val deadline = SystemClock.elapsedRealtime() + 8_000
            var collected = 0
            // Calling weak.get() here pins its result in the test thread's ART frame during GC.
            // Keep the reference objects themselves reachable and count notifications only.
            while (collected < players.size && SystemClock.elapsedRealtime() < deadline) {
                System.gc()
                System.runFinalization()
                delay(100)
                while (collectedPlayers.poll() != null) collected++
            }
            val retained = players.size - collected
            instrument.sendStatus(0, Bundle().apply {
                putInt("trial_players_created", players.size)
                putInt("trial_players_released", releasedTotal)
                putInt("trial_codecs_released", codecTotal)
                putInt("trial_players_retained", retained)
                putString("stream", "\nTrial players: created=${players.size}, released=$releasedTotal, retained=$retained; released codecs=$codecTotal\n")
            })
            assertEquals("Released trial players should be collectible", 0, retained)
        } finally {
            network.close()
            server.shutdown()
        }
    }

    @Test fun cancelDuringFormatReadStopsHttpCall() = runBlocking {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody("#EXTM3U\n").setBodyDelay(3, TimeUnit.SECONDS))
            start()
        }
        val network = Network({ true }, localDns)
        try {
            val sniff = async(Dispatchers.Default) { PlaybackProbe(network).mediaType("http://trial.test:${server.port}/clip") }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            sniff.cancelAndJoin()
            withTimeout(1_500) {
                while (network.client.dispatcher.runningCallsCount() > 0) delay(10)
            }
            assertTrue(sniff.isCancelled)
        } finally {
            network.close()
            server.shutdown()
        }
    }

    @Test fun undeliveredResponseIsClosedWhenLoaderOrSessionCancels() {
        val closedBodies = AtomicInteger()
        val server = MockWebServer().apply { start() }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val body = checkNotNull(response.body)
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = body.source()
                override fun close() { closedBodies.incrementAndGet(); body.close() }
            }).build()
        }.build()
        try {
            for (i in 1..2) {
                server.enqueue(MockResponse().setBody("unread media bytes"))
                val owner = MediaCallOwner(client)
                try {
                    val call = owner.newCall(Request.Builder().url(server.url("/media")).build())
                    call.execute().use {
                        if (i == 1) call.cancel() else owner.close()
                        assertEquals("Owner must close the unread response", i, closedBodies.get())
                    }
                    assertEquals("No duplicate close after cancellation", i, closedBodies.get())
                } finally { owner.close() }
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }
}
