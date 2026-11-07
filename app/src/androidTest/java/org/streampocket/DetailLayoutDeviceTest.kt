package org.streampocket

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
        runBlocking { Storage(ui.activity.application).write(Saved(allowHttp=true,history=listOf(History(Playback(video,Episode("第17集","$base/main/17.mp4"),5000),System.currentTimeMillis())))) }
        ui.runOnUiThread {
            model=ViewModelProvider(ui.activity,object:ViewModelProvider.Factory {
                override fun <T:ViewModel> create(clazz:Class<T>):T {
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
    @Test fun longCatalogJumpStickyToolsResumeAndLineSelectionRemainReachable() {
        val list=ui.onNodeWithTag("detailGrid")
        ui.onNodeWithTag("detailPlay").assertIsDisplayed().assertTextContains("继续观看",substring=true)
        captureUi(ui,"detail-header")
        list.performScrollToNode(hasText("展开简介"));ui.onNodeWithText("展开简介").performClick()
        list.performScrollToNode(hasText("收起简介"));ui.onNodeWithText("收起简介").performClick()
        list.performScrollToNode(hasTestTag("episodeJump"));ui.onNodeWithTag("episodeJump").performClick()
        ui.onNodeWithTag("detailPicker").performScrollToNode(hasTestTag("episodeRange:9"))
        ui.onNodeWithTag("episodeRange:9").performClick()
        ui.onNodeWithTag("episode:第451集").assertIsDisplayed()
        val toolbar=ui.onNodeWithTag("episodeToolbar").getUnclippedBoundsInRoot()
        assertTrue(ui.onNodeWithTag("episode:第451集").getUnclippedBoundsInRoot().top>=toolbar.bottom)
        ui.onNodeWithTag("detailPlay").assertIsDisplayed()
        ui.onNodeWithTag("detailBackToTop").assertIsDisplayed()
        ui.onNodeWithTag("backToTop").assertDoesNotExist()
        val sourceButton=ui.onNodeWithTag("detailLinePicker").getUnclippedBoundsInRoot()
        val sameButton=ui.onNodeWithTag("detailSearchSame").getUnclippedBoundsInRoot()
        assertEquals(sourceButton.top.value,sameButton.top.value,1f)
        assertEquals(sourceButton.bottom.value,sameButton.bottom.value,1f)
        assertTrue(sourceButton.right<=sameButton.left)
        ui.onNodeWithTag("detailPlay").assertTextEquals("继续观看")
        captureUi(ui,"detail-jump")
        ui.onNodeWithTag("episodeOrder").performClick()
        ui.onNodeWithTag("episodeOrder").assertIsOn().assertContentDescriptionContains("当前倒序，切换正序")
        ui.onNodeWithTag("episode:第500集").assertIsDisplayed()
        captureUi(ui,"detail-last-episode")
        ui.onNodeWithTag("detailPlay").performClick()
        ui.waitUntil(5000) { model.state.value.playback?.episode?.url=="$base/main/17.mp4" }
        ui.runOnIdle { model.stopPlayback() }
        ui.waitUntil(5000) { model.state.value.playback==null }
        ui.onNodeWithTag("detailLinePicker").performClick()
        captureUi(ui,"detail-lines")
        ui.onNodeWithTag("detailLine:1").performClick()
        ui.onNodeWithTag("episode:特别篇").assertIsDisplayed().performClick()
        ui.waitUntil(5000) { model.state.value.playback?.episode?.url=="$base/special.mp4" }
        ui.runOnIdle { model.stopPlayback() }
        ui.onNodeWithTag("detailSearchSame").performClick()
        ui.waitUntil(5000) { model.state.value.detail==null }
        ui.onNodeWithTag("searchInput").assertTextContains(video.title)
    }
    @Test fun emptyDetailKeepsRecoveryAction() {
        ui.runOnUiThread { ui.activity.setContent { PocketTheme { DetailScreen(Detail(video,"",emptyList()),model,{}) } } }
        ui.onNodeWithTag("detailPlay").assertIsNotEnabled()
        ui.onNodeWithTag("detailSearchSame").assertIsEnabled().assertIsDisplayed()
    }
    @Test fun interactiveReviewSession() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("reviewSession")=="true")
        val done=java.io.File(ui.activity.filesDir,"detail-review-done");done.delete()
        captureUi(ui,"detail-review")
        val deadline=android.os.SystemClock.elapsedRealtime()+300000
        while(!done.exists() && android.os.SystemClock.elapsedRealtime()<deadline) { ui.mainClock.advanceTimeBy(100);ui.waitForIdle();android.os.SystemClock.sleep(100) }
    }
}
