package org.streampocket

import androidx.media3.common.*
import androidx.media3.exoplayer.*
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Independent real surface/decoder, same synthetic media; no app retry/network/controller. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class DecoderIsolationTest {
    @Test fun realSurfaceDecode() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        val asset=args.getString("decoderAsset") ?: "test.mp4"
        require(asset in setOf("test.mp4","baseline-video.mp4"))
        val fallback=args.getString("decoderFallback")=="true"
        val software=args.getString("softwareOnly")=="true"
        val file=java.io.File(instrument.targetContext.cacheDir,"decoder-isolation.mp4")
        instrument.context.assets.open(asset).use { input -> file.outputStream().use(input::copyTo) }
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        val event=CountDownLatch(1)
        val report=org.json.JSONObject().put("asset",asset).put("fallback",fallback).put("software_only",software).put("first_frame",false)
        var player:ExoPlayer?=null
        var view:PlayerView?=null
        val start=android.os.SystemClock.elapsedRealtime()
        try {
            scenario.onActivity { activity ->
                view=PlayerView(activity);activity.setContentView(view)
                val renderers=DefaultRenderersFactory(activity.applicationContext).setEnableDecoderFallback(fallback)
                renderers.setMediaCodecSelector { mime,secure,tunnel ->
                    val all=androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(mime,secure,tunnel)
                    report.put("candidates_$mime",org.json.JSONArray(all.map { "${it.name};software=${it.softwareOnly}" }))
                    if(software) all.filter { it.softwareOnly } else all
                }
                player=ExoPlayer.Builder(activity.applicationContext,renderers).build().also { p ->
                    view!!.player=p
                    p.addListener(object:Player.Listener {
                        override fun onRenderedFirstFrame() {
                            report.put("first_frame",true).put("first_frame_ms",android.os.SystemClock.elapsedRealtime()-start);event.countDown()
                        }
                        override fun onPlayerError(error:PlaybackException) {
                            report.put("error",playbackErrorDiagnostic(error));event.countDown()
                        }
                    })
                    p.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)));p.prepare();p.play()
                }
            }
            val returned=event.await(120,TimeUnit.SECONDS)
            report.put("event_received",returned)
            val output=java.io.File(instrument.targetContext.getExternalFilesDir(null),"decoder-$asset-$fallback-$software.json")
            output.writeText(report.toString(2))
            instrument.sendStatus(0,android.os.Bundle().apply { putString("decoder_diagnostic",report.toString()) })
            assertTrue(report.toString(),report.getBoolean("first_frame"))
        } finally {
            scenario.onActivity { view?.player=null;player?.release();player=null;view=null }
            scenario.close();file.delete()
        }
    }
}
