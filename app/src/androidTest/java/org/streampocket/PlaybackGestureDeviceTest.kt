package org.streampocket

import android.os.SystemClock
import android.view.*
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
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class PlaybackGestureDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val directTouch get()=InstrumentationRegistry.getArguments().getString("directTouch")=="true"
    private fun view(root:View):GesturePlayerView? {
        if(root is GesturePlayerView) return root
        if(root is ViewGroup) for(i in 0 until root.childCount) view(root.getChildAt(i))?.let { return it }
        return null
    }
    private fun playerView()=view(ui.activity.window.decorView)!!
    private fun readyFor(expected:Playback?):Boolean {
        if(expected==null) return false
        val v=view(ui.activity.window.decorView) ?: return false
        val owner=(v.seekRelative as? kotlin.jvm.internal.CallableReference)?.boundReceiver as? PlaybackController ?: return false
        val field=PlaybackController::class.java.getDeclaredField("session").apply { isAccessible=true }
        val actual=field.get(owner) as Playback
        return actual.sessionId==expected.sessionId && v.player?.currentMediaItem?.localConfiguration?.uri?.toString()==expected.episode.url && v.player?.playbackState==androidx.media3.common.Player.STATE_READY
    }
    private var observingActivePlayer=false
    private fun waitFor(predicate:()->Boolean) { ui.waitUntil(120000) { var yes=false;if(observingActivePlayer) instrument.runOnMainSync { yes=predicate() } else ui.runOnIdle { yes=predicate() };yes } }
    private val clickStates=java.util.concurrent.CopyOnWriteArrayList<Boolean>()
    private val visibilityEvents=java.util.concurrent.CopyOnWriteArrayList<Pair<Long,Int>>()
    private val touchTimeline=java.util.concurrent.CopyOnWriteArrayList<String>()
    private fun trace(value:String) { touchTimeline.add("${SystemClock.uptimeMillis()}:$value") }
    private fun inject(action:Int,x:Float,y:Float,down:Long,eventTime:Long=SystemClock.uptimeMillis(),sync:Boolean=true) {
        val event=MotionEvent.obtain(down,eventTime,action,x,y,0)
        event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
        try {
            if(directTouch) instrument.runOnMainSync {
                val xy=IntArray(2);playerView().getLocationOnScreen(xy)
                event.offsetLocation(-xy[0].toFloat(),-xy[1].toFloat())
                trace("dispatch action=$action event=${event.eventTime} down=${event.downTime} visible=${playerView().isControllerFullyVisible}")
                assertTrue(playerView().dispatchTouchEvent(event))
                trace("dispatched action=$action visible=${playerView().isControllerFullyVisible}")
            } else assertTrue(instrument.uiAutomation.injectInputEvent(event,sync))
        } finally { event.recycle() }
    }
    private fun point(right:Boolean):Pair<Float,Float> {
        var point=0f to 0f
        instrument.runOnMainSync { val v=playerView();val xy=IntArray(2);v.getLocationOnScreen(xy);point=(xy[0]+v.width*(if(right) .85f else .15f)) to (xy[1]+v.height*.4f) }
        return point
    }
    private fun tap(right:Boolean,count:Int=1) {
        val (x,y)=point(right)
        if(count==2) {
            val start=SystemClock.uptimeMillis()
            // Preserve the actual intended double-tap event times even when the
            // software emulator delays acknowledgement. Events still use InputDispatcher.
            for((action,offset) in listOf(MotionEvent.ACTION_DOWN to 0L,MotionEvent.ACTION_UP to 20L,MotionEvent.ACTION_DOWN to 80L,MotionEvent.ACTION_UP to 100L)) {
                val target=start+offset;val remaining=target-SystemClock.uptimeMillis()
                if(remaining>0) SystemClock.sleep(remaining)
                inject(action,x,y,if(offset<80) start else start+80,target,false)
            }
            instrument.waitForIdleSync()
        } else {
            val down=SystemClock.uptimeMillis();inject(MotionEvent.ACTION_DOWN,x,y,down);inject(MotionEvent.ACTION_UP,x,y,down)
        }
    }
    private fun nativeTap(id:Int,fraction:Float=.5f) {
        var x=0f;var y=0f
        instrument.runOnMainSync { val v=playerView().findViewById<View>(id);v.setOnTouchListener { _,event -> trace("native id=$id action=${event.actionMasked}");false };trace("native-target id=$id enabled=${v.isEnabled} shown=${v.isShown} size=${v.width}x${v.height}");val xy=IntArray(2);v.getLocationOnScreen(xy);x=xy[0]+v.width*fraction;y=xy[1]+v.height*.5f }
        val down=SystemClock.uptimeMillis();inject(MotionEvent.ACTION_DOWN,x,y,down);inject(MotionEvent.ACTION_UP,x,y,down)
    }
    private fun startupTraces() {
        if(android.os.Build.VERSION.SDK_INT<30) return
        val context=instrument.targetContext
        val dir=java.io.File(context.getExternalFilesDir(null),"gestures").apply { mkdirs() }
        val manager=context.getSystemService(android.app.ActivityManager::class.java)
        val records=manager.getHistoricalProcessExitReasons(context.packageName,0,8)
        java.io.File(dir,"exit-info.txt").writeText(records.joinToString("\n") { "time=${it.timestamp} reason=${it.reason} status=${it.status} description=${it.description}" })
        records.filter { it.reason==android.app.ApplicationExitInfo.REASON_ANR }.take(2).forEachIndexed { index,record ->
            record.traceInputStream?.use { input ->
                val buffer=ByteArray(256*1024);var count=0
                while(count<buffer.size) { val n=input.read(buffer,count,buffer.size-count);if(n<0) break;count+=n }
                java.io.File(dir,"startup-anr-$index.txt").writeBytes(buffer.copyOf(count))
            }
        }
    }
    private fun stage(value:String) {
        instrument.sendStatus(0,android.os.Bundle().apply { putString("stage",value) })
    }
    private fun capture(name:String) {
        val dir=java.io.File(instrument.targetContext.getExternalFilesDir(null),"gestures").apply { mkdirs() }
        instrument.uiAutomation.takeScreenshot()?.let { bitmap ->try { java.io.File(dir,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } } finally { bitmap.recycle() } }
    }
    @Test fun realTouchControlsSeekAndEpisodePanel() {
        androidx.test.espresso.IdlingPolicies.setMasterPolicyTimeout(120,TimeUnit.SECONDS)
        val directFixture=InstrumentationRegistry.getArguments().getString("directFixture")=="true"
        startupTraces()
        val server=MockWebServer();server.start(InetAddress.getByName("127.0.0.1"),0);val base="http://fixture.test:${server.port}"
        val bytes=instrument.context.assets.open("gestures-video-only.mp4").use { it.readBytes() }
        val mediaTemplate=MockResponse().addHeader("Content-Type","video/mp4").setBody(Buffer().write(bytes))
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.path!!.startsWith("/failure")) return MockResponse().setResponseCode(404).setBody("local test failure")
                if(request.path!!.startsWith("/api")) {
                    val episodes=(1..1000).joinToString("#") { "第${it}集${'$'}$base/test.mp4?episode=$it" }
                    return MockResponse().setBody("""{"list":[{"vod_play_from":"本地测试","vod_play_url":"$episodes"}]}""")
                }
                val range=request.getHeader("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                if(range==0) return mediaTemplate.clone()
                return MockResponse().setResponseCode(206).addHeader("Content-Type","video/mp4")
                    .addHeader("Content-Range","bytes $range-${bytes.size-1}/${bytes.size}").setBody(Buffer().write(bytes,range.coerceAtMost(bytes.size),bytes.size-range.coerceAtMost(bytes.size)))
            }
        }
        lateinit var model:AppModel
        lateinit var originalCallback:Window.Callback
        val receivedInputs=java.util.ArrayDeque<String>()
        ui.runOnUiThread {
            originalCallback=ui.activity.window.callback
            if(InstrumentationRegistry.getArguments().getString("controlsOnly")!="true") ui.activity.window.callback=object:Window.Callback by originalCallback {
                override fun dispatchTouchEvent(event:MotionEvent):Boolean {
                    receivedInputs.addLast("action=${event.actionMasked},event=${event.eventTime},received=${SystemClock.uptimeMillis()}")
                    while(receivedInputs.size>32) receivedInputs.removeFirst()
                    return originalCallback.dispatchTouchEvent(event)
                }
            }
            ui.activity.viewModelStore.clear()
            model=ViewModelProvider(ui.activity,object:ViewModelProvider.Factory {
                override fun <T:ViewModel> create(clazz:Class<T>):T {
                    @Suppress("UNCHECKED_CAST") return AppModel(ui.activity.application,object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) }) as T
                }
            })[AppModel::class.java]
            ui.activity.setContent { PocketTheme { Pocket(model) } }
        }
        val watchdog=java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        watchdog.scheduleAtFixedRate({
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val v=view(ui.activity.window.decorView)
                stage("health:state=${v?.player?.playbackState},error=${v?.player?.playerError?.errorCode},frames=${PlaybackMetrics.frames},requests=${server.requestCount},buffered=${v?.player?.bufferedPosition},duration=${v?.player?.duration},lifecycle=${ui.activity.lifecycle.currentState}")
            }
            val stacks=Thread.getAllStackTraces().filter { (thread,_) -> thread.name=="main" || thread.name.contains("Instr") || thread.name.contains("ExoPlayer") || thread.name.contains("OkHttp") || thread.name.contains("MockWebServer") || thread.name.contains("Loader") }
            stage("watchdog="+stacks.entries.joinToString(" | ") { (thread,stack) -> thread.name+":"+stack.take(8).joinToString(";") })
        },30,30,TimeUnit.SECONDS)
        try {
            stage(if(directTouch) "isolated-view-dispatch-not-system-input" else "system-input-dispatch")
            stage("model-started")
            waitFor { !model.state.value.loading }
            stage("model-loaded")
            instrument.runOnMainSync { if(!model.state.value.saved.allowHttp) model.allowHttp(true) }
            waitFor { model.state.value.saved.allowHttp }
            if(directFixture) {
                // Test-only fixture state: no production hook, no API/search request and
                // no assertion that this isolated route proves metadata networking.
                instrument.runOnMainSync {
                    val video=Video(Source("本地确定性夹具","$base/api"),"gesture","手势测试")
                    val episodes=(1..500).map { Episode("第${it}集","$base/test.mp4?episode=$it") }
                    val field=AppModel::class.java.getDeclaredField("_state").apply { isAccessible=true }
                    @Suppress("UNCHECKED_CAST")
                    val mutable=field.get(model) as kotlinx.coroutines.flow.MutableStateFlow<UiState>
                    mutable.value=mutable.value.copy(detail=Detail(video,"仅手势专项的本地夹具",listOf(Line("本地测试",episodes))),detailLoading=false)
                }
                stage("direct-fixture-no-metadata-network")
            } else instrument.runOnMainSync { model.open(Video(Source("本地测试","$base/api"),"gesture","手势测试")) }
            waitFor { !model.state.value.detailLoading }
            assertNotNull("Detail failed: ${model.state.value.message};fixtureRequests=${server.requestCount}",model.state.value.detail)
            assertEquals(500,model.state.value.detail!!.lines[0].episodes.size)
            stage("detail-loaded-500-episodes")
            if(InstrumentationRegistry.getArguments().getString("releaseOnly")=="true") {
                observingActivePlayer=true
                repeat(2) {
                    instrument.runOnMainSync { val d=model.state.value.detail!!;model.play(d.video,d.lines[0].episodes[0]) }
                    waitFor { readyFor(model.state.value.playback) }
                    instrument.runOnMainSync { model.stopPlayback() }
                    waitFor { PlaybackMetrics.created==PlaybackMetrics.released }
                    instrument.runOnMainSync { assertEquals(0,PlaybackMetrics.samplers) }
                    stage("repeat-release-${it+1}-passed")
                }
                return
            }
            if(InstrumentationRegistry.getArguments().getString("errorOnly")=="true") {
                instrument.runOnMainSync { val d=model.state.value.detail!!;model.play(d.video,Episode("失败夹具","$base/failure.mp4")) }
                waitFor { view(ui.activity.window.decorView)?.player?.playerError!=null }
                observingActivePlayer=true
                ui.onNodeWithText("横屏全屏").performClick()
                waitFor { ui.activity.window.decorView.width>ui.activity.window.decorView.height && !playerView().isControllerFullyVisible }
                ui.onNodeWithTag("playbackError").assertIsDisplayed()
                ui.onNodeWithText("重试播放").assertIsDisplayed()
                ui.onNodeWithText("返回选集").assertIsDisplayed()
                stage("actual-http404-fullscreen-recovery-visible")
                val requestsBefore=server.requestCount
                ui.onNodeWithText("重试播放").performClick()
                waitFor { server.requestCount>requestsBefore && playerView().player?.playerError!=null }
                stage("actual-http404-retry-request-passed")
                instrument.runOnMainSync {
                    // Test-only state injection of the initialization catch outcome:
                    // stop(false) clears the PlayerView, then the existing failure callback
                    // exposes the error. No production hook or trust policy is added.
                    val controller=(playerView().seekRelative as kotlin.jvm.internal.CallableReference).boundReceiver as PlaybackController
                    controller.stop(false)
                    val field=PlaybackController::class.java.getDeclaredField("failed").apply { isAccessible=true }
                    @Suppress("UNCHECKED_CAST")
                    val failed=field.get(controller) as (String)->Unit
                    failed("初始化失败（仅测试状态）")
                    assertNull(playerView().player)
                }
                ui.onNodeWithTag("playbackError").assertIsDisplayed()
                ui.onNodeWithText("初始化失败（仅测试状态）").assertIsDisplayed()
                ui.onNodeWithText("重试播放").assertIsEnabled()
                val emptyPlayerRequests=server.requestCount
                ui.onNodeWithText("重试播放").performClick()
                waitFor { server.requestCount>emptyPlayerRequests && playerView().player?.playerError!=null }
                ui.onNodeWithTag("playbackError").assertIsDisplayed()
                stage("empty-player-error-state-retry-passed")
                capture("fullscreen-error")
                ui.onNodeWithText("返回选集").performClick()
                waitFor { model.state.value.playback==null && PlaybackMetrics.created==PlaybackMetrics.released }
                instrument.runOnMainSync { assertEquals(0,PlaybackMetrics.samplers) }
                stage("error-exit-and-release-passed")
                return
            }
            val initialFrames=PlaybackMetrics.frames
            instrument.runOnMainSync { val d=model.state.value.detail!!;model.play(d.video,d.lines.first().episodes[0]) }
            waitFor { view(ui.activity.window.decorView)?.player?.playbackState==androidx.media3.common.Player.STATE_READY && PlaybackMetrics.frames>initialFrames }
            stage("first-frame-ready")
            observingActivePlayer=true
            instrument.runOnMainSync { playerView().player!!.pause();playerView().player!!.seekTo(2000) }
            ui.onNodeWithText("上一集").assertIsNotEnabled()
            ui.onNodeWithText("横屏全屏").performClick()
            waitFor { ui.activity.window.decorView.width>ui.activity.window.decorView.height && !playerView().isControllerFullyVisible }
            ui.onNodeWithText("退出全屏").assertDoesNotExist();capture("fullscreen-hidden");stage("fullscreen-hidden")
            if(!directTouch) waitFor { ui.activity.hasWindowFocus() }
            instrument.runOnMainSync {
                if(!directTouch) assertTrue("System overlay owns focus",ui.activity.hasWindowFocus())
                assertTrue("Fixture must be seekable",playerView().player!!.isCurrentMediaItemSeekable)
                assertTrue(playerView().player!!.isCommandAvailable(androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
            }
            instrument.runOnMainSync {
                val v=playerView()
                v.setOnClickListener { clickStates.add(v.isControllerFullyVisible);trace("performClick visible=${v.isControllerFullyVisible}") }
                v.findViewById<androidx.media3.ui.PlayerControlView>(androidx.media3.ui.R.id.exo_controller).addVisibilityListener { visibility ->
                    visibilityEvents.add(SystemClock.uptimeMillis() to visibility);trace("visibility=$visibility")
                }
            }
            if(InstrumentationRegistry.getArguments().getString("controlsOnly")!="true") {
            tap(true,2)
            instrument.runOnMainSync { stage("double-right: position=${playerView().player!!.currentPosition};duration=${playerView().player!!.duration};focus=${ui.activity.hasWindowFocus()};events="+receivedInputs.joinToString(" | ")) }
            capture("after-double-right")
            waitFor { playerView().player!!.currentPosition in 11500..12500 }
            SystemClock.sleep(600)
            instrument.runOnMainSync { assertFalse("Double tap must not show controls",playerView().isControllerFullyVisible);assertFalse(playerView().player!!.playWhenReady) }
            tap(false,2);waitFor { playerView().player!!.currentPosition in 1500..2500 }
            waitFor { playerView().player!!.playbackState==androidx.media3.common.Player.STATE_READY }
            val seeks=java.util.concurrent.atomic.AtomicInteger()
            val seekListener=object:androidx.media3.common.Player.Listener {
                override fun onPositionDiscontinuity(oldPosition:androidx.media3.common.Player.PositionInfo,newPosition:androidx.media3.common.Player.PositionInfo,reason:Int) {
                    if(reason==androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK) seeks.incrementAndGet()
                }
            }
            instrument.runOnMainSync { playerView().player!!.addListener(seekListener) }
            val (x,y)=point(true);val down=SystemClock.uptimeMillis()
            inject(MotionEvent.ACTION_DOWN,x,y,down);SystemClock.sleep(ViewConfiguration.getLongPressTimeout()+900L);inject(MotionEvent.ACTION_UP,x,y,down)
            var heldPosition=0L
            instrument.runOnMainSync { heldPosition=playerView().player!!.currentPosition;assertTrue(heldPosition>3000) }
            stage("long-press-released")
            val releasedSeekCount=seeks.get()
            SystemClock.sleep(800)
            instrument.runOnMainSync {
                assertEquals("Long press stops immediately",heldPosition,playerView().player!!.currentPosition)
                assertEquals("No delayed seek queue after release",releasedSeekCount,seeks.get())
                assertFalse("Seeking preserves paused intent",playerView().player!!.playWhenReady)
                playerView().player!!.removeListener(seekListener)
            }
            val clicksBefore=clickStates.size
            tap(false);waitFor { clickStates.size>clicksBefore }
            assertTrue("Confirmed single tap showed controls",clickStates.last())
            waitFor { visibilityEvents.any { it.second==View.VISIBLE } && visibilityEvents.last().second!=View.VISIBLE }
            val shown=visibilityEvents.first { it.second==View.VISIBLE }.first
            val hidden=visibilityEvents.first { it.first>=shown && it.second!=View.VISIBLE }.first
            assertTrue("Default idle timeout must not hide controls prematurely",hidden-shown>=2900)
            stage("single-show-and-default-auto-hide: visible-ms=${hidden-shown};events="+visibilityEvents.joinToString())
            // After verifying the production 3-second timeout, isolate remaining controls
            // from this VM's >8-second Espresso observation delays. This is test-only;
            // it does not establish normal system-input timing or change the APK default.
            instrument.runOnMainSync { playerView().controllerShowTimeoutMs=0;playerView().showController() }
            val beforeHide=clickStates.size
            tap(false);waitFor { clickStates.size>beforeHide }
            assertFalse("Second confirmed tap hides controls",clickStates.last())
            val beforeShow=clickStates.size
            tap(false);waitFor { clickStates.size>beforeShow }
            assertTrue(clickStates.last());capture("controls")
            stage("single-toggle-passed")
            } else {
                instrument.runOnMainSync { playerView().controllerShowTimeoutMs=0;playerView().showController() }
                stage("remaining-controls-scope-prior-gesture-evidence-retained")
            }
            nativeTap(androidx.media3.ui.R.id.exo_play_pause)
            stage("native-play-tapped")
            waitFor { playerView().player!!.playWhenReady }
            nativeTap(androidx.media3.ui.R.id.exo_play_pause)
            waitFor { !playerView().player!!.playWhenReady }
            stage("native-pause-passed")
            nativeTap(androidx.media3.ui.R.id.exo_progress,.5f)
            waitFor { val p=playerView().player!!;p.currentPosition in (p.duration*4/10)..(p.duration*6/10) }
            stage("native-timebar-passed")
            instrument.runOnMainSync { playerView().showController() }
            stage("opening-episode-panel")
            ui.onNodeWithText("选集").performClick()
            ui.onNodeWithText("正在播放 · 第1集").assertExists();capture("episodes")
            ui.onNodeWithText("第2集").performClick()
            stage("episode-2-selected")
            waitFor { model.state.value.playback?.episode?.name=="第2集" }
            instrument.runOnMainSync { assertTrue(ui.activity.window.decorView.width>ui.activity.window.decorView.height) }
            waitFor { readyFor(model.state.value.playback) }
            stage("replacement-controller-session-and-media-ready")
            instrument.runOnMainSync { playerView().player!!.pause() }
            waitFor { !playerView().isControllerFullyVisible }
            if(InstrumentationRegistry.getArguments().getString("controlsOnly")=="true") {
                instrument.runOnMainSync {
                    stage("replacement-view-timeout-ms=${playerView().controllerShowTimeoutMs}")
                    playerView().controllerShowTimeoutMs=0
                    playerView().performClick()
                }
                stage("controls-scope-accessibility-click-not-cross-session-touch")
            } else tap(false)
            waitFor { playerView().isControllerFullyVisible }
            ui.onNodeWithText("上一集").assertIsEnabled();ui.onNodeWithText("选集").performClick()
            ui.onNodeWithText("正在播放 · 第2集").assertExists();stage("episode-2-highlight-and-fullscreen-passed")
            ui.onNode(hasScrollToIndexAction()).performScrollToIndex(499)
            ui.onNodeWithText("第500集").performClick()
            waitFor { model.state.value.playback?.episode?.name=="第500集" && readyFor(model.state.value.playback) }
            instrument.runOnMainSync { playerView().player!!.pause();playerView().controllerShowTimeoutMs=0;playerView().showController() }
            ui.onNodeWithText("下一集").assertIsNotEnabled()
            ui.onNodeWithText("选集").performClick()
            ui.onNodeWithText("正在播放 · 第500集").assertIsDisplayed();capture("last-episode")
            stage("lazy-scroll-last-disabled-current-auto-position-passed")
            waitFor { android.view.inspector.WindowInspector.getGlobalWindowViews().any { it !== ui.activity.window.decorView && it.hasWindowFocus() } }
            if(directTouch) instrument.runOnMainSync { ui.activity.onBackPressedDispatcher.onBackPressed() } else instrument.uiAutomation.executeShellCommand("input keyevent 4").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
            // System Back is delivered asynchronously (including predictive Back).
            ui.waitUntil(5000) { ui.onAllNodesWithText("正在播放 · 第500集").fetchSemanticsNodes().isEmpty() }
            ui.onNodeWithText("正在播放 · 第500集").assertDoesNotExist()
            instrument.runOnMainSync { assertTrue(ui.activity.window.decorView.width>ui.activity.window.decorView.height) }
            if(directTouch) instrument.runOnMainSync { ui.activity.onBackPressedDispatcher.onBackPressed() } else instrument.uiAutomation.executeShellCommand("input keyevent 4").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
            waitFor { ui.activity.window.decorView.width<ui.activity.window.decorView.height }
            ui.onNodeWithText("横屏全屏").assertExists();stage("back-panel-then-fullscreen-passed")
            instrument.runOnMainSync { playerView().hideController();playerView().player!!.seekTo(2000) }
            val (heldX,heldY)=point(true);val heldDown=SystemClock.uptimeMillis()
            inject(MotionEvent.ACTION_DOWN,heldX,heldY,heldDown)
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout()+300L)
            instrument.runOnMainSync {
                val field=GesturePlayerView::class.java.getDeclaredField("held").apply { isAccessible=true }
                assertTrue("Held gesture must actually be active before session replacement",field.getBoolean(playerView()))
                val d=model.state.value.detail!!;model.play(d.video,d.lines[0].episodes[2])
            }
            inject(MotionEvent.ACTION_CANCEL,heldX,heldY,heldDown)
            waitFor { model.state.value.playback?.episode?.name=="第3集" && readyFor(model.state.value.playback) }
            instrument.runOnMainSync { playerView().player!!.pause();playerView().player!!.seekTo(1000) }
            SystemClock.sleep(900)
            instrument.runOnMainSync { assertEquals("Old held gesture cannot seek a new session",1000L,playerView().player!!.currentPosition) }
            stage("old-session-held-seek-isolation-passed")
            repeat(2) {
                instrument.runOnMainSync { model.stopPlayback() }
                waitFor { PlaybackMetrics.created==PlaybackMetrics.released }
                instrument.runOnMainSync { val d=model.state.value.detail!!;model.play(d.video,d.lines[0].episodes[0]) }
                waitFor { readyFor(model.state.value.playback) }
            }
            instrument.runOnMainSync { model.stopPlayback() }
            waitFor { PlaybackMetrics.created==PlaybackMetrics.released }
            instrument.runOnMainSync { assertEquals(0,PlaybackMetrics.samplers) }
            instrument.sendStatus(0,android.os.Bundle().apply { putString("gesture_result","${if(directTouch) "isolated View MotionEvents" else "system input"}:controlsOnly=${InstrumentationRegistry.getArguments().getString("controlsOnly")};fullscreen;500episode panel;switch;Back;release passed") })
        } finally {
            watchdog.shutdownNow()
            val dir=java.io.File(instrument.targetContext.getExternalFilesDir(null),"gestures").apply { mkdirs() }
            java.io.File(dir,"touch-timeline.txt").writeText(touchTimeline.joinToString("\n"))
            ui.runOnUiThread { ui.activity.window.callback=originalCallback;model.stopPlayback() };server.shutdown()
        }
    }
}
