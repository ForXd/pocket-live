package org.streampocket

import android.os.Handler
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// Explicit public test URL argument only; never installs an unverified default media source.
// This checks real decoder first-frame output and advancing position, not a mock Player.
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class PlaybackDeviceTest {
    @Test fun licensedVideoRendersAndAdvances() {
        val args=InstrumentationRegistry.getArguments()
        val url=args.getString("licensedVideoUrl") ?: throw AssertionError("Pass a verified licensedVideoUrl; playback acceptance cannot silently skip")
        require(validUrl(url)!=null)
        val instrument=InstrumentationRegistry.getInstrumentation()
        val started=android.os.SystemClock.elapsedRealtime()
        val firstFrameMs=java.util.concurrent.atomic.AtomicLong(-1)
        val frame=CountDownLatch(1); val progressed=CountDownLatch(1)
        val failure=AtomicReference<String?>(null)
        var player:ExoPlayer?=null
        val handler=Handler(Looper.getMainLooper())
        var surface:android.view.Surface?=null
        var texture:android.graphics.SurfaceTexture?=null
        instrument.runOnMainSync {
            texture=android.graphics.SurfaceTexture(false)
            surface=android.view.Surface(texture)
            player=ExoPlayer.Builder(instrument.targetContext).build().also { p ->
                p.setVideoSurface(surface)
                p.addListener(object:Player.Listener {
                    override fun onRenderedFirstFrame() { firstFrameMs.compareAndSet(-1,android.os.SystemClock.elapsedRealtime()-started); frame.countDown() }
                    override fun onPlayerError(error:PlaybackException) { failure.set("errorCode=${error.errorCode}"); frame.countDown(); progressed.countDown() }
                })
                p.setMediaItem(MediaItem.fromUri(url));p.prepare();p.play()
                handler.postDelayed(object:Runnable {
                    override fun run() {
                        if(p.currentPosition>1500) progressed.countDown() else handler.postDelayed(this,200)
                    }
                },200)
            }
        }
        try {
            assertTrue("No decoded video frame within 30s",frame.await(30,TimeUnit.SECONDS))
            assertNull(failure.get())
            assertTrue("Playback did not advance",progressed.await(10,TimeUnit.SECONDS))
            assertNull(failure.get())
            instrument.sendStatus(0,android.os.Bundle().apply {
                putLong("actual_first_frame_ms",firstFrameMs.get())
                putLong("actual_progress_check_elapsed_ms",android.os.SystemClock.elapsedRealtime()-started)
                putString("measurement_type","actual-decoder-public-authorized-media")
            })
        } finally { instrument.runOnMainSync { handler.removeCallbacksAndMessages(null);player?.release();surface?.release();texture?.release() } }
    }
}
