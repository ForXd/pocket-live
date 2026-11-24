// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal typealias TrialPlayerCreated = (ExoPlayer, Surface, SurfaceTexture) -> Unit

/** The caller limits simultaneous trials. Codec startup and teardown stay off the UI thread. */
internal suspend fun runPlaybackTrial(context:Context,network:Network,episode:Episode,automaticAds:Boolean,
    adPaths:Set<String>,smoothMode:Boolean,onPlayerCreated:TrialPlayerCreated?=null):ProbeResult {
    val failed=ProbeResult(ProbeStatus.FAILED)
    if(validUrl(episode.url)==null) return failed
    return try {
        withTimeoutOrNull(ProbePolicy.MEDIA_TIMEOUT_MS) {
            val item=playbackMediaItem(network,episode)
            withContext(Dispatchers.IO) {
                val worker=HandlerThread("PocketPlaybackTrial").apply { start() }
                try {
                    withContext(Handler(worker.looper).asCoroutineDispatcher()) {
                        playTrialMedia(context.applicationContext,network,episode,item,automaticAds,adPaths,smoothMode,onPlayerCreated)
                    }
                } finally {
                    worker.quitSafely()
                    worker.join()
                }
            }
        } ?: failed
    } catch(e:CancellationException) { throw e }
      catch(_:Exception) { currentCoroutineContext().ensureActive();failed }
}

private suspend fun playTrialMedia(context:Context,network:Network,episode:Episode,item:MediaItem,
    automaticAds:Boolean,adPaths:Set<String>,smoothMode:Boolean,onPlayerCreated:TrialPlayerCreated?):ProbeResult {
    val excluded=mutableSetOf<String>()
    while(true) {
        currentCoroutineContext().ensureActive()
        try {
            return tryDecoder(context,network,episode,item,automaticAds,adPaths,smoothMode,excluded.toSet(),onPlayerCreated)
        } catch(error:PlaybackException) {
            val failed=error.failedDecoderName()
            if(failed==null || excluded.size>=4 || !excluded.add(failed)) throw error
        }
    }
}

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
private suspend fun tryDecoder(context:Context,network:Network,episode:Episode,item:MediaItem,
    automaticAds:Boolean,adPaths:Set<String>,smoothMode:Boolean,excluded:Set<String>,
    onPlayerCreated:TrialPlayerCreated?):ProbeResult {
    val completed=CompletableDeferred<ProbeResult>()
    val failed=ProbeResult(ProbeStatus.FAILED)
    val calls=MediaCallOwner(network.client)
    var player:ExoPlayer?=null
    var texture:SurfaceTexture?=null
    var surface:Surface?=null
    var events:Player.Listener?=null
    var outputObserver:PlaybackOutputObserver?=null
    try {
        val upstream=OkHttpDataSource.Factory(calls)
        val policy=PlaybackLoadPolicy()
        val factory=DefaultMediaSourceFactory(upstream).setLoadErrorHandlingPolicy(policy)
        val source=playbackMediaSource(item,factory,upstream,policy,automaticAds,adPaths)
        val tracks=DefaultTrackSelector(context).apply { configurePlaybackChannels() }
        val renderers=playbackRenderers(context,excluded) { failure ->
            android.util.Log.e("PlaybackTrial","Final renderer cleanup failed",failure)
        }
        val load=DefaultLoadControl.Builder().setBufferDurationsMs(1500,4000,PlaybackPolicy.START_MS,1500)
            .setTargetBufferBytes(4*1024*1024).build()
        val p=ExoPlayer.Builder(context,renderers).setLooper(checkNotNull(Looper.myLooper())).setTrackSelector(tracks).setLoadControl(load).setMediaSourceFactory(factory).build()
        player=p
        p.configurePlaybackVideo(smoothMode)
        p.volume=0f
        p.setAudioAttributes(AudioAttributes.DEFAULT,false)
        val output=SurfaceTexture(false)
        texture=output
        surface=Surface(output)
        p.setVideoSurface(surface)
        fun success(video:Boolean):ProbeResult {
            val format=if(video) p.videoFormat else p.audioFormat
            val mime=when {
                source is HlsMediaSource -> MimeTypes.APPLICATION_M3U8
                else -> format?.containerMimeType ?: format?.sampleMimeType
            }
            val size=p.videoSize
            val resolution=if(video && size.width>0 && size.height>0) "${size.width}×${size.height}" else null
            return ProbeResult(ProbeStatus.READY,resolution,episode.copy(mimeType=mime),mime)
        }
        onPlayerCreated?.invoke(p, surface, output)
        events=object:Player.Listener {
            override fun onPlayerError(error:PlaybackException) { completed.completeExceptionally(error) }
            override fun onPlaybackStateChanged(playbackState:Int) {
                if(playbackState==Player.STATE_ENDED) completed.complete(failed)
            }
        }.also(p::addListener)
        outputObserver=PlaybackOutputObserver(p) { video -> completed.complete(success(video)) }.also(p::addAnalyticsListener)
        p.setMediaSource(source)
        p.prepare()
        p.play()
        return completed.await()
    } finally {
        try {
            try {
                calls.close()
                player?.let { p ->
                    events?.let(p::removeListener)
                    outputObserver?.let(p::removeAnalyticsListener)
                }
            } finally { player?.release() }
        } finally {
            try { surface?.release() } finally { texture?.release() }
        }
    }
}

/** A selected track and STATE_READY alone do not prove that this device produced media output. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
internal class PlaybackOutputObserver(private val player:ExoPlayer,private val onOutput:(video:Boolean)->Unit):AnalyticsListener {
    private var confirmed=false
    fun reset() { confirmed=false }
    private fun confirm(video:Boolean) {
        if(confirmed) return
        confirmed=true
        onOutput(video)
    }
    override fun onRenderedFirstFrame(eventTime:AnalyticsListener.EventTime,output:Any,renderTimeMs:Long) {
        confirm(video=true)
    }
    override fun onAudioPositionAdvancing(eventTime:AnalyticsListener.EventTime,playoutStartSystemTimeMs:Long) {
        val tracks=player.currentTracks
        if(tracks.isTypeSelected(C.TRACK_TYPE_AUDIO) && !tracks.containsType(C.TRACK_TYPE_VIDEO))
            confirm(video=false)
    }
}
