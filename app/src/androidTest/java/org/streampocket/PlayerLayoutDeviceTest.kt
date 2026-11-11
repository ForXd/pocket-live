package org.streampocket

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Dns
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import java.net.InetAddress

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class PlayerLayoutDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private fun view(v:View):GesturePlayerView? = if(v is GesturePlayerView) v else if(v is ViewGroup) (0 until v.childCount).firstNotNullOfOrNull { view(v.getChildAt(it)) } else null
    @Test fun portraitAndLandscapeActionsKeepPlayerSession() {
        ui.runOnUiThread { ui.activity.viewModelStore.clear() }
        kotlinx.coroutines.runBlocking { Storage(ui.activity.application).write(Saved(allowHttp=true)) }
        val server=MockWebServer();server.start()
        val base="http://fixture.test:${server.port}"
        val assets=InstrumentationRegistry.getInstrumentation().context.assets
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse = if(request.requestUrl!!.encodedPath=="/api") MockResponse().setBody("""{"list":[{"vod_play_from":"高清线路","vod_play_url":"${(1..500).joinToString("#") { "第${it}集${'$'}$base/test.mp4?e=$it" }}"}]}""")
                else MockResponse().setBody(Buffer().write(assets.open("test.mp4").use { it.readBytes() })).setHeader("Content-Type","video/mp4")
        }
        lateinit var model:AppModel
        ui.runOnUiThread {
            ui.activity.viewModelStore.clear()
            ui.activity.requestedOrientation=if (InstrumentationRegistry.getArguments().getString("widePlayer")=="true") android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            model=ViewModelProvider(ui.activity,object:ViewModelProvider.Factory {
                override fun <T:ViewModel> create(clazz:Class<T>):T {
                    @Suppress("UNCHECKED_CAST") return AppModel(ui.activity.application,object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) }) as T
                }
            })[AppModel::class.java]
            ui.activity.setContent { PocketTheme { Pocket(model) } }
        }
        try {
            ui.waitUntil(10000) { !model.state.value.loading }
            assertTrue(model.state.value.saved.allowHttp)
            ui.runOnIdle { model.open(Video(Source("我的影视来源","$base/api"),"1","星河漫游")) }
            ui.waitUntil(10000) { model.state.value.detail!=null || (!model.state.value.detailLoading && model.state.value.detailError!=null) }
            assertNotNull("Detail failure: ${model.state.value.detailError}; message: ${model.state.value.message}",model.state.value.detail)
            val beforeFrames=PlaybackMetrics.frames
            ui.runOnIdle { val d=model.state.value.detail!!;model.play(d.video,d.lines[0].episodes[0]) }
            ui.waitUntil(20000) { PlaybackMetrics.frames>beforeFrames }
            ui.runOnIdle { view(ui.activity.window.decorView)!!.apply { player!!.pause();controllerShowTimeoutMs=0;showController() } }
            if (InstrumentationRegistry.getArguments().getString("widePlayer")=="true") {
                val created=PlaybackMetrics.created
                val fallbacks=PlaybackMetrics.decoderFallbacks
                ui.onNodeWithTag("playerSidePanel").assertIsDisplayed()
                captureUi(ui,"player-wide")
                ui.onNode(hasText("第1集") and isSelected()).performClick()
                assertEquals(created,PlaybackMetrics.created)
                ui.onNodeWithContentDescription("横屏全屏").performClick()
                ui.runOnIdle { view(ui.activity.window.decorView)!!.showController() }
                ui.onNodeWithContentDescription("退出全屏").performClick()
                ui.onNodeWithTag("playerSidePanel").assertIsDisplayed()
                assertEquals(created,PlaybackMetrics.created)
                val frames=PlaybackMetrics.frames
                ui.onNodeWithText("第2集").performClick()
                ui.waitUntil(20000) { model.state.value.playback?.episode?.name=="第2集" && PlaybackMetrics.frames>frames }
                // The software emulator may recover a decoder after resizing its surface.
                assertEquals(created+1+PlaybackMetrics.decoderFallbacks-fallbacks,PlaybackMetrics.created)
                assertEquals(1L,PlaybackMetrics.created-PlaybackMetrics.released)
                ui.onNodeWithContentDescription("返回选集").performClick()
                ui.waitUntil(10000) { model.state.value.playback==null && PlaybackMetrics.created==PlaybackMetrics.released }
                return
            }
            ui.onNodeWithContentDescription("上一集").assertIsNotEnabled()
            ui.onNodeWithContentDescription("下一集").assertHeightIsAtLeast(48.dp)
            ui.onNodeWithText("选集").assertHeightIsAtLeast(48.dp)
            ui.runOnIdle {
                val playerView=view(ui.activity.window.decorView)!!
                assertTrue("Native timestamp must remain visible at large font size",playerView.findViewById<View>(androidx.media3.ui.R.id.exo_position).isShown)
                assertTrue("Native playback settings must remain available",playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings).isShown)
            }
            captureUi(ui,"player-portrait")
            ui.onNodeWithText("选集").performClick()
            captureUi(ui,"player-portrait-episodes")
            ui.onNodeWithText("关闭").performClick()
            ui.runOnIdle { view(ui.activity.window.decorView)!!.showController() }
            val session=model.state.value.playback!!.sessionId
            val created=PlaybackMetrics.created
            ui.onNodeWithContentDescription("更多播放选项").performClick()
            captureUi(ui,"player-options")
            val smooth=model.state.value.smoothPlayback
            ui.onNodeWithText("流畅优先").performClick()
            ui.waitUntil(5000) { model.state.value.smoothPlayback!=smooth }
            ui.onNodeWithText("关闭").performClick()
            assertEquals(session,model.state.value.playback!!.sessionId)
            assertEquals(created,PlaybackMetrics.created)
            ui.onNodeWithContentDescription("横屏全屏").performClick()
            ui.waitUntil(5000) { ui.activity.window.decorView.width>ui.activity.window.decorView.height }
            ui.runOnIdle { view(ui.activity.window.decorView)!!.showController() }
            ui.onNodeWithContentDescription("退出全屏").assertIsDisplayed()
            captureUi(ui,"player-landscape")
            assertEquals(created,PlaybackMetrics.created)
            val windowsBefore=if(android.os.Build.VERSION.SDK_INT>=29) android.view.inspector.WindowInspector.getGlobalWindowViews().size else 0
            ui.onNodeWithText("选集").performClick()
            ui.onNodeWithTag("fullscreenEpisodePanel").assertIsDisplayed()
            val panel=ui.onNodeWithTag("fullscreenEpisodePanel").getUnclippedBoundsInRoot()
            val surface=ui.onNodeWithTag("playbackSurface").getUnclippedBoundsInRoot()
            assertEquals((surface.right-surface.left).value*.5f,(panel.right-panel.left).value,2f)
            repeat(10) { iteration ->
                val instrument=InstrumentationRegistry.getInstrumentation()
                when(iteration) {
                    0 -> instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                    1 -> {
                        val now=android.os.SystemClock.uptimeMillis()
                        val x=ui.activity.window.decorView.width/4f
                        val y=ui.activity.window.decorView.height/2f
                        for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
                            val event=android.view.MotionEvent.obtain(now,now,action,x,y,0)
                            try { instrument.uiAutomation.injectInputEvent(event,true) } finally { event.recycle() }
                        }
                    }
                    else -> ui.onNodeWithText("关闭").performClick()
                }
                ui.onNodeWithTag("fullscreenEpisodePanel").assertDoesNotExist()
                if(android.os.Build.VERSION.SDK_INT>=29) ui.waitUntil(5000) {
                    android.view.inspector.WindowInspector.getGlobalWindowViews().size==windowsBefore
                }
                ui.runOnIdle { view(ui.activity.window.decorView)!!.showController() }
                ui.onNodeWithText("选集").performClick()
                ui.onNodeWithTag("fullscreenEpisodePanel").assertIsDisplayed()
            }
            assertEquals("Opening the glass panel must not recreate playback",created,PlaybackMetrics.created)
            ui.onNodeWithTag("playbackEpisodeRanges").performScrollToNode(hasTestTag("playbackRange:9"))
            ui.onNodeWithTag("playbackRange:9").performClick()
            ui.onNodeWithText("第451集").assertIsDisplayed()
            captureUi(ui,"player-episodes-500")
            ui.onNodeWithTag("playbackCurrent").performClick()
            ui.onNodeWithText("第1集").assertIsDisplayed()
            captureUi(ui,"player-episodes")
            val nextFrames=PlaybackMetrics.frames
            ui.onNodeWithText("第2集").performClick()
            ui.waitUntil(20000) { model.state.value.playback?.episode?.name=="第2集" && PlaybackMetrics.frames>nextFrames }
            // A new session intentionally hides controls. Wait for that effect, then reveal them.
            ui.waitUntil(5000) {
                ui.runOnUiThread { view(ui.activity.window.decorView)?.apply { player?.pause();controllerShowTimeoutMs=0;showController() } }
                ui.onAllNodesWithContentDescription("上一集").fetchSemanticsNodes().isNotEmpty()
            }
            ui.onNodeWithContentDescription("上一集").assertIsEnabled()
            ui.onNodeWithContentDescription("退出全屏").performClick()
            if(ui.activity.resources.configuration.smallestScreenWidthDp<600) {
                ui.waitUntil(5000) { ui.activity.window.decorView.width<ui.activity.window.decorView.height }
            } else {
                // Large-screen Android may retain landscape after leaving fullscreen.
                ui.onNodeWithTag("playerSidePanel").assertIsDisplayed()
            }
            ui.onNodeWithContentDescription("返回选集").performClick()
            ui.waitUntil(10000) { model.state.value.playback==null && PlaybackMetrics.created==PlaybackMetrics.released }
        } finally { ui.runOnUiThread { ui.activity.viewModelStore.clear() };server.shutdown() }
    }
}
