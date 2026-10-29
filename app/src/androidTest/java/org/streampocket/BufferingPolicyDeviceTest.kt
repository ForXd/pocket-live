package org.streampocket

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class BufferingPolicyDeviceTest {
    private val id=PlayerId.UNSET
    private fun params(bufferMs:Long,speed:Float=1f,rebuffer:Boolean=false,liveUs:Long=C.TIME_UNSET)=
        LoadControl.Parameters(id,Timeline.EMPTY,MediaPeriodId("trace"),0,bufferMs*1000,speed,true,rebuffer,liveUs,C.TIME_UNSET)

    @Test fun prefetchedVideoSurvivesTransientOutage() {
        val load=playbackLoadControl()
        load.onPrepared(id)
        try {
            // Start with a full reservoir, then replay 120 seconds in 100 ms steps.
            // Link delivers 4 seconds of media per second except a 25-second outage.
            // This invokes Media3's real hysteresis, not a copy of its algorithm.
            var buffer=60_000L
            var depletedTicks=0
            for(t in 0 until 1200) {
                val loading=load.shouldContinueLoading(params(buffer))
                if(loading && t !in 360 until 610) buffer+=400
                if(buffer<100) depletedTicks++
                buffer=(buffer-100).coerceAtLeast(0)
            }
            assertEquals("Buffered video exhausted during the transient outage (100 ms ticks)",0,depletedTicks)
        } finally { load.onReleased(id) }
    }

    @Test fun startupRebufferSpeedAndMemoryBudgetRemainBounded() {
        val load=playbackLoadControl()
        load.onPrepared(id)
        try {
            assertFalse(load.shouldStartPlayback(params(500)))
            assertTrue(load.shouldStartPlayback(params(1000)))
            assertFalse(load.shouldStartPlayback(params(3000,rebuffer=true)))
            assertTrue(load.shouldStartPlayback(params(4000,rebuffer=true)))
            assertFalse(load.shouldStartPlayback(params(4000,speed=2f,rebuffer=true)))
            assertTrue(load.shouldStartPlayback(params(8000,speed=2f,rebuffer=true)))
            assertTrue(load.shouldStartPlayback(params(1500,rebuffer=true,liveUs=3_000_000)))
            val allocator=load.allocator
            val chunks=List(PlaybackPolicy.TARGET_BYTES/allocator.individualAllocationLength) { allocator.allocate() }
            assertFalse("Byte budget must stop prefetch even below the time target",load.shouldContinueLoading(params(10_000)))
            chunks.forEach { allocator.release(it) }
        } finally { load.onReleased(id) }
    }
}
