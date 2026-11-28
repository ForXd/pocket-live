package org.streampocket

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.net.InetAddress

class DetailLayoutDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var model:AppModel
    private lateinit var server:MockWebServer
    private lateinit var video:Video
    private lateinit var base:String
    @Before fun setup() {
        server=MockWebServer().apply { start() };base="http://detail.test:${server.port}"
        video=Video(Source("星河片库","$base/api"),"gallery","星河漫游",subtitle="2026 · 科幻 / 悬疑",poster="$base/poster",rating=8.5)
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                val path=request.requestUrl!!.encodedPath
                val assets=InstrumentationRegistry.getInstrumentation().context.assets
                if(path=="/poster") return MockResponse().setHeader("Content-Type","image/jpeg").setBody(okio.Buffer().write(assets.open("editorial-poster.jpg").use { it.readBytes() }))
                if(path.endsWith(".mp4")) return MockResponse().setHeader("Content-Type","video/mp4").setBody(okio.Buffer().write(assets.open("test.mp4").use { it.readBytes() }))
                if(path=="/recommend") return MockResponse().setBody("""{"subjects":[]}""")
                val main=(1..500).joinToString("#") { "第${it}集${'$'}$base/main/$it.mp4" }
                val detail=mapOf("vod_id" to "gallery","vod_name" to video.title,
                    "vod_content" to "一次意外的信号，让年轻的观测员踏上寻找失踪家人的旅途。星际航线之外，一个关于记忆与时间的秘密正在浮现。她与一支临时组建的科考队穿越废弃空间站，在陌生星球留下的记录中拼凑真相。随着归航窗口临近，队员们必须决定是遵守撤离命令，还是继续追踪那个无人回应的坐标。这段旅程让每个人重新理解了家与告别。",
                    "vod_play_from" to "主线路${'$'}${'$'}${'$'}备用线路",
                    "vod_play_url" to "$main${'$'}${'$'}${'$'}特别篇${'$'}$base/special.mp4")
                return MockResponse().setBody(com.google.gson.Gson().toJson(mapOf("list" to listOf(detail))))
            }
        }
        ui.runOnUiThread { ui.activity.viewModelStore.clear() }
        runBlocking { Storage(ui.activity.application).write(Saved(allowHttp=true,history=listOf(History(Playback(video,Episode("第17集","$base/main/17.mp4"),5000,duration=10000),System.currentTimeMillis())))) }
        ui.runOnUiThread {
            model=ViewModelProvider(ui.activity,object:ViewModelProvider.Factory {
                override fun <T:ViewModel> create(modelClass:Class<T>):T {
                    @Suppress("UNCHECKED_CAST")
                    return AppModel(ui.activity.application,object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) },{_,_,_->"$base/recommend"}) as T
                }
            })[AppModel::class.java]
            ThemePreference.write(ui.activity,if(InstrumentationRegistry.getArguments().getString("appearance")=="dark") ThemeMode.DARK else ThemeMode.LIGHT)
            ui.activity.setContent { PocketTheme { Pocket(model) } }
        }
        ui.waitUntil(10000) { !model.state.value.loading }
        ui.runOnIdle { model.open(video) }
        ui.waitUntil(10000) { model.state.value.detail!=null }
    }
    @After fun close() { ui.runOnUiThread { ui.activity.viewModelStore.clear() };server.shutdown() }
    @Test fun oneScreenResumeAndEpisodeSheet() {
        ui.onNodeWithTag("detailPlay").assertIsDisplayed().assertContentDescriptionContains("继续观看")
        ui.onNodeWithText("继续第17集").assertIsDisplayed()
        ui.onNodeWithTag("detailPlay").assert(hasText("已看到 00:05 · 星河片库"))
        ui.onNodeWithTag("detailProgress", useUnmergedTree=true).assertRangeInfoEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo(.5f,0f..1f))
        ui.onNodeWithText("展开简介").performClick()
        ui.onNodeWithText("收起简介").performClick()
        ui.onNodeWithTag("detailPlay").performClick()
        ui.waitUntil(5000) { model.state.value.playback?.episode?.url=="$base/main/17.mp4" }
        ui.runOnIdle { model.stopPlayback() }
        ui.onNodeWithTag("detailEpisodes").performClick()
        ui.onNodeWithTag("playbackEpisodeRanges").performScrollToNode(hasTestTag("playbackRange:9"))
        ui.onNodeWithTag("playbackRange:9").performClick()
        ui.onNodeWithText("第451集").assertIsDisplayed()
        ui.onNodeWithTag("detailLine:1").performClick()
        ui.onNodeWithText("特别篇").performClick()
        ui.waitUntil(5000) { model.state.value.playback?.episode?.url=="$base/special.mp4" }
    }
    @Test fun firstVisitShowsFirstEpisodeWithoutHistoryRing() {
        val fresh = Detail(video.copy(id="new"),"",listOf(Line("主线路",listOf(Episode("第1集","$base/main/1.mp4")))))
        ui.runOnUiThread { ui.activity.setContent { PocketTheme { DetailScreen(fresh,model) } } }
        ui.onNodeWithText("第1集").assertIsDisplayed()
        ui.onNodeWithText("星河片库").assertIsDisplayed()
        ui.onNodeWithText("清晰度未知", substring = true).assertDoesNotExist()
        ui.onNodeWithTag("detailProgress", useUnmergedTree=true).assertDoesNotExist()
        ui.onNodeWithTag("detailPlay").assertContentDescriptionContains("立即播放")
    }
    @Test fun emptyDetailDisablesPlayback() {
        ui.runOnUiThread { ui.activity.setContent { PocketTheme { DetailScreen(Detail(video,"",emptyList()),model) } } }
        ui.onNodeWithTag("detailPlay").assertIsNotEnabled()
        ui.onNodeWithTag("detailEpisodes").assertIsNotEnabled()
    }
    @Test fun longEpisodeAndSourceKeepSeparateTouchTargetsAtDoubleFontSize() {
        val source = video.source.copy(name = "星河影视资料馆长片与纪录片精选来源".repeat(5))
        val episode = Episode("第1集：星际航线之外的长夜与归途".repeat(4), "$base/main/1.mp4")
        val fresh = Detail(video.copy(id="long", source=source), "遥远的观测站", listOf(Line("主线路", listOf(episode))))
        ui.runOnUiThread { ui.activity.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                PocketTheme { Box(Modifier.width(320.dp).fillMaxHeight()) { DetailScreen(fresh, model) } }
            }
        } }
        val play = ui.onNodeWithTag("detailPlay").assertHeightIsAtLeast(48.dp)
        val chooser = ui.onNodeWithTag("detailEpisodes").assertHeightIsAtLeast(48.dp)
        assertTrue(play.getUnclippedBoundsInRoot().right < chooser.getUnclippedBoundsInRoot().left)
        for (text in listOf(episode.name, source.name)) {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            ui.onNodeWithText(text, useUnmergedTree=true)
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(2, layouts.single().lineCount)
            assertTrue(layouts.single().isLineEllipsized(1))
        }
        chooser.performTouchInput { click() }
        ui.onNode(hasText(episode.name) and hasAnyAncestor(hasTestTag("playbackEpisodes"))).performClick()
        ui.waitUntil(5000) { model.state.value.playback?.episode?.url == episode.url }
    }
    @Test fun finishedEpisodeRestartsWithoutShowingAnOldBreakpoint() {
        val record = model.state.value.saved.history.single().playback
        val detail = requireNotNull(model.state.value.detail)
        ui.runOnUiThread { ui.activity.setContent { PocketTheme { DetailScreen(detail,model) } } }
        ui.runOnIdle { model.checkpoint(record, record.duration, duration=record.duration) }
        ui.waitUntil(10000) { model.state.value.saved.history.single().playback.watchedToEnd }
        val replay = ui.onNodeWithTag("detailPlay").assertContentDescriptionContains("重新播放")
        replay.assert(hasText("重播第17集"))
        ui.onNodeWithText("已看到", substring=true).assertDoesNotExist()
        replay.performTouchInput { click() }
        ui.waitUntil(5000) { model.state.value.playback?.episode?.url == record.episode.url }
        assertEquals(0L, model.state.value.playback!!.position)
    }
    @Test fun interactiveReviewSession() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("reviewSession")=="true")
        val done=java.io.File(ui.activity.filesDir,"detail-review-done");done.delete()
        captureUi(ui,"detail-review")
        val deadline=android.os.SystemClock.elapsedRealtime()+300000
        while(!done.exists() && android.os.SystemClock.elapsedRealtime()<deadline) { ui.mainClock.advanceTimeBy(100);ui.waitForIdle();android.os.SystemClock.sleep(100) }
    }
}
