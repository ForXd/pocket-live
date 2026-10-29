// SPDX-License-Identifier: AGPL-3.0-or-later
// Modified 2026-10-24: one owner for player, view binding and sampling job.
package org.streampocket

import android.content.Context
import androidx.media3.common.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*

/** All methods and callbacks are main-thread confined. No Activity is stored here. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackController(
    private val context: Context,
    private val network: Network,
    private val session: Playback,
    private val checkpoint: (Long, Boolean) -> Unit,
    private val intent: (Boolean) -> Unit,
    private val playing: (Boolean) -> Unit,
    private val failed: (String) -> Unit,
    private val cleanupFailed: (String) -> Unit,
    private val videoSize: (Int,Int) -> Unit = { _,_ -> },
    private val automaticAds:Boolean = false,
    private val adPaths:Set<String> = emptySet(),
    private val filterReport:(String)->Unit = {},
    private val health:(PlaybackHealth)->Unit = {},
    private var smoothMode:Boolean = false,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaCalls:MediaCallOwner?=null
    private var sample: Job? = null
    private var recovery: Job? = null
    private var player: ExoPlayer? = null
    private var view: PlayerView? = null
    private var listener: Player.Listener? = null
    private var liveMedia = session.video.source.kind == SourceKind.LIVE
    private var position = resumePosition(liveMedia,session.position)
    @Volatile private var hlsEdit=HlsEdit("")
    private var initialSeek=true
    private var autoStart = session.autoPlay
    private var closed = false
    private var failedDecoder: String? = null
    private val excludedDecoders=linkedSetOf<String>()
    fun attach(next: PlayerView) {
        if(view !== next) view?.player = null
        view = next
        next.player = player
    }
    fun detach(old: PlayerView) { old.player = null; if(view === old) view = null }
    fun start() {
        if(closed || player != null) return
        try {
            val load = playbackLoadControl()
            val calls=MediaCallOwner(network.client);mediaCalls=calls
            val dataSource=OkHttpDataSource.Factory(calls)
            val loadPolicy=PlaybackLoadPolicy()
            val factory = DefaultMediaSourceFactory(dataSource).setLoadErrorHandlingPolicy(loadPolicy)
            hlsEdit=HlsEdit("");initialSeek=true
            val rejected=excludedDecoders.toSet() // Codec selection runs on the playback thread.
            val diagnosticOwner=++PlaybackMetrics.nextDiagnosticOwner
            val reportCleanup=cleanupFailed // AppModel-only callback, never retain the screen/controller.
            val renderers=object:DefaultRenderersFactory(context.applicationContext) {
                override fun createRenderers(eventHandler:android.os.Handler,videoListener:androidx.media3.exoplayer.video.VideoRendererEventListener,audioListener:androidx.media3.exoplayer.audio.AudioRendererEventListener,textOutput:androidx.media3.exoplayer.text.TextOutput,metadataOutput:androidx.media3.exoplayer.metadata.MetadataOutput):Array<Renderer> {
                    val delegates=super.createRenderers(eventHandler,videoListener,audioListener,textOutput,metadataOutput)
                    val inject:(Renderer)->Unit = { renderer ->
                        if(BuildConfig.DEBUG && renderer.trackType==C.TRACK_TYPE_VIDEO && ReleaseFaultInjection.pending.compareAndSet(true,false)) {
                            ReleaseFaultInjection.triggered.incrementAndGet()
                            ReleaseFaultInjection.videoState=renderer.state
                            ReleaseFaultInjection.audioState=delegates.firstOrNull { it.trackType==C.TRACK_TYPE_AUDIO }?.state ?: -1
                            PlaybackMetrics.recordRelease("injected-video-disable videoState=${ReleaseFaultInjection.videoState} audioState=${ReleaseFaultInjection.audioState}")
                            throw InjectedVideoDisableFailure()
                        }
                    }
                    if(BuildConfig.DEBUG && ReleaseFaultInjection.useOriginalRelease) return delegates.map { delegate ->
                        object:ForwardingRenderer(delegate) { override fun disable() { super.disable();inject(delegate) } }
                    }.toTypedArray()
                    return RendererReleaseGroup(delegates,inject) { failure ->
                        PlaybackMetrics.cleanupFailures++
                        PlaybackMetrics.recordRelease("renderer-cleanup-failure class=${failure.javaClass.name} suppressed=${failure.suppressed.size}")
                        androidx.media3.common.util.Log.e("RendererCleanup","Final renderer cleanup failed",failure)
                        // Report explicitly, but allow Media3 to finish load-control/track-selector/
                        // audio-focus cleanup after the renderer loop. Do not retain Throwable.
                        android.os.Handler(android.os.Looper.getMainLooper()).post { reportCleanup("播放器资源清理出现异常，请重新打开播放") }
                    }.wrapped
                }
                override fun buildAudioSink(context:Context,enableFloatOutput:Boolean,enableAudioTrackPlaybackParams:Boolean):androidx.media3.exoplayer.audio.AudioSink? {
                    val sink=super.buildAudioSink(context,enableFloatOutput,enableAudioTrackPlaybackParams) ?: return null
                    return if(BuildConfig.DEBUG && PlaybackMetrics.diagnosticEnabled) DiagnosticAudioSink(sink,diagnosticOwner) else sink
                }
            }.setEnableDecoderFallback(true)
                .setMediaCodecSelector { mime,secure,tunnel ->
                    androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(mime,secure,tunnel)
                        .filterNot { it.name in rejected }
                }
            // API32+ Spatializer listeners retained a released player in the API37 heap.
            // This phone player does not offer spatial audio: prefer stereo without registering
            // the dynamic spatializer listener. Keep the default fallback for multichannel-only media.
            val tracks=if(BuildConfig.DEBUG && PlaybackMetrics.diagnosticEnabled) DiagnosticTrackSelector(context.applicationContext,diagnosticOwner) else DefaultTrackSelector(context.applicationContext)
            if(android.os.Build.VERSION.SDK_INT>=32) tracks.parameters=tracks.buildUponParameters()
                .setConstrainAudioChannelCountToDeviceCapabilities(false).setMaxAudioChannelCount(2).build()
            val p = ExoPlayer.Builder(context.applicationContext,renderers).setTrackSelector(tracks)
                .setLoadControl(load).setMediaSourceFactory(factory).build()
            player = p // Transfer ownership before any operation that can fail.
            setSmoothMode(smoothMode)
            (tracks as? DiagnosticTrackSelector)?.playerRef=java.lang.ref.WeakReference(p)
            PlaybackMetrics.recordRelease("player-created owner=$diagnosticOwner playerIdentity=${System.identityHashCode(p)}")
            PlaybackMetrics.created++
            val events = object : Player.Listener {
                override fun onRenderedFirstFrame() { if(player===p && !closed) PlaybackMetrics.frames++ }
                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    // The initial masking timeline precedes playlist parsing. Wait for the
                    // real source timeline so HLS cuts exist before translating resume time.
                    if(player===p && !closed && !timeline.isEmpty && initialSeek &&
                        reason==Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE && !timeline.getWindow(0,Timeline.Window()).isPlaceholder) {
                        initialSeek=false
                        if(!p.isCurrentMediaItemLive) p.seekTo(hlsEdit.filteredPosition(position))
                        filterReport(if(hlsEdit.removed>0) "已移除 ${hlsEdit.removed} 个疑似广告切片 · ${hlsEdit.removedMs/1000} 秒" else "未移除切片（无匹配或不满足安全条件）")
                    }
                    // Some VOD APIs also return live media. Detect it once without resetting
                    // the live edge on each playlist refresh or disturbing an intentional pause.
                    if(player===p && !closed && !timeline.isEmpty && p.isCurrentMediaItemLive && !liveMedia) {
                        liveMedia=true;position=0;p.seekToDefaultPosition()
                    }
                }
                override fun onVideoSizeChanged(size:VideoSize) { if(player===p && !closed) videoSize(size.width,size.height) }
                override fun onPlayWhenReadyChanged(value: Boolean, reason: Int) { if(player===p && !closed) intent(value) }
                override fun onPlayerError(error: PlaybackException) {
                    if(player!==p || closed) return
                    PlaybackMetrics.errorDiagnostic=playbackErrorDiagnostic(error)
                    failedDecoder=if(error.errorCode==PlaybackException.ERROR_CODE_DECODING_FAILED)
                        generateSequence(error as Throwable?) { it.cause }.take(6)
                            .filterIsInstance<androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException>()
                            .firstOrNull()?.codecInfo?.name else null
                    if(failedDecoder!=null && excludedDecoders.size<4) {
                        val shouldPlay=p.playWhenReady
                        recovery?.cancel()
                        recovery=scope.launch {
                            yield() // Leave the Player callback before replacing its owner.
                            recovery=null
                            if(player===p && !closed) recoverDecoder(shouldPlay)
                        }
                    } else failed("播放失败（${error.errorCode}），可重试或快速换源")
                }
                override fun onIsPlayingChanged(value: Boolean) { if(player===p && !closed) playing(value) }
                override fun onPlaybackStateChanged(state: Int) {
                    if(player===p && !closed && state == Player.STATE_ENDED) { position=0; checkpoint(0,false) }
                }
            }
            listener=events; p.addListener(events)
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_NEVER).build(),true)
            p.setHandleAudioBecomingNoisy(true)
            view?.player=p
            val item=MediaItem.fromUri(session.episode.url)
            if((automaticAds || adPaths.isNotEmpty()) && !liveMedia && androidx.media3.common.util.Util.inferContentType(android.net.Uri.parse(session.episode.url))==C.CONTENT_TYPE_HLS) {
                val hls=androidx.media3.exoplayer.hls.HlsMediaSource.Factory(dataSource).setLoadErrorHandlingPolicy(loadPolicy).setPlaylistParserFactory(SafeHlsParserFactory(session.episode.url,adPaths,automaticAds) { hlsEdit=it })
                p.setMediaSource(hls.createMediaSource(item))
            } else p.setMediaItem(item)
            if(liveMedia) p.seekToDefaultPosition() else p.seekTo(position)
            p.prepare()
            p.playWhenReady=autoStart; autoStart=false
            sample=scope.launch {
                PlaybackMetrics.samplers++
                var bufferingSince=0L
                var lastCheckpoint=0L
                var lastHealth:PlaybackHealth?=null
                try {
                    while(isActive && player===p) {
                        delay(1000)
                        val now=android.os.SystemClock.elapsedRealtime()
                        val waiting=p.playbackState==Player.STATE_BUFFERING && p.playWhenReady
                        if(!waiting) bufferingSince=0L else if(bufferingSince==0L) bufferingSince=now
                        val status=PlaybackHealth(waiting,waiting && now-bufferingSince>=PlaybackPolicy.SLOW_MS,(p.totalBufferedDuration/1000).coerceAtLeast(0))
                        if(status!=lastHealth) { health(status);lastHealth=status }
                        if(p.isPlaying && now-lastCheckpoint>=5000) {
                            lastCheckpoint=now
                            PlaybackMetrics.samples++
                            position=resumePosition(liveMedia,hlsEdit.originalPosition(p.currentPosition))
                            checkpoint(position,p.playWhenReady)
                        }
                    }
                } finally { PlaybackMetrics.samplers-- }
            }
        } catch(e: Exception) { stop(false); failed("播放器初始化失败，可重试或快速换源") }
    }
    fun checkpointNow() {
        val p=player ?: return
        if(closed || initialSeek) return
        position=resumePosition(liveMedia,hlsEdit.originalPosition(p.currentPosition))
        checkpoint(position,p.playWhenReady)
    }
    fun setSmoothMode(enabled:Boolean) {
        smoothMode=enabled
        val p=player ?: return
        p.trackSelectionParameters=p.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(if(enabled) 1280 else Int.MAX_VALUE,if(enabled) 720 else Int.MAX_VALUE)
            .setMaxVideoBitrate(if(enabled) 2_000_000 else Int.MAX_VALUE).build()
    }
    fun seekRelative(delta:Long):String {
        val p=player ?: return "当前不可跳转"
        if(closed || p.currentTimeline.isEmpty || !p.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return "当前不可跳转"
        val target=boundedSeek(p.currentPosition,delta,p.duration,p.isCurrentMediaItemSeekable) ?: return "当前直播或媒体不可跳转"
        if(target!=p.currentPosition) p.seekTo(target)
        return "${if(delta<0) "后退" else "前进"} · ${target/60000}:${(target/1000%60).toString().padStart(2,'0')}"
    }
    fun retry() {
        if(closed) return
        val decoder=failedDecoder
        if(decoder!=null && excludedDecoders.size<4) {
            recoverDecoder(true)
        } else if(player == null) { autoStart=true;start() } else {
            val p=player ?: return
            if(p.playerError==null && p.playbackState==Player.STATE_BUFFERING) {
                stop(false);autoStart=true;start();return
            }
            // One recovery per explicit user action, no timer/reconnect loop or new player.
            if(liveMedia || p.isCurrentMediaItemLive ||
                p.playerError?.errorCode==PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                liveMedia=true;position=0;p.seekToDefaultPosition()
            }
            p.prepare();p.play()
        }
    }
    private fun recoverDecoder(play: Boolean) {
        val decoder=failedDecoder ?: return
        excludedDecoders.add(decoder);failedDecoder=null
        PlaybackMetrics.decoderFallbacks++
        stop(false);autoStart=play;start()
    }
    fun stop(rotating: Boolean) {
        (view as? GesturePlayerView)?.cancelGestures()
        recovery?.cancel();recovery=null
        sample?.cancel(); sample=null // Synchronous cancellation before touching the player.
        val p=player
        player=null
        view?.player=null
        mediaCalls?.close();mediaCalls=null
        health(PlaybackHealth())
        try {
            if(p != null) {
                position=if(p.playbackState == Player.STATE_ENDED) 0 else if(initialSeek) position else resumePosition(liveMedia,hlsEdit.originalPosition(p.currentPosition))
                autoStart=rotating && p.playWhenReady
                checkpoint(position,autoStart)
            }
        } finally {
            try {
                if(p != null) {
                    listener?.let(p::removeListener)
                    try { p.clearVideoSurface() } finally {
                        val releaseStart=android.os.SystemClock.elapsedRealtime()
                        val releaseObserver=object:Player.Listener {
                            override fun onPlayerError(error:PlaybackException) {
                                if(error.errorCode==PlaybackException.ERROR_CODE_TIMEOUT) PlaybackMetrics.releaseTimeouts++
                            }
                        }
                        p.addListener(releaseObserver)
                        PlaybackMetrics.recordRelease("player-release-enter identity=${System.identityHashCode(p)}")
                        try { p.release(); PlaybackMetrics.released++ }
                        catch(e:Exception) { PlaybackMetrics.releaseFailures++;throw e }
                        finally {
                            p.removeListener(releaseObserver)
                            PlaybackMetrics.lastReleaseElapsed=android.os.SystemClock.elapsedRealtime()
                            PlaybackMetrics.maxReleaseMs=maxOf(PlaybackMetrics.maxReleaseMs,PlaybackMetrics.lastReleaseElapsed-releaseStart)
                            PlaybackMetrics.recordRelease("player-release-exit identity=${System.identityHashCode(p)} duration=${PlaybackMetrics.lastReleaseElapsed-releaseStart}")
                        }
                    }
                }
            } finally { listener=null; playing(false) }
        }
    }
    fun close(rotating: Boolean) {
        try { stop(rotating) } finally { closed=true; view=null;failedDecoder=null;excludedDecoders.clear();scope.cancel() }
    }
}

/** Bounded counters, no object references, URLs, logs or durable storage. */
internal object PlaybackMetrics {
    @Volatile var cleanupFailures=0L
    var created=0L; var released=0L; var samples=0L; var samplers=0; var frames=0L
    var errorDiagnostic=""
    var decoderFallbacks=0L
    var releaseTimeouts=0L;var releaseFailures=0L;var maxReleaseMs=0L;var lastReleaseElapsed=0L
    var nextDiagnosticOwner=0L
    @Volatile var diagnosticEnabled=false
    private val releaseEvents=java.util.ArrayDeque<String>()
    private var droppedReleaseEvents=0
    @Synchronized fun recordRelease(value:String) {
        if(!BuildConfig.DEBUG || !diagnosticEnabled) return
        releaseEvents.addLast("${android.os.SystemClock.elapsedRealtime()} ${Thread.currentThread().name} $value".take(2048))
        while(releaseEvents.size>1024) { releaseEvents.removeFirst();droppedReleaseEvents++ }
    }
    @Synchronized fun releaseEvidence()="dropped_events=$droppedReleaseEvents\n"+releaseEvents.joinToString("\n")
}

