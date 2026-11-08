package org.streampocket

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.net.InetAddress

class UiLayoutDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun primaryActionsAndSettingsRemainReachable() {
        val server=MockWebServer();server.start()
        val base="http://fixture.test:${server.port}"
        val source=Source("示例来源","$base/api")
        val video=Video(source,"1","星河漫游", "更新至第12集",poster="$base/poster.png")
        val history=History(Playback(video,Episode("第3集","$base/video3.mp4"),72000),System.currentTimeMillis())
        ui.runOnUiThread { ui.activity.viewModelStore.clear() }
        runBlocking { Storage(ui.activity.application).write(Saved(subscriptions=listOf(Subscription("fixture","我的订阅","$base/sub",System.currentTimeMillis(),listOf(source))),history=listOf(history),allowHttp=true)) }
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse = if(request.path=="/poster.png") MockResponse().setHeader("Content-Type","image/jpeg").setBody(okio.Buffer().write(InstrumentationRegistry.getInstrumentation().context.assets.open("editorial-poster.jpg").use { it.readBytes() }))
            else if(request.path!!.startsWith("/recommend")) MockResponse().setBody("""{"subjects":[{"id":"1","title":"星河漫游","rate":"8.5","cover":"$base/poster.png"},{"id":"2","title":"山海之间","rate":"8.2","cover":"$base/poster.png"},{"id":"3","title":"一段跨越山河与四季的漫长旅程：我们的故事还没有结束","rate":"","cover":""},{"id":"4","title":"雨","rate":"9.1","episodes_info":"更新至第十二集 · 特别篇与幕后故事持续更新","cover":"$base/poster.png"},{"id":"5","title":"风","rate":"","cover":"$base/poster.png"}]}""")
            else if(request.requestUrl!!.queryParameter("ids")!=null) MockResponse().setBody("""{"list":[{"vod_content":"一次穿越群星的旅程。每个故事，都值得被认真观看。","vod_play_from":"高清线路","vod_play_url":"第1集${'$'}$base/video.mp4#第2集${'$'}$base/video2.mp4#第3集${'$'}$base/video3.mp4#第4集${'$'}$base/video4.mp4#第5集${'$'}$base/video5.mp4#第6集${'$'}$base/video6.mp4"}]}""")
            else MockResponse().setBody("""{"list":[{"vod_id":"1","vod_name":"星河漫游","vod_remarks":"更新至第12集","vod_pic":"$base/poster.png"},{"vod_id":"2","vod_name":"山海之间","vod_remarks":"高清","vod_pic":"$base/poster.png"}]}""")
        }
        lateinit var model:AppModel
        ui.runOnUiThread {
            ui.activity.viewModelStore.clear()
            model=ViewModelProvider(ui.activity,object:ViewModelProvider.Factory {
                override fun <T:ViewModel> create(modelClass:Class<T>):T {
                    @Suppress("UNCHECKED_CAST")
                    return AppModel(ui.activity.application,object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) },{_,_,_->"$base/recommend"}) as T
                }
            })[AppModel::class.java]
            ThemePreference.write(ui.activity,if(InstrumentationRegistry.getArguments().getString("appearance")=="dark") ThemeMode.DARK else ThemeMode.LIGHT)
            ui.activity.setContent { PocketTheme { Pocket(model) } }
        }
        val instrument=InstrumentationRegistry.getInstrumentation()
        fun capture(name:String) {
            ui.waitForIdle()
            android.os.SystemClock.sleep(450) // Let platform IME/inset and navigation animations settle before pixels.
            ui.waitForIdle()
            instrument.waitForIdleSync()
            val dir=java.io.File(instrument.targetContext.getExternalFilesDir(null),"ui-v0100-${InstrumentationRegistry.getArguments().getString("appearance","light")}").apply { mkdirs() }
            instrument.uiAutomation.takeScreenshot()?.let { b -> try { java.io.File(dir,"$name.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } } finally { b.recycle() } }
        }
        try {
            ui.waitUntil(10000) { !model.state.value.loading }
            assertEquals("History artwork survives persistence",video.poster,model.state.value.saved.history.single().playback.video.poster)
            ui.onNodeWithTag("navigationRail").assertDoesNotExist()
            ui.onNodeWithTag("recommendationGrid").assertExists()
            ui.onNodeWithContentDescription("外观设置").assertDoesNotExist()
            assertTrue(ui.onNodeWithTag("nav:2").getUnclippedBoundsInRoot().left<ui.onNodeWithTag("nav:1").getUnclippedBoundsInRoot().left)
            assertTrue(ui.onNodeWithTag("nav:3").getUnclippedBoundsInRoot().left>ui.onNodeWithTag("nav:2").getUnclippedBoundsInRoot().left)
            ui.onNodeWithTag("searchInput").performTextInput("星河")
            capture("00-keyboard")
            ui.onNodeWithContentDescription("开始搜索").assertHeightIsAtLeast(48.dp).performClick()
            ui.waitUntil(10000) { !model.state.value.searching && model.state.value.results.size==2 }
            ui.onNodeWithTag("searchResults").performScrollToNode(hasText("星河漫游"))
            ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("星河漫游 封面").fetchSemanticsNodes().isNotEmpty() }
            capture("01-search")
            ui.onNodeWithText("星河漫游").performClick()
            ui.waitUntil(10000) { model.state.value.detail!=null }
            ui.onNodeWithTag("detailPlay").assertIsDisplayed()
            ui.onNodeWithTag("detailPlay").assertTextContains("继续观看",substring=true)
            ui.onNodeWithTag("detailGrid").performScrollToIndex(0)
            capture("06-detail")
            ui.onNodeWithContentDescription("返回",useUnmergedTree=true).performClick()
            ui.waitUntil(5000) { model.state.value.detail==null }
            ui.onNodeWithText("订阅",useUnmergedTree=true).performClick()
            ui.onNodeWithText("订阅管理").assertDoesNotExist()
            capture("02-subscriptions")
            ui.onNodeWithText("播放与网络").assertDoesNotExist()
            ui.onNodeWithTag("nav:3").performClick()
            ui.onNodeWithTag("settingsAppearance").assertIsDisplayed()
            ui.onNodeWithTag("settingsUpdates").assertIsDisplayed()
            ui.onNodeWithTag("settingsList").performScrollToNode(hasText("播放与网络"))
            ui.onNodeWithText("播放与网络").performClick()
            ui.onNodeWithTag("settingsList").performScrollToNode(hasText("允许未加密 HTTP"))
            ui.onNodeWithText("允许未加密 HTTP").assertIsDisplayed()
            capture("03-settings")
            ui.onNodeWithTag("settingsList").performScrollToNode(hasText("自动 HLS 广告过滤"))
            ui.onNodeWithText("自动 HLS 广告过滤").performClick()
            ui.waitUntil(5000) { model.state.value.saved.adFilter }
            ui.onNodeWithText("自动 HLS 广告过滤").performClick()
            ui.waitUntil(5000) { !model.state.value.saved.adFilter }
            ui.onNodeWithTag("settingsList").performScrollToNode(hasText("存储与缓存"))
            ui.onNodeWithText("存储与缓存").performClick()
            ui.onNodeWithTag("settingsList").performScrollToNode(hasText("清缓存（保留配置）"))
            ui.onNodeWithText("清缓存（保留配置）").assertHeightIsAtLeast(48.dp)
            ui.onNodeWithText("历史",useUnmergedTree=true).performClick()
            ui.onNodeWithTag("historyGroups").performScrollToNode(hasTestTag("continue:${HistoryGroups.key(video).stableKey}"))
            ui.onNodeWithTag("continue:${HistoryGroups.key(video).stableKey}").assertHeightIsAtLeast(48.dp)
            ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("星河漫游 封面").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("观看历史").assertDoesNotExist()
            capture("04-history")
            ui.onNodeWithTag("nav:0").performClick()
            if(ui.onAllNodesWithContentDescription("清空并返回推荐").fetchSemanticsNodes().isNotEmpty()) ui.onNodeWithContentDescription("清空并返回推荐").performClick()
            ui.waitUntil(10000) { !model.state.value.recommendLoading && model.state.value.recommendations.size==5 }
            ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("星河漫游 封面").fetchSemanticsNodes().isNotEmpty() }
            capture("05-discovery")
            ui.onNodeWithText("最近观看").assertDoesNotExist()
            ui.onNodeWithTag("nav:2").performClick()
            ui.onNodeWithTag("historySearch").performTextInput("星河")
            ui.onNodeWithTag("historySearch").performImeAction()
            ui.onNodeWithTag("historyGroups").performScrollToNode(hasTestTag("continue:${HistoryGroups.key(video).stableKey}"))
            ui.onNodeWithTag("continue:${HistoryGroups.key(video).stableKey}").assertIsDisplayed()
            ui.onNodeWithTag("historySearch").performTextReplacement("不存在")
            ui.onNodeWithText("没有匹配记录").assertIsDisplayed()
            ui.onNodeWithTag("historySearch").performTextClearance()
            instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            capture("08-history-query")
            if(InstrumentationRegistry.getArguments().getString("uxReview")=="true") {
                val done=java.io.File(ui.activity.filesDir,"history-review-done");done.delete()
                val deadline=android.os.SystemClock.elapsedRealtime()+180000
                while(!done.exists() && android.os.SystemClock.elapsedRealtime()<deadline) {
                    ui.mainClock.advanceTimeBy(100);ui.waitForIdle();android.os.SystemClock.sleep(100)
                }
            }

            ui.onNodeWithTag("nav:0").performClick()
            ui.onNodeWithText("推荐",useUnmergedTree=true).performClick()
            val grid=ui.onNodeWithTag("recommendationGrid")
            var expectedHeight:Float?=null
            var expectedTitleOffset:Float?=null

            for(id in listOf("2","3","4","5")) {
                grid.performScrollToNode(hasTestTag("recommendation:$id"))
                val card=ui.onNodeWithTag("recommendation:$id").getUnclippedBoundsInRoot()
                val title=ui.onNodeWithTag("recommendTitle:$id",useUnmergedTree=true).getUnclippedBoundsInRoot()
                val poster=ui.onNodeWithTag("recommendPoster:$id",useUnmergedTree=true).getUnclippedBoundsInRoot()
                assertEquals("Poster touches left edge",card.left.value,poster.left.value,1f)
                if((card.right-card.left).value<240f) assertEquals("Poster touches right edge",card.right.value,poster.right.value,1f)
                assertEquals("Poster touches top edge",card.top.value,poster.top.value,1f)
                if(id in listOf("3","5")) ui.onNodeWithTag("recommendRating:$id",useUnmergedTree=true).assertDoesNotExist()
                else ui.onNodeWithTag("recommendRating:$id",useUnmergedTree=true).assertExists()
                if(expectedHeight==null) {
                    expectedHeight=(card.bottom-card.top).value;expectedTitleOffset=title.top.value-card.top.value
                } else {
                    assertEquals("Mixed title/rating card height",expectedHeight!!,(card.bottom-card.top).value,1f)
                    assertEquals("Title anchor",expectedTitleOffset!!,title.top.value-card.top.value,1f)
                }
            }
            grid.performTouchInput { swipeUp(durationMillis=600) }
            ui.onNodeWithTag("searchInput").assertIsNotDisplayed()
            capture("08-aligned-grid")
            grid.performScrollToIndex(0)
            ui.onNodeWithContentDescription("刷新推荐").assertHeightIsAtLeast(48.dp).performClick()
            ui.waitUntil(10000) { !model.state.value.recommendLoading }
            ui.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
                .performScrollToNode(hasTestTag("recommendation:1"))
            ui.onNodeWithTag("recommendation:1").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            capture("07-discovery-action")
            ui.onNodeWithTag("recommendation:1").performClick()
            ui.waitUntil(10000) { !model.state.value.searching && model.state.value.results.size==2 }
            ui.onNodeWithTag("searchInput").assertTextContains("星河漫游")
            ui.onNodeWithTag("nav:3").performClick()
            ui.onNodeWithTag("searchInput").assertDoesNotExist()
            ui.onNodeWithTag("nav:0").performClick()
            ui.onNodeWithTag("searchResults").assertExists()
            ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
            ui.onNodeWithTag("recommendationGrid").assertExists()
            assertFalse(model.state.value.searching)
            ui.onNodeWithTag("nav:0").performClick()
            if(ui.onAllNodesWithContentDescription("清空并返回推荐").fetchSemanticsNodes().isNotEmpty()) ui.onNodeWithContentDescription("清空并返回推荐").performClick()
            ui.onNodeWithTag("continueWatching").assertDoesNotExist()
            ui.onNodeWithTag("nav:2").performClick()
            ui.onNodeWithTag("historyGroups").performScrollToNode(hasTestTag("continue:${HistoryGroups.key(video).stableKey}"))
            ui.onNodeWithTag("continue:${HistoryGroups.key(video).stableKey}").performClick()
            ui.waitUntil(5000) { model.state.value.playback!=null }
            assertEquals(72000L,model.state.value.playback!!.position)
            ui.runOnIdle { model.stopPlayback() }
            ui.waitUntil(10000) { PlaybackMetrics.created==PlaybackMetrics.released }

        } finally { ui.runOnUiThread { ui.activity.viewModelStore.clear() };server.shutdown() }
    }
}
