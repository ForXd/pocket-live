package org.streampocket

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Dns
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Actual Pocket/AppModel/PlaybackScreen code, real HTTP and decoders; only DNS/test data injected. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class AppFlowDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun subscriptionSearchPlaybackLifecycleAndStorage() {
        androidx.test.espresso.IdlingPolicies.setMasterPolicyTimeout(120,TimeUnit.SECONDS)
        val server=MockWebServer();server.start()
        val base="http://fixture.test:${server.port}"
        val failSubscription=AtomicBoolean(false)
        val failMedia=AtomicBoolean(false)
        val fullFlow=InstrumentationRegistry.getArguments().getString("fullFlow")=="true"
        val includePip=InstrumentationRegistry.getArguments().getString("includePip")!="false"
        val pipOnly=InstrumentationRegistry.getArguments().getString("pipOnly")=="true"
        require(!pipOnly || (fullFlow && includePip))
        val extra=if(fullFlow) ",{\"name\":\"测试源2\",\"url\":\"$base/api2\"}" else ""
        val instrument=InstrumentationRegistry.getInstrumentation()
        val media=instrument.context.assets
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                val path=request.requestUrl!!.encodedPath
                return when {
                    path=="/sub" -> if(failSubscription.get()) MockResponse().setResponseCode(503) else MockResponse().setBody("""{"name":"受控订阅","sources":[{"name":"测试源","url":"$base/api"}$extra]}""")
                    path.startsWith("/api") -> if(request.requestUrl!!.queryParameter("ids")!=null) MockResponse().setBody("""{"list":[{"vod_play_from":"MP4${'$'}${'$'}${'$'}HLS","vod_play_url":"MP4集${'$'}$base/test.mp4${'$'}${'$'}${'$'}HLS集${'$'}$base/test.m3u8"}]}""") else MockResponse().setBody("""{"list":[{"vod_id":"1","vod_name":"测试画面"}]}""")
                    path.endsWith(".mp4") || path.endsWith(".ts") || path.endsWith(".m3u8") -> {
                        if(failMedia.get()) return MockResponse().setResponseCode(503)
                        val bytes=media.open(path.removePrefix("/")).use { it.readBytes() }
                        MockResponse().setBody(Buffer().write(bytes)).addHeader("Content-Type",if(path.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else if(path.endsWith(".ts")) "video/mp2t" else "video/mp4")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
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
        val diagnostics=if(InstrumentationRegistry.getArguments().getString("releaseDiagnostics")=="true")
            ReleaseDiagnostics(instrument.targetContext.applicationContext as android.app.Application).also { it.start() } else null
        val injectReleaseFailure=InstrumentationRegistry.getArguments().getString("injectReleaseFailure")=="true"
        ReleaseFaultInjection.useOriginalRelease=InstrumentationRegistry.getArguments().getString("originalReleaseControl")=="true"
        ReleaseFaultInjection.triggered.set(0)
        try {
            ui.waitUntil(60000) { !model.state.value.loading }
            ui.runOnIdle { model.allowHttp(true) }
            ui.waitUntil(60000) { model.state.value.saved.allowHttp }
            if(InstrumentationRegistry.getArguments().getString("sessionOnly")=="true" || InstrumentationRegistry.getArguments().getString("fullscreenBackOnly")=="true" || InstrumentationRegistry.getArguments().getString("fullscreenPipOnly")=="true") {
                val initialFrames=PlaybackMetrics.frames
                ui.runOnIdle { model.play(Video(Source("会话测试","$base/api"),"session-A","会话测试"),Episode("MP4集","$base/test.mp4")) }
                ui.waitUntil(120000) { PlaybackMetrics.frames>initialFrames }
                ui.waitUntil(120000) {
                    var ready=false
                    ui.runOnIdle { val p=findPlayer(ui.activity.window.decorView) as? androidx.media3.exoplayer.ExoPlayer
                        ready=p!=null && p.currentPosition>1500 && (p.audioDecoderCounters?.renderedOutputBufferCount ?: 0)>0 }
                    ready
                }
                if(InstrumentationRegistry.getArguments().getString("fullscreenBackOnly")=="true" || InstrumentationRegistry.getArguments().getString("fullscreenPipOnly")=="true") verifyFullscreenAndPip() else verifySessionReplacement(model)
                ui.runOnIdle { model.stopPlayback() }
                ui.waitUntil(60000) { PlaybackMetrics.created==PlaybackMetrics.released }
                assertEquals(0,PlaybackMetrics.samplers)
                return
            }
            ui.onNodeWithText("订阅").performClick()
            ui.onNodeWithTag("subscriptionInput").performTextReplacement("$base/sub")
            ui.onNodeWithText("添加订阅").performScrollTo().performClick()
            ui.waitUntil(60000) { model.state.value.saved.subscriptions.size==1 }
            val original=model.state.value.saved.subscriptions.single()
            failSubscription.set(true)
            ui.runOnIdle { model.importSubscription(original.url,original) }
            ui.waitUntil(60000) { model.state.value.importing.isEmpty() && model.state.value.message?.contains("503")==true }
            assertEquals(original,model.state.value.saved.subscriptions.single())
            failSubscription.set(false)
            if(fullFlow) {
                ui.runOnIdle { model.importSubscription(original.url,original) }
                ui.waitUntil(60000) { model.state.value.saved.subscriptions.single().updated>original.updated && model.state.value.importing.isEmpty() }
            }
            ui.onNodeWithText("搜索",useUnmergedTree=true).performClick()
            ui.onNodeWithTag("searchInput").performTextReplacement("测试")
            ui.onNodeWithText("开始搜索").performClick()
            ui.waitUntil(60000) { model.state.value.results.size==(if(fullFlow) 2 else 1) && !model.state.value.searching }
            ui.onAllNodesWithText("测试画面")[0].performClick()
            ui.waitUntil(60000) { model.state.value.detail!=null }
            if(fullFlow) {
                ui.onNodeWithText("搜索同名 · 换源").performClick()
                ui.waitUntil(60000) { !model.state.value.searching && model.state.value.results.size==2 }
                ui.onNode(hasText("测试源2 · ",substring=true)).performClick()
                ui.waitUntil(60000) { model.state.value.detail?.video?.source?.name=="测试源2" }
                failMedia.set(true)
                ui.onNodeWithText("MP4集").performClick()
                ui.waitUntil(60000) { ui.onAllNodes(hasText("播放失败",substring=true)).fetchSemanticsNodes().isNotEmpty() }
                val framesBeforeRetry=PlaybackMetrics.frames
                failMedia.set(false)
                ui.onNodeWithText("重试播放").performClick()
                ui.waitUntil(120000) { PlaybackMetrics.frames>framesBeforeRetry }
                val supportsPip=ui.activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
                instrument.sendStatus(0,android.os.Bundle().apply { putBoolean("pip_supported",supportsPip) })
                if(supportsPip && includePip) {
                    ui.onNodeWithText("画中画").performClick()
                    ui.waitUntil(60000) { ui.activity.isInPictureInPictureMode }
                    assertEquals(1L,PlaybackMetrics.created-PlaybackMetrics.released)
                    if(InstrumentationRegistry.getArguments().getString("pipInspectOnly")=="true") {
                        capturePipSystemEvidence()
                        org.junit.Assume.assumeTrue("DIAGNOSTIC ONLY: PiP exit not attempted; no PiP acceptance claim",false)
                    }
                    expandPipWithSystemControl()
                    ui.waitUntil(60000) { !ui.activity.isInPictureInPictureMode }
                }
                if(!includePip) instrument.sendStatus(0,android.os.Bundle().apply { putString("pip_acceptance","NOT RUN in this isolated playback stress run; separate PiP acceptance remains required") })
                ui.onNodeWithText("返回选集").performClick()
                ui.waitUntil(60000) { PlaybackMetrics.created==PlaybackMetrics.released }
                if(pipOnly) { assertEquals(0,PlaybackMetrics.samplers);return }
            }
            val stress=InstrumentationRegistry.getArguments().getString("stressCycles")?.toIntOrNull()?.coerceIn(0,50) ?: 0
            val weakPlayers=mutableListOf<java.lang.ref.WeakReference<Any>>()
            val weakActivities=mutableListOf<java.lang.ref.WeakReference<Any>>()
            val memory=org.json.JSONArray()
            for((cycle,label) in (listOf("MP4集","HLS集")+List(stress) { if(it%2==0) "MP4集" else "HLS集" }).withIndex()) {
                val frames=PlaybackMetrics.frames
                val frameStart=android.os.SystemClock.elapsedRealtime()
                ui.onNodeWithText(label).performClick()
                ui.waitUntil(120000) { PlaybackMetrics.frames>frames }
                val firstFrame=android.os.SystemClock.elapsedRealtime()-frameStart
                ui.waitUntil(120000) {
                    var advanced=false
                    ui.runOnIdle { advanced=(findPlayer(ui.activity.window.decorView) as? androidx.media3.common.Player)?.currentPosition?.let { it>1500 } == true }
                    advanced
                }
                assertEquals(1L,PlaybackMetrics.created-PlaybackMetrics.released)
                ui.waitUntil(60000) {
                    var rendered=false
                    ui.runOnIdle {
                        val p=findPlayer(ui.activity.window.decorView) as? androidx.media3.exoplayer.ExoPlayer
                        rendered=(p?.audioDecoderCounters?.renderedOutputBufferCount ?: 0)>0
                    }
                    rendered
                }
                if(cycle==0 && InstrumentationRegistry.getArguments().getString("fullscreenOnly")=="true") {
                    verifyFullscreenAndPip()
                    ui.onNodeWithText("返回选集").performClick()
                    ui.waitUntil(60000) { PlaybackMetrics.created==PlaybackMetrics.released }
                    assertEquals(0,PlaybackMetrics.samplers)
                    return
                }
                ui.runOnIdle { findPlayer(ui.activity.window.decorView)?.let { weakPlayers+=java.lang.ref.WeakReference(it) } }
                if(cycle==0 && InstrumentationRegistry.getArguments().getString("extendedLifecycle")=="true") {
                    ui.runOnIdle {
                        val view=findPlayerView(ui.activity.window.decorView)!!
                        view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause).performClick()
                    }
                    ui.waitUntil(60000) { var paused=false;ui.runOnIdle { paused=!(findPlayer(ui.activity.window.decorView) as androidx.media3.common.Player).playWhenReady };paused }
                    for(orientation in listOf(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
                        ui.runOnIdle { weakActivities+=java.lang.ref.WeakReference(ui.activity);ui.activity.requestedOrientation=orientation }
                        val expected=if(orientation==android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT
                        ui.waitUntil(60000) { instrument.targetContext.resources.configuration.orientation==expected }
                        ui.waitUntil(60000) { PlaybackMetrics.created-PlaybackMetrics.released==1L }
                        ui.runOnIdle { assertFalse((findPlayer(ui.activity.window.decorView) as androidx.media3.common.Player).playWhenReady) }
                    }
                    ui.runOnIdle { findPlayerView(ui.activity.window.decorView)!!.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause).performClick() }
                    ui.waitUntil(60000) { var playing=false;ui.runOnIdle { playing=(findPlayer(ui.activity.window.decorView) as androidx.media3.common.Player).playWhenReady };playing }
                }
                if(cycle<2) {
                ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                assertEquals(0L,PlaybackMetrics.created-PlaybackMetrics.released)
                assertEquals(0,PlaybackMetrics.samplers)
                val samples=PlaybackMetrics.samples
                Thread.sleep(5500)
                assertEquals(samples,PlaybackMetrics.samples)
                ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                ui.waitUntil(60000) { PlaybackMetrics.created-PlaybackMetrics.released==1L }
                ui.runOnIdle { weakActivities+=java.lang.ref.WeakReference(ui.activity) }
                ui.activityRule.scenario.recreate()
                ui.waitUntil(60000) { PlaybackMetrics.created-PlaybackMetrics.released==1L }
                }
                if(injectReleaseFailure && cycle==2) ReleaseFaultInjection.pending.set(true)
                ui.onNodeWithText("返回选集").performClick()
                ui.waitUntil(60000) { PlaybackMetrics.created==PlaybackMetrics.released }
                assertEquals(0,PlaybackMetrics.samplers)
                if(stress>0) {
                    Thread.sleep(1500)
                    val info=android.os.Debug.MemoryInfo();android.os.Debug.getMemoryInfo(info)
                    val sample=org.json.JSONObject().put("cycle",cycle).put("pss_kib",info.totalPss)
                        .put("java_heap_bytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
                        .put("native_heap_bytes",android.os.Debug.getNativeHeapAllocatedSize())
                        .put("first_frame_ms",firstFrame)
                        .put("files_bytes",instrument.targetContext.filesDir.walkTopDown().filter { it.isFile }.sumOf { it.length() })
                        .put("cache_bytes",instrument.targetContext.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() })
                    memory.put(sample);instrument.sendStatus(0,android.os.Bundle().apply { putString("playback_cycle",sample.toString()) })
                }
            }
            if(stress>0) {
                repeat(3) { round ->
                    Thread.sleep(10000);System.gc();System.runFinalization();Thread.sleep(2000)
                    instrument.sendStatus(0,android.os.Bundle().apply {
                        putInt("retention_round",round);putInt("weak_players_alive",weakPlayers.count { it.get()!=null })
                        putInt("weak_destroyed_activities_alive",weakActivities.count { it.get()!=null })
                    })
                    diagnostics?.snapshot("settle-$round")
                    if(diagnostics!=null && round<2) android.os.Debug.dumpHprofData(java.io.File(instrument.targetContext.getExternalFilesDir(null),"settle-$round.hprof").absolutePath)
                }
                val heap=java.io.File(instrument.targetContext.getExternalFilesDir(null),"after-playback.hprof")
                android.os.Debug.dumpHprofData(heap.absolutePath)
                instrument.sendStatus(0,android.os.Bundle().apply { putString("heap_path",heap.absolutePath);putString("memory_samples",memory.toString()) })
            }
            if(injectReleaseFailure) {
                assertEquals("Injected video disable must execute exactly once",1,ReleaseFaultInjection.triggered.get())
                assertEquals(0,ReleaseFaultInjection.videoState)
                assertTrue("Audio must still be enabled/started at injection",ReleaseFaultInjection.audioState in 1..2)
            }
            ui.runOnIdle { model.clearCache() }
            ui.waitUntil(60000) { model.state.value.storageUsage?.responseBytes==0L }
            assertEquals(1,model.state.value.saved.subscriptions.size)
            instrument.sendStatus(0,android.os.Bundle().apply {
                putLong("players_created",PlaybackMetrics.created);putLong("players_released",PlaybackMetrics.released)
                putLong("decoded_first_frames",PlaybackMetrics.frames);putInt("active_samplers",PlaybackMetrics.samplers)
                putString("scope","controlled-real-http-MP4-HLS-actual-app-screen")
            })
        } finally {
            instrument.sendStatus(0,android.os.Bundle().apply {
                putString("last_decoder_diagnostic",PlaybackMetrics.errorDiagnostic)
                putLong("created_at_end",PlaybackMetrics.created);putLong("released_before_cleanup",PlaybackMetrics.released)
                putLong("frames_at_end",PlaybackMetrics.frames)
                putLong("decoder_fallbacks",PlaybackMetrics.decoderFallbacks)
                putLong("release_timeouts",PlaybackMetrics.releaseTimeouts);putLong("release_failures",PlaybackMetrics.releaseFailures)
                putLong("max_release_ms",PlaybackMetrics.maxReleaseMs);putLong("last_release_elapsed",PlaybackMetrics.lastReleaseElapsed)
                putInt("injected_disable_failures",ReleaseFaultInjection.triggered.get())
                putInt("injected_video_state",ReleaseFaultInjection.videoState);putInt("injected_audio_state",ReleaseFaultInjection.audioState)
                putLong("renderer_cleanup_failures",PlaybackMetrics.cleanupFailures)
            })
            ui.runOnUiThread { model.stopPlayback() };server.shutdown();diagnostics?.close()
            ReleaseFaultInjection.pending.set(false);ReleaseFaultInjection.useOriginalRelease=false
        }
    }
    private fun verifySessionReplacement(model:AppModel) {
        lateinit var a:Playback
        lateinit var b:Playback
        lateinit var second:Playback
        lateinit var firstPlayer:androidx.media3.common.Player
        var released=PlaybackMetrics.released
        ui.runOnIdle {
            a=model.state.value.playback!!
            firstPlayer=findPlayer(ui.activity.window.decorView) as androidx.media3.common.Player
            firstPlayer.pause()
            assertTrue(firstPlayer.currentPosition>=1500)
            // A -> different B in the same composition slot. A disposal must
            // checkpoint A's identity, never the new B's position.
            model.play(a.video.copy(id="session-B"),a.episode)
            b=model.state.value.playback!!
            model.playbackIntent(b.sessionId,false)
        }
        ui.waitUntil(90000) { PlaybackMetrics.released>released }
        ui.runOnIdle {
            assertEquals(b.sessionId,model.state.value.playback!!.sessionId)
            assertEquals(0L,model.state.value.playback!!.position)
            assertNotSame(firstPlayer,findPlayer(ui.activity.window.decorView))
            model.checkpoint(a,98765,false)
            assertEquals(0L,model.state.value.playback!!.position)
        }
        ui.waitUntil(60000) { model.state.value.saved.history.any { it.playback.sessionId==a.sessionId && it.playback.position==98765L } }
        ui.runOnIdle {
            assertEquals(98765L,model.state.value.saved.history.first { it.playback.sessionId==a.sessionId }.playback.position)
            released=PlaybackMetrics.released
            // Reopen exactly the same episode with a deliberately different
            // resume position. The old session's late writes must be rejected.
            model.resume(History(b.copy(position=321),System.currentTimeMillis()))
            second=model.state.value.playback!!
            model.playbackIntent(second.sessionId,false)
        }
        ui.waitUntil(90000) { PlaybackMetrics.released>released }
        ui.runOnIdle {
            assertNotEquals(b.sessionId,second.sessionId)
            model.checkpoint(b,88888,false)
            assertEquals(second.sessionId,model.state.value.playback!!.sessionId)
            assertEquals(321L,model.state.value.playback!!.position)
            repeat(10000) { model.measuredQuality(second.sessionId,1920,1080) }
        }
        ui.waitUntil(60000) { model.state.value.playback!!.episode.quality.evidence==QualityEvidence.MEASURED }
        ui.runOnIdle {
            val before=model.state.value
            repeat(10000) { model.measuredQuality(second.sessionId,1920,1080) }
            assertSame("Duplicate dimensions must not emit UI state",before,model.state.value)
            assertEquals(321L,model.state.value.playback!!.position)
        }
    }
    private fun verifyFullscreenAndPip() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val dir=java.io.File(instrument.targetContext.getExternalFilesDir(null),"fullscreen").apply { mkdirs() }
        fun capture(name:String) {
            instrument.uiAutomation.takeScreenshot()?.let { bitmap ->
                try { java.io.File(dir,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } } finally { bitmap.recycle() }
            }
        }
        lateinit var original:androidx.media3.exoplayer.ExoPlayer
        var position=0L
        var portraitWidth=0
        var portraitHeight=0
        ui.runOnIdle {
            original=findPlayer(ui.activity.window.decorView) as androidx.media3.exoplayer.ExoPlayer
            original.pause();position=original.currentPosition
            val v=findPlayerView(ui.activity.window.decorView)!!
            portraitWidth=v.width;portraitHeight=v.height
        }
        capture("portrait")
        ui.onNodeWithText("横屏全屏").performClick()
        ui.waitUntil(90000) { ui.activity.window.decorView.width>ui.activity.window.decorView.height }
        ui.waitUntil(90000) {
            var hidden=false
            ui.runOnIdle { hidden=androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.systemBars())==false }
            hidden
        }
        var landscapeWidth=0
        var landscapeHeight=0
        ui.runOnIdle {
            val v=findPlayerView(ui.activity.window.decorView)!!
            landscapeWidth=v.width;landscapeHeight=v.height
            assertSame("Rotation must retain the player",original,v.player)
            assertFalse(original.playWhenReady)
            assertTrue(kotlin.math.abs(original.currentPosition-position)<1000)
            assertTrue("Video width must increase",landscapeWidth>portraitWidth)
            val ratio=original.videoSize.width.toDouble()/original.videoSize.height.coerceAtLeast(1)
            val before=minOf(portraitWidth.toDouble(),portraitHeight*ratio)
            val after=minOf(landscapeWidth.toDouble(),landscapeHeight*ratio)
            assertTrue("Fitted video must grow",after>before)
        }
        capture("landscape")
        val testPip=InstrumentationRegistry.getArguments().getString("fullscreenBackOnly")!="true"
        if(testPip) {
            // Fullscreen code6 starts hidden; explicitly reveal the normal control layer.
            ui.runOnIdle { findPlayerView(ui.activity.window.decorView)!!.performClick() }
            ui.onNodeWithText("画中画").performClick()
            ui.waitUntil(90000) { ui.activity.isInPictureInPictureMode }
            capture("pip")
            expandPipWithSystemControl()
            ui.waitUntil(90000) { !ui.activity.isInPictureInPictureMode && ui.activity.window.decorView.width>ui.activity.window.decorView.height }
        } else instrument.sendStatus(0,android.os.Bundle().apply { putString("pip_acceptance","NOT RUN: independent fullscreen/back scope; PiP acceptance remains required") })
        ui.runOnIdle {
            assertSame(original,findPlayer(ui.activity.window.decorView))
            assertFalse(original.playWhenReady)
            assertTrue(kotlin.math.abs(original.currentPosition-position)<1000)
            ui.activity.onBackPressedDispatcher.onBackPressed()
        }
        ui.waitUntil(90000) { ui.activity.window.decorView.width<ui.activity.window.decorView.height }
        ui.onNodeWithText("横屏全屏").assertIsDisplayed()
        ui.runOnIdle { assertSame(original,findPlayer(ui.activity.window.decorView));assertFalse(original.playWhenReady) }
        capture("restored")
        java.io.File(dir,"assertions.json").writeText(org.json.JSONObject()
            .put("portrait_width",portraitWidth).put("portrait_height",portraitHeight)
            .put("landscape_width",landscapeWidth).put("landscape_height",landscapeHeight)
            .put("same_player",true).put("system_bars_hidden",true).put("paused_position_preserved",true)
            .put("pip_expand",testPip).put("back_exits_fullscreen_first",true).toString(2))
        instrument.sendStatus(0,android.os.Bundle().apply { putString("fullscreen_assertions",java.io.File(dir,"assertions.json").readText()) })
    }
    private fun capturePipSystemEvidence() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val automation=instrument.uiAutomation
        val oldFlags=automation.serviceInfo.flags
        automation.serviceInfo=automation.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val dir=java.io.File(instrument.targetContext.getExternalFilesDir(null),"pip-diagnostic").apply { mkdirs() }
        try {
            repeat(3) { round ->
                Thread.sleep(10000)
                val text=StringBuilder();var count=0
                fun node(n:android.view.accessibility.AccessibilityNodeInfo?,depth:Int) {
                    if(n==null || depth>20 || ++count>1000) return
                    val b=android.graphics.Rect();n.getBoundsInScreen(b)
                    text.append(" ".repeat(depth)).append("pkg=${n.packageName} id=${n.viewIdResourceName} class=${n.className} desc=${n.contentDescription} text=${n.text} clickable=${n.isClickable} bounds=$b actions=${n.actionList}\n")
                    for(i in 0 until n.childCount) node(n.getChild(i),depth+1)
                }
                for(w in automation.windows) {
                    val b=android.graphics.Rect();w.getBoundsInScreen(b)
                    text.append("WINDOW id=${w.id} type=${w.type} pip=${w.isInPictureInPictureMode} active=${w.isActive} focused=${w.isFocused} bounds=$b\n")
                    node(w.root,0)
                }
                java.io.File(dir,"windows-$round.txt").writeText(text.toString())
                automation.takeScreenshot()?.let { bitmap ->
                    try { java.io.File(dir,"screen-$round.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } } finally { bitmap.recycle() }
                }
                for(kind in listOf("activity activities","window windows")) {
                    val output=android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("dumpsys $kind")).use { it.readBytes() }
                    java.io.File(dir,kind.substringBefore(' ')+"-$round.txt").writeBytes(output)
                }
            }
        } finally { automation.serviceInfo=automation.serviceInfo.apply { flags=oldFlags } }
    }
    private fun expandPipWithSystemControl() {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val originalFlags=automation.serviceInfo.flags
        val windowEvidence=StringBuilder()
        automation.serviceInfo=automation.serviceInfo.apply {
            flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        fun findExpand(node:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo? {
            if(node==null) return null
            val description=node.contentDescription?.toString().orEmpty().lowercase()
            if(node.isClickable && (node.viewIdResourceName?.endsWith("/expand_button")==true ||
                    description in listOf("fullscreen","full screen","expand","全屏"))) return node
            for(i in 0 until node.childCount) findExpand(node.getChild(i))?.let { return it }
            return null
        }
        try {
            // isInPictureInPictureMode becomes true before the system entry animation ends.
            // Use the real system full-screen control instead of racing a reorder intent.
            val deadline=android.os.SystemClock.elapsedRealtime()+60000
            var tappedAt=0L
            while(android.os.SystemClock.elapsedRealtime()<deadline) {
                val windows=automation.windows
                windows.mapNotNull { findExpand(it.root) }.firstOrNull()?.let {
                    assertTrue("System PiP expand action rejected",it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                    return
                }
                val now=android.os.SystemClock.elapsedRealtime()
                if(now-tappedAt>3000) {
                    val pip=windows.firstOrNull { it.isInPictureInPictureMode && it.root?.packageName==InstrumentationRegistry.getInstrumentation().targetContext.packageName }
                    if(pip!=null) {
                        val bounds=android.graphics.Rect();pip.getBoundsInScreen(bounds)
                        val root=pip.root
                        if(root?.actionList?.any { it.id==android.view.accessibility.AccessibilityNodeInfo.ACTION_EXPAND }==true) {
                            windowEvidence.append("System PiP root exposes ACTION_EXPAND; requesting it\n")
                            assertTrue(root.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_EXPAND))
                            return
                        }
                        val display=android.util.DisplayMetrics()
                        @Suppress("DEPRECATION")
                        (InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(display)
                        if(windowEvidence.length<16000) windowEvidence.append("t=$now pip=$bounds display=${display.widthPixels}x${display.heightPixels}\n")
                        if(!bounds.isEmpty && bounds.width()<display.widthPixels*0.9 && bounds.height()<display.heightPixels*0.8) {
                            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")).use { it.readBytes() }
                            tappedAt=now
                        }
                    }
                    else if(windowEvidence.length<16000) windowEvidence.append(windows.joinToString(prefix="t=$now windows=",postfix="\n") { "${it.id}:pip=${it.isInPictureInPictureMode}:pkg=${it.root?.packageName}" })
                }
                Thread.sleep(250)
            }
            fail("System PiP full-screen control did not become accessible")
        } finally {
            java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"pip-window-evidence.txt").writeText(windowEvidence.toString())
            automation.serviceInfo=automation.serviceInfo.apply { flags=originalFlags }
        }
    }
    private fun findPlayerView(view:android.view.View):androidx.media3.ui.PlayerView? {
        if(view is androidx.media3.ui.PlayerView) return view
        if(view is android.view.ViewGroup) for(i in 0 until view.childCount) findPlayerView(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun findPlayer(view:android.view.View):Any? {
        if(view is androidx.media3.ui.PlayerView) return view.player
        if(view is android.view.ViewGroup) for(i in 0 until view.childCount) findPlayer(view.getChildAt(i))?.let { return it }
        return null
    }

}
