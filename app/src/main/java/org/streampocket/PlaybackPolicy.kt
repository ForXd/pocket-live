// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

data class PlaybackHealth(val buffering:Boolean=false,val slow:Boolean=false,val bufferedSeconds:Long=0)
object PlaybackPolicy {
    // Refill before the reservoir falls into the range of common network stalls.
    // Keep a 10-second hysteresis window and the existing byte budget.
    const val MIN_BUFFER_MS=50_000
    const val MAX_BUFFER_MS=60_000
    const val START_MS=1000
    const val REBUFFER_MS=4000
    const val TARGET_BYTES=32*1024*1024
    const val SLOW_MS=8000L
    fun retryDelay(code:Int?,count:Int):Long? {
        if(count>=3 || (code!=null && code in 400..499 && code !in listOf(408,429))) return null
        return (count.coerceAtLeast(1)*750L).coerceAtMost(1500)
    }
}
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class PlaybackLoadPolicy:DefaultLoadErrorHandlingPolicy(2) {
    override fun getRetryDelayMsFor(info:LoadErrorHandlingPolicy.LoadErrorInfo):Long {
        if(super.getRetryDelayMsFor(info)==C.TIME_UNSET) return C.TIME_UNSET
        val http=generateSequence(info.exception as Throwable?) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        return PlaybackPolicy.retryDelay(http?.responseCode,info.errorCount) ?: C.TIME_UNSET
    }
}

/** Shared production factory, also exercised by deterministic network-trace tests. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
internal fun playbackLoadControl() = androidx.media3.exoplayer.DefaultLoadControl.Builder()
    .setBufferDurationsMs(PlaybackPolicy.MIN_BUFFER_MS,PlaybackPolicy.MAX_BUFFER_MS,PlaybackPolicy.START_MS,PlaybackPolicy.REBUFFER_MS)
    .setTargetBufferBytes(PlaybackPolicy.TARGET_BYTES)
    .setPrioritizeTimeOverSizeThresholds(false)
    .build()