/** Opt-in test observation through the supported forwarding API; release behavior is unchanged. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
internal class DiagnosticAudioSink(sink:androidx.media3.exoplayer.audio.AudioSink,val diagnosticOwner:Long):androidx.media3.exoplayer.audio.ForwardingAudioSink(sink) {
    private val diagnosticSink=sink
    private val sinkIdentity=System.identityHashCode(sink)
    @Volatile private var releaseEntered=false
    private val observed=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    init { PlaybackMetrics.recordRelease("sink-created owner=$diagnosticOwner sinkIdentity=$sinkIdentity") }
    private fun probe(method:String) {
        if(observed.add(method) || releaseEntered) PlaybackMetrics.recordRelease("sink-$method owner=$diagnosticOwner afterRelease=$releaseEntered stack="+Thread.currentThread().stackTrace.drop(2).take(10).joinToString(" <- "))
    }
    override fun supportsFormat(format:Format):Boolean { probe("supportsFormat");return super.supportsFormat(format) }
    override fun getFormatSupport(format:Format):Int { probe("getFormatSupport");return super.getFormatSupport(format) }
    override fun configure(inputFormat:Format,specifiedBufferSize:Int,outputChannels:IntArray?) { probe("configure");super.configure(inputFormat,specifiedBufferSize,outputChannels) }
    override fun release() {
        releaseEntered=true
        PlaybackMetrics.recordRelease("sink-release-enter owner=$diagnosticOwner sinkIdentity=$sinkIdentity")
        receiverSnapshot("before")
        try { super.release();receiverSnapshot("after-return");PlaybackMetrics.recordRelease("sink-release-success owner=$diagnosticOwner") }
        catch(e:Exception) { receiverSnapshot("after-exception");PlaybackMetrics.recordRelease("sink-release-exception owner=$diagnosticOwner class=${e.javaClass.name}");throw e }
    }
    // Read-only private-field observation for the pinned 1.6.1 debug diagnosis. Never mutate
    // registrations or retain the receiver. These are release-boundary snapshots, not hooks
    // inside unregister(). Release builds cannot construct this diagnostic wrapper.
    private fun receiverSnapshot(boundary:String) {
        if(!BuildConfig.DEBUG || !PlaybackMetrics.diagnosticEnabled) return
        try {
            val field=androidx.media3.exoplayer.audio.DefaultAudioSink::class.java.getDeclaredField("audioCapabilitiesReceiver").apply { isAccessible=true }
            val receiver=field.get(diagnosticSink)
            if(receiver==null) PlaybackMetrics.recordRelease("receiver-$boundary owner=$diagnosticOwner null")
            else {
                val registered=receiver.javaClass.getDeclaredField("registered").apply { isAccessible=true }.getBoolean(receiver)
                val capabilities=receiver.javaClass.getDeclaredField("audioCapabilities").apply { isAccessible=true }.get(receiver)
                PlaybackMetrics.recordRelease("receiver-$boundary owner=$diagnosticOwner receiverIdentity=${System.identityHashCode(receiver)} registered=$registered capabilitiesNull=${capabilities==null}")
            }
        } catch(e:Exception) { PlaybackMetrics.recordRelease("receiver-$boundary owner=$diagnosticOwner unavailable=${e.javaClass.name}") }
    }
}

/** Bounded technical fields only: no exception objects, URLs, headers or arbitrary messages. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun playbackErrorDiagnostic(error: PlaybackException): String {
    val format=(error as? ExoPlaybackException)?.rendererFormat
    val causes=generateSequence(error as Throwable?) { it.cause }.take(6).map {
        when(it) {
            is android.media.MediaCodec.CodecException -> "${it.javaClass.simpleName}:${it.diagnosticInfo}"
            is androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException -> "${it.javaClass.simpleName}:${it.codecInfo?.name}:${it.diagnosticInfo}"
            else -> it.javaClass.simpleName
        }
    }.joinToString(" > ")
    return "code=${error.errorCode};mime=${format?.sampleMimeType};codecs=${format?.codecs};size=${format?.width}x${format?.height};causes=$causes".take(1024)
}

/** Debug-only observer; does not keep a player alive and never writes private fields. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
internal class DiagnosticTrackSelector(context:Context,private val owner:Long):DefaultTrackSelector(context) {
    var playerRef:java.lang.ref.WeakReference<ExoPlayer>?=null
    override fun release() {
        super.release()
        if(!BuildConfig.DEBUG || !PlaybackMetrics.diagnosticEnabled) return
        try {
            fun field(target:Any,type:Class<*>,name:String):Any?=type.getDeclaredField(name).apply { isAccessible=true }.get(target)
            val base=androidx.media3.exoplayer.trackselection.TrackSelector::class.java
            val listenerNull=field(this,base,"listener")==null
            val bandwidthNull=field(this,base,"bandwidthMeter")==null
            val p=playerRef?.get()
            val internal=p?.let { field(it,it.javaClass,"internalPlayer") }
            val focus=internal?.let { field(it,it.javaClass,"audioFocusManager") }
            val controlNull=focus?.let { field(it,it.javaClass,"playerControl")==null }
            val focusState=focus?.let { field(it,it.javaClass,"audioFocusState") }
            PlaybackMetrics.recordRelease("track-selector-released owner=$owner listenerNull=$listenerNull bandwidthNull=$bandwidthNull focusControlNull=$controlNull focusState=$focusState")
        } catch(e:Exception) { PlaybackMetrics.recordRelease("track-selector-observation owner=$owner unavailable=${e.javaClass.name}") }
        finally { playerRef=null }
    }
}

/** Live offsets are relative to a moving window and must not become resume timestamps. */
internal fun resumePosition(live:Boolean,position:Long):Long = if(live) 0 else position.coerceAtLeast(0)
