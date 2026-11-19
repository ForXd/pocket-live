package org.streampocket

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.asAndroidBitmap
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
import kotlin.math.roundToInt

class UiLayoutDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun primaryActionsAndSettingsRemainReachable() {
        val server=MockWebServer();server.start()
        val base="http://fixture.test:${server.port}"
        val review=InstrumentationRegistry.getArguments().getString("designCapture")=="true"
        val source=Source(if(review) "溪山源" else "示例来源","$base/api")
        val video=Video(source,"1","星河漫游", "更新至第12集",poster="$base/poster.png")
        val history=History(Playback(video,Episode("第3集","$base/video3.mp4"),72000,duration=180_000),System.currentTimeMillis())
        ui.runOnUiThread { ui.activity.viewModelStore.clear() }
        val subscriptions=buildList {
            add(Subscription("fixture",if(review) "山海片库" else "我的订阅","$base/sub",System.currentTimeMillis(),listOf(source)))
            if(review) add(Subscription("tv","城市频道","$base/tv",System.currentTimeMillis(),listOf(Source("山海频道","$base/live",SourceKind.LIVE))))
        }
        val records=buildList {
            add(history)
            add(history.copy(playback=history.playback.copy(episode=Episode("第2集","$base/video2.mp4"),position=45_000),updated=history.updated-1))
            add(history.copy(playback=history.playback.copy(episode=Episode("第1集","$base/video.mp4"),position=180_000),updated=history.updated-2))
            if(review) add(History(Playback(Video(source,"2","山海之间",poster="$base/forest.jpg"),Episode("第1集","$base/video.mp4"),501000,duration=2607000),history.updated-86400000L))
        }
        runBlocking { Storage(ui.activity.application).write(Saved(
            subscriptions=subscriptions,history=records,searchHistoryEnabled=review,
            recentSearches=if(review) listOf("星河漫游","夜航") else emptyList(),allowHttp=true)) }
        val referenceVideo=java.io.File(ui.activity.getExternalFilesDir(null),"design-reference.mp4")
        val media=if(review && referenceVideo.isFile) referenceVideo.readBytes()
            else InstrumentationRegistry.getInstrumentation().context.assets.open("test.mp4").use { it.readBytes() }
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse = if(request.path in listOf("/forest.jpg","/sea.jpg") || (review && request.path=="/poster.png")) {
                val index=listOf("/poster.png","/forest.jpg","/sea.jpg").indexOf(request.path)
                MockResponse().setHeader("Content-Type","image/jpeg").setBody(okio.Buffer().write(InstrumentationRegistry.getInstrumentation().context.assets.open("review-$index.jpg").use { it.readBytes() }))
            } else if(request.path=="/live") MockResponse().setBody("#EXTM3U\n#EXTINF:-1 group-title=\"综合\",山海综合\n$base/video.mp4\n#EXTINF:-1 group-title=\"纪录\",人文纪实\n$base/video2.mp4\n#EXTINF:-1 group-title=\"影视\",城市影院\n$base/video3.mp4")
            else if(request.path=="/poster.png") MockResponse().setHeader("Content-Type","image/jpeg").setBody(okio.Buffer().write(InstrumentationRegistry.getInstrumentation().context.assets.open("editorial-poster.jpg").use { it.readBytes() }))
            else if(request.requestUrl?.encodedPath?.endsWith(".mp4")==true) MockResponse().setHeader("Content-Type","video/mp4").setBody(okio.Buffer().write(media))
            else if(request.path!!.startsWith("/synopsis")) MockResponse().setBody("<span property=\"v:summary\">一次穿越群星的旅程。每个故事，都值得被认真观看。</span>")
            else if(review && request.path!!.startsWith("/recommend")) MockResponse().setBody("""{"subjects":[{"id":"1","title":"星河漫游","rate":"8.5","cover":"$base/poster.png"},{"id":"2","title":"山海之间","rate":"8.2","cover":"$base/forest.jpg","episodes_info":"更新至第 6 集"},{"id":"3","title":"夜航","cover":"$base/sea.jpg","episodes_info":"悬疑 · 电影"}]}""")
            else if(request.path!!.startsWith("/recommend")) MockResponse().setBody("""{"subjects":[{"id":"1","title":"星河漫游","rate":"8.5","cover":"$base/poster.png"},{"id":"2","title":"山海之间","rate":"8.2","cover":"$base/forest.jpg"},{"id":"3","title":"一段跨越山河与四季的漫长旅程：我们的故事还没有结束","rate":"","cover":"$base/sea.jpg"},{"id":"4","title":"雨","rate":"9.1","episodes_info":"更新至第十二集 · 特别篇与幕后故事持续更新","cover":"$base/poster.png"},{"id":"5","title":"风","rate":"","cover":"$base/sea.jpg"}]}""")
            else if(request.requestUrl!!.queryParameter("ids")!=null) MockResponse().setBody("""{"list":[{"vod_content":"一次穿越群星的旅程。每个故事，都值得被认真观看。","vod_play_from":"高清线路","vod_play_url":"第1集${'$'}$base/video.mp4#第2集${'$'}$base/video2.mp4#第3集${'$'}$base/video3.mp4#第4集${'$'}$base/video4.mp4#第5集${'$'}$base/video5.mp4#第6集${'$'}$base/video6.mp4"}]}""")
            else if(review && request.requestUrl?.queryParameter("wd")==null) MockResponse().setBody("""{"list":[{"vod_id":"2","vod_name":"山海之间","type_name":"剧情","vod_pic":"$base/forest.jpg"},{"vod_id":"3","vod_name":"夜航","type_name":"悬疑","vod_pic":"$base/sea.jpg"}]}""")
            else if(review) MockResponse().setBody("""{"list":[{"vod_id":"1","vod_name":"星河漫游","vod_score":"8.5","vod_pic":"$base/poster.png"},{"vod_id":"3","vod_name":"星河之夜","type_name":"电影","vod_pic":"$base/sea.jpg"}]}""")
            else MockResponse().setBody("""{"list":[{"vod_id":"1","vod_name":"星河漫游","vod_remarks":"更新至第12集","vod_pic":"$base/poster.png"},{"vod_id":"2","vod_name":"山海之间","vod_remarks":"高清","vod_pic":"$base/poster.png"}]}""")
        }
        lateinit var model:AppModel
        ui.runOnUiThread {
            ui.activity.viewModelStore.clear()
            model=ViewModelProvider(ui.activity,object:ViewModelProvider.Factory {
                override fun <T:ViewModel> create(modelClass:Class<T>):T {
                    @Suppress("UNCHECKED_CAST")
                    return AppModel(ui.activity.application,object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) },{_,_,_->"$base/recommend"},synopsisUrl={"$base/synopsis/$it"}) as T
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
            if(InstrumentationRegistry.getArguments().getString("motionReview")=="true") {
                ui.waitUntil(10000) { model.state.value.recommendations.isNotEmpty() }
                ui.mainClock.autoAdvance=false
                val marker=java.io.File(instrument.targetContext.getExternalFilesDir(null),"motion-ready")
                marker.createNewFile()
                try {
                    val end=android.os.SystemClock.elapsedRealtime()+11000
                    while(android.os.SystemClock.elapsedRealtime()<end) {
                        ui.mainClock.advanceTimeByFrame()
                        android.os.SystemClock.sleep(16)
                    }
                } finally { marker.delete();ui.mainClock.autoAdvance=true }
                return
            }
            if(InstrumentationRegistry.getArguments().getString("manualDesignReview")=="true") {
                if (InstrumentationRegistry.getArguments().getString("startFullscreen") == "true") {
                    ui.runOnIdle { model.open(video) }
                    ui.waitUntil(10000) { model.state.value.detail?.video?.id == video.id }
                    ui.runOnIdle { model.play(video, Episode("第2集", "$base/video2.mp4")) }
                    ui.onNodeWithContentDescription("横屏全屏").performClick()
                }
                // Keep the fixture server and Activity available for the entire human review.
                // The reviewer can end the session by closing the preview emulator.
                while(!ui.activity.isFinishing) {
                    ui.mainClock.advanceTimeBy(16)
                    android.os.SystemClock.sleep(16)
                }
                return
            }
            if(InstrumentationRegistry.getArguments().getString("uxReview")=="true") {
                ui.waitUntil(10000) { model.state.value.recommendations.isNotEmpty() }
                val done=java.io.File(ui.activity.filesDir,"ui-review-done");done.delete()
                val deadline=android.os.SystemClock.elapsedRealtime()+180000
                while(!done.exists() && android.os.SystemClock.elapsedRealtime()<deadline) {
                    ui.mainClock.advanceTimeBy(100);ui.waitForIdle();android.os.SystemClock.sleep(100)
                }
                return
            }

            if(InstrumentationRegistry.getArguments().getString("designCapture")=="true") {
                ui.waitUntil(10000) { !model.state.value.recommendLoading && model.state.value.recommendations.isNotEmpty() }
                fun back() { instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK) }
                fun noHomeBack() {
                    ui.onNodeWithContentDescription("返回推荐").assertDoesNotExist()
                    ui.onNodeWithContentDescription("返回").assertDoesNotExist()
                }
                fun assertRightRail(tag: String) {
                    val search=ui.onNodeWithTag("openSearch").getUnclippedBoundsInRoot()
                    val action=ui.onNodeWithTag(tag).assertHeightIsEqualTo(48.dp).getUnclippedBoundsInRoot()
                    assertEquals("Right hit edge of $tag",search.right.value,action.right.value,1f)
                    assertEquals("Center of $tag",(search.left.value+search.right.value)/2,(action.left.value+action.right.value)/2,1f)
                }
                ui.onNodeWithContentDescription("豆瓣评分 8.2 分").assertExists()
                ui.onNodeWithText("更新至第 6 集").assertExists()
                ui.onNodeWithText("豆瓣 8.2",substring=true).assertDoesNotExist()
                capture("design-home")
                for(label in listOf("推荐","分类","直播")) {
                    val results=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    ui.onNodeWithText(label,useUnmergedTree=true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(results) }
                    val layout=results.single()
                    assertTrue("$label: ${layout.size}, ${layout.lineCount} lines, ellipsized=${layout.isLineEllipsized(0)}", layout.lineCount==1 && !layout.isLineEllipsized(0))
                }
                assertRightRail("recommendCategoryPicker")
                noHomeBack()
                ui.onNodeWithTag("recommendCategoryPicker").performClick(); capture("sheet-recommend"); back()
                ui.onNodeWithText("分类").performClick()
                ui.waitUntil(10000) { model.state.value.catalogVideos.size==2 }
                noHomeBack()
                assertRightRail("discoverySort")
                val tabs=ui.onNodeWithTag("recommendToolbar").getUnclippedBoundsInRoot()
                val sourceButton=ui.onNodeWithTag("catalogSourcePicker").assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
                val genreButton=ui.onNodeWithTag("catalogGenrePicker").assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
                val sortButton=ui.onNodeWithTag("discoverySort").getUnclippedBoundsInRoot()
                assertTrue("Space below tabs",sourceButton.top >= tabs.bottom + 7.dp)
                assertTrue("Filters can wrap without intersecting",genreButton.left >= sourceButton.right + 7.dp || genreButton.top >= sourceButton.bottom)
                assertTrue("Sort has a separate hit area",maxOf(sourceButton.right,genreButton.right) <= sortButton.left - 7.dp)
                capture("design-catalog")
                if(InstrumentationRegistry.getArguments().getString("catalogOnly")=="true") return
                for((tag,name) in listOf("catalogSourcePicker" to "catalog-source","catalogGenrePicker" to "genre","discoverySort" to "sort")) {
                    ui.onNodeWithTag(tag).performClick();capture("sheet-$name");back()
                }
                ui.onNodeWithTag("liveTab").performClick()
                ui.waitUntil(10000) { model.state.value.liveChannels.size==3 }
                noHomeBack();assertRightRail("liveRefresh");capture("design-live")
                ui.onNodeWithTag("liveSourcePicker").performClick();capture("sheet-live-source");back()
                val frames=PlaybackMetrics.frames
                ui.onNodeWithTag("liveChannel:山海综合").performClick()
                ui.waitUntil(20000) { PlaybackMetrics.frames>frames }
                ui.onNodeWithText("选集").assertDoesNotExist()
                ui.onNodeWithText("上一集").assertDoesNotExist()
                ui.onNodeWithText("下一集").assertDoesNotExist()
                ui.onNodeWithText("重试选集").assertDoesNotExist()
                val backTarget=ui.onNodeWithContentDescription("返回频道").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp).getUnclippedBoundsInRoot()
                val moreTarget=ui.onNodeWithContentDescription("更多播放选项").assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp).getUnclippedBoundsInRoot()
                assertEquals(backTarget.top.value,moreTarget.top.value,1f)
                capture("design-live-player")
                ui.onNodeWithContentDescription("更多播放选项").performClick();capture("sheet-live-options")
                ui.onNodeWithContentDescription("关闭").performClick()
                ui.onNodeWithContentDescription("返回频道").performClick()
                ui.onNodeWithText("推荐").performClick()
                ui.onNodeWithTag("recommendation:1").performClick()
                ui.waitUntil(10000) { model.state.value.recommendSynopsis!=null }
                assertTrue(model.state.value.searchQuery.isEmpty())
                ui.onNodeWithTag("searchRecommendationSources").assertIsDisplayed()
                capture("design-recommendation-preview")
                ui.onNodeWithContentDescription("关闭详情").performClick()
                ui.onNodeWithTag("recommendation:1").assertIsDisplayed()
                ui.onNodeWithTag("recommendation:1").performClick()
                ui.onNodeWithTag("searchRecommendationSources").performClick()
                ui.waitUntil(10000) { model.state.value.searchQuery=="星河漫游" && !model.state.value.searching }
                assertNull(model.state.value.recommendPreview)
                ui.onNodeWithContentDescription("返回推荐").performClick()


                ui.onNodeWithTag("openSearch").performClick()
                val field = ui.onNodeWithTag("searchInput").getUnclippedBoundsInRoot()
                val arrow = ui.onNodeWithContentDescription("返回推荐").getUnclippedBoundsInRoot()
                assertTrue(arrow.right < field.left)
                assertEquals((arrow.top.value+arrow.bottom.value)/2, (field.top.value+field.bottom.value)/2, 1f)
                capture("design-search-input")
                ui.onNodeWithTag("searchInput").performTextInput("星河")
                ui.onNodeWithTag("searchInput").performImeAction()
                ui.waitUntil(10000) { !model.state.value.searching && model.state.value.results.size==2 }
                capture("design-search")
                ui.onNodeWithText("星河漫游").performClick()
                capture("design-search-expanded")
                ui.onNodeWithTag("sourceChoice:${source.key}:1").performClick()
                ui.waitUntil(10000) { model.state.value.detail!=null }
                capture("design-detail")
                ui.onNodeWithTag("detailPlay").assertIsDisplayed()
                ui.onNodeWithTag("detailEpisodes").performClick();capture("sheet-detail-episodes");back()
                ui.onNodeWithContentDescription("关闭详情").performClick()
                ui.onNodeWithTag("nav:1").performClick()
                capture("design-subscriptions")
                ui.onNodeWithTag("swipeSubscription:fixture").performTouchInput { swipeLeft() }
                capture("design-subscription-swipe")
                ui.onNodeWithContentDescription("删除订阅").performClick()
                capture("sheet-subscription-delete")
                ui.onNodeWithText("取消").performClick()
                ui.onNodeWithTag("swipeSubscription:fixture").performTouchInput { swipeRight() }
                ui.onNodeWithText("分享片源").performClick();capture("sheet-share");back()
                ui.onNodeWithTag("subscription:fixture").performClick();capture("design-subscription-expanded")
                ui.onNodeWithTag("nav:2").performClick()
                capture("design-history")
                ui.onNodeWithContentDescription("搜索历史").performClick()
                ui.onNodeWithTag("historySearch").performTextInput("山海")
                ui.onNodeWithTag("historySearch").performImeAction()
                capture("design-history-search")
                ui.onNodeWithContentDescription("返回历史").performClick()
                val id=HistoryGroups.key(video).stableKey
                ui.onNodeWithTag("swipeHistory:$id").performTouchInput { swipeLeft() }
                capture("design-history-swipe")
                ui.onNodeWithTag("deleteHistory:$id").performClick()
                capture("sheet-history-delete")
                ui.onNodeWithText("取消").performClick()
                ui.onNodeWithTag("nav:3").performClick()
                capture("design-settings")
                for((tag,name) in listOf("settingsNetwork" to "network","settingsStorage" to "storage")) {
                    ui.onNodeWithTag("settingsList").performScrollToNode(hasTestTag(tag))
                    ui.onNodeWithTag(tag).performClick(); capture("design-$name"); ui.onNodeWithContentDescription("返回设置").performClick()
                }
                ui.onNodeWithTag("settingsAppearance").performClick()
                capture("sheet-appearance")
                instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                ui.onNodeWithTag("settingsUpdates").performClick()
                capture("sheet-update")
                instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                ui.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("settingsSourceRules"))
                ui.onNodeWithTag("settingsSourceRules").performClick()
                ui.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("settingsRule:${source.key}"))
                capture("design-rules")
                ui.onNodeWithTag("settingsRule:${source.key}").performClick()
                capture("sheet-source-rules")
                instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                ui.onNodeWithTag("nav:2").performClick()
                ui.onNodeWithContentDescription("筛选历史").performClick()
                capture("sheet-history-filter")
                ui.onNodeWithText("清空历史").performClick()
                capture("sheet-history-clear")
                instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                ui.onNodeWithTag("nav:1").performClick()
                ui.onNodeWithContentDescription("添加订阅").performClick()
                capture("sheet-add-subscription")
                ui.onNodeWithText("粘贴 JSON").performClick()
                capture("sheet-json")
                instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                return
            }
            assertEquals("History artwork survives persistence",video.poster,model.state.value.saved.history.first().playback.video.poster)
            ui.onNodeWithTag("mainNavigation").assertExists()
            ui.onNodeWithTag("recommendationGrid").assertExists()
            ui.onNodeWithContentDescription("外观设置").assertDoesNotExist()
            val navOrder = listOf(0,2,1,3).map { ui.onNodeWithTag("nav:$it").getUnclippedBoundsInRoot() }
            val rail = ui.activity.resources.configuration.screenWidthDp >= 840
            assertTrue("Destination order follows the active navigation axis",navOrder.zipWithNext().all { (a,b) ->
                if(rail) a.bottom<=b.top else a.right<=b.left
            })
            if (!rail) listOf("首页","历史","订阅","设置").forEach {
                ui.onNodeWithContentDescription(it).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            }
            ui.onNodeWithTag("openSearch").performClick()
            ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
            ui.onNodeWithTag("openSearch").assertIsDisplayed().performClick()
            ui.onNodeWithTag("searchInput").performTextInput("星河")
            capture("00-keyboard")
            ui.onNodeWithContentDescription("开始搜索").assertHeightIsAtLeast(48.dp).performClick()
            ui.waitUntil(10000) { !model.state.value.searching && model.state.value.results.size==2 }
            ui.onNodeWithTag("searchResults").performScrollToNode(hasText("星河漫游"))
            ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("星河漫游 封面").fetchSemanticsNodes().isNotEmpty() }
            val result = ui.onNodeWithTag("searchVideo:1", useUnmergedTree=true).getUnclippedBoundsInRoot()
            val cover = ui.onNodeWithContentDescription("星河漫游 封面", useUnmergedTree=true).getUnclippedBoundsInRoot()
            ui.onNodeWithText("搜索结果").assertDoesNotExist()
            val card = ui.onNodeWithTag("searchGroup:${VideoGroups.familyKey(video)}").getUnclippedBoundsInRoot()
            assertEquals(MediaCardInset.value, (cover.left-card.left).value, 1f)
            assertEquals(MediaCardInset.value, (cover.top-card.top).value, 1f)
            assertEquals(result.left.value, cover.left.value, 1f)
            assertEquals(result.top.value, cover.top.value, 1f)
            if (ui.activity.resources.configuration.screenWidthDp < 600) {
                val first = ui.onNodeWithTag("nav:0").getUnclippedBoundsInRoot()
                val last = ui.onNodeWithTag("nav:3").getUnclippedBoundsInRoot()
                val field = ui.onNodeWithTag("searchInput").getUnclippedBoundsInRoot()
                assertEquals((first.right-first.left).value, (last.right-last.left).value, 1f)
                val arrow=ui.onNodeWithContentDescription("返回推荐").getUnclippedBoundsInRoot()
                assertTrue(arrow.right < field.left)
                assertEquals((arrow.top.value+arrow.bottom.value)/2,(field.top.value+field.bottom.value)/2,1f)
                assertEquals(first.top.value, last.top.value, 1f)
            }
            capture("01-search")
            ui.onNodeWithText("星河漫游").performClick()
            ui.onNodeWithTag("sourceChoice:${source.key}:1").performScrollTo().performClick()
            ui.waitUntil(10000) { model.state.value.detail!=null }
            ui.onNodeWithTag("detailPlay").assertIsDisplayed()
            ui.onNodeWithTag("detailPlay").assertContentDescriptionContains("继续观看")
            capture("06-detail")
            val warmCover = requireNotNull(model.posters.cached(video.poster))
            ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            ui.runOnUiThread { ui.activity.application.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) }
            ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            ui.waitForIdle()
            assertSame("Previous page artwork stays warm through ordinary backgrounding",warmCover,model.posters.cached(video.poster))
            ui.onNodeWithContentDescription("关闭详情",useUnmergedTree=true).performClick()
            ui.waitUntil(5000) { model.state.value.detail==null }
            ui.onNodeWithTag("nav:1").performClick()
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
            ui.onNodeWithContentDescription("返回设置").performClick()
            ui.onNodeWithTag("settingsList").performScrollToNode(hasText("存储与缓存"))
            ui.onNodeWithText("存储与缓存").performClick()
            ui.onNodeWithTag("settingsList").performScrollToNode(hasText("清缓存（保留配置）"))
            ui.onNodeWithText("清缓存（保留配置）").assertHeightIsAtLeast(48.dp)
            ui.onNodeWithTag("nav:2").performClick()
            ui.onNodeWithTag("historyGroups").performScrollToNode(hasTestTag("continue:${HistoryGroups.key(video).stableKey}"))
            ui.onNodeWithTag("continue:${HistoryGroups.key(video).stableKey}").assertHeightIsAtLeast(48.dp)
            ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("星河漫游 封面").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("观看历史").assertDoesNotExist()
            capture("04-history")

            ui.onNodeWithTag("nav:0").performClick()
            if(ui.onAllNodesWithContentDescription("返回推荐").fetchSemanticsNodes().isNotEmpty()) ui.onNodeWithContentDescription("返回推荐").performClick()
            ui.waitUntil(10000) { !model.state.value.recommendLoading && model.state.value.recommendations.size==5 }
            ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("星河漫游 封面").fetchSemanticsNodes().isNotEmpty() }
            capture("05-discovery")
            ui.onNodeWithText("最近观看").assertDoesNotExist()
            ui.onNodeWithTag("nav:2").performClick()
            ui.onNodeWithContentDescription("搜索历史").performClick()
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
                if(expectedTitleOffset==null) expectedTitleOffset=title.top.value-card.top.value
                assertEquals("Title anchor",expectedTitleOffset!!,(title.top-card.top).value,1f)
                val bottomInset=(card.bottom-title.bottom).value
                if(id=="4") assertTrue("Episode metadata has a line below the title",bottomInset>10f)
                else assertEquals("Title-only card has no reserved metadata slot",8f,bottomInset,1f)
            }
            grid.performScrollToIndex(2)
            fun bounds(id:String)=ui.onNodeWithTag("recommendation:$id").getUnclippedBoundsInRoot()
            val short=bounds("2")
            val long=bounds("3")
            val nextLeft=bounds("4")
            val nextRight=bounds("5")
            assertEquals(short.left.value,nextLeft.left.value,1f)
            assertEquals(long.left.value,nextRight.left.value,1f)
            assertEquals(short.bottom.value+12f,nextLeft.top.value,1f)
            assertEquals(long.bottom.value+12f,nextRight.top.value,1f)
            assertTrue("The next two covers begin at different heights",nextLeft.top < nextRight.top)
            val dispatcher=ui.activity.onBackPressedDispatcher
            fun drag(edge:Int, fraction:Float=.5f) {
                val width=ui.activity.window.decorView.width.toFloat()
                ui.runOnIdle {
                    val start=if(edge==androidx.activity.BackEventCompat.EDGE_LEFT) 0f else width
                    dispatcher.dispatchOnBackStarted(androidx.activity.BackEventCompat(start,0f,0f,edge))
                    val x=if(edge==androidx.activity.BackEventCompat.EDGE_LEFT) width*fraction else width*(1f-fraction)
                    dispatcher.dispatchOnBackProgressed(androidx.activity.BackEventCompat(x,0f,fraction,edge))
                }
                ui.mainClock.advanceTimeByFrame()
            }
            fun crop(rect:androidx.compose.ui.geometry.Rect):android.graphics.Bitmap {
                val image=ui.onRoot().captureToImage().asAndroidBitmap()
                return android.graphics.Bitmap.createBitmap(image,rect.left.roundToInt(),rect.top.roundToInt(),
                    rect.width.roundToInt(),rect.height.roundToInt())
            }
            capture("waterfall")
            val ratingBounds=ui.onNodeWithTag("recommendRating:4",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            val originalBadge=crop(ratingBounds)
            ui.mainClock.autoAdvance=false
            try {
                ui.onNodeWithTag("recommendation:4").performClick()
                ui.mainClock.advanceTimeBy(144)
                capture("poster-expanding")
                ui.mainClock.advanceTimeBy(480)
                ui.onNodeWithTag("recommendationPreview").assertIsDisplayed()
                capture("poster-expanded")
                drag(androidx.activity.BackEventCompat.EDGE_RIGHT)
                capture("poster-return-half")
                ui.runOnIdle { dispatcher.dispatchOnBackCancelled() }
                ui.mainClock.advanceTimeBy(250)
                ui.onNodeWithContentDescription("关闭详情").assertIsDisplayed()
                assertEquals("4", model.state.value.recommendPreview?.id)
                capture("poster-return-cancelled")
                drag(androidx.activity.BackEventCompat.EDGE_LEFT,1f)
                capture("poster-at-origin")
                assertTrue("Original thumbnail rating is visible before the overlay is disposed",originalBadge.sameAs(crop(ratingBounds)))
                ui.runOnIdle { dispatcher.onBackPressed() }
                ui.mainClock.advanceTimeBy(350)
                assertNull(model.state.value.recommendPreview)
                ui.onNodeWithTag("expandedPoster").assertDoesNotExist()
                assertEquals(nextLeft,bounds("4"))
                capture("poster-returned")
                ui.onNodeWithTag("recommendation:4").performClick()
                ui.mainClock.advanceTimeBy(350)
                ui.onNodeWithContentDescription("关闭详情").performClick()
                ui.mainClock.advanceTimeBy(350)
                assertNull(model.state.value.recommendPreview)
                assertEquals(nextLeft,bounds("4"))
            } finally { ui.mainClock.autoAdvance=true }
            grid.performTouchInput { swipeUp(durationMillis=600) }
            ui.onNodeWithTag("searchInput").assertIsNotDisplayed()
            capture("08-aligned-grid")
            grid.performScrollToIndex(0)
            ui.onNodeWithTag("recommendCategoryPicker").performClick()
            ui.onNodeWithContentDescription("刷新推荐").assertHeightIsAtLeast(48.dp).performClick()
            ui.waitUntil(10000) { !model.state.value.recommendLoading }
            ui.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
                .performScrollToNode(hasTestTag("recommendation:1"))
            ui.onNodeWithTag("recommendation:1").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            capture("07-discovery-action")
            fun smallFrame()=android.graphics.Bitmap.createScaledBitmap(
                ui.onRoot().captureToImage().asAndroidBitmap(),32,64,true)
            val homeFrame=smallFrame()
            ui.onNodeWithTag("recommendation:1").performClick()
            ui.onNodeWithTag("searchRecommendationSources").performClick()
            ui.waitUntil(10000) { !model.state.value.searching && model.state.value.results.size==2 }
            ui.onNodeWithTag("searchInput").assertTextContains("星河漫游")
            ui.onNodeWithTag("expandedPoster").assertDoesNotExist()
            assertNull(model.state.value.recommendPreview)
            ui.mainClock.autoAdvance=false
            try {
                drag(androidx.activity.BackEventCompat.EDGE_LEFT,1f)
                val preview=smallFrame()
                var channelError=0L
                for(y in 0 until preview.height) for(x in 0 until preview.width) {
                    val home=homeFrame.getPixel(x,y)
                    val actual=preview.getPixel(x,y)
                    for(shift in 0..16 step 8) {
                        channelError+=kotlin.math.abs(((home shr shift) and 255)-((actual shr shift) and 255))
                    }
                }
                val meanError=channelError.toFloat()/(preview.width*preview.height*3)
                assertTrue("Search back preview must match home before expansion (mean RGB error $meanError)",meanError<7f)
                capture("recommendation-search-back-preview")
                ui.runOnIdle { dispatcher.onBackPressed() }
                ui.mainClock.advanceTimeBy(240)
                ui.onNodeWithTag("recommendationGrid").assertIsDisplayed()
                ui.onNodeWithTag("expandedPoster").assertDoesNotExist()
                capture("recommendation-search-return")
            } finally { ui.mainClock.autoAdvance=true }
            ui.onNodeWithTag("recommendation:1").performClick()
            ui.onNodeWithTag("searchRecommendationSources").performClick()
            ui.onNodeWithTag("nav:3").performClick()
            ui.onNodeWithTag("searchInput").assertDoesNotExist()
            ui.onNodeWithTag("nav:0").performClick()
            ui.onNodeWithTag("searchResults").assertExists()
            ui.runOnIdle { ui.activity.onBackPressedDispatcher.onBackPressed() }
            ui.onNodeWithTag("recommendationGrid").assertExists()
            assertFalse(model.state.value.searching)
            ui.onNodeWithTag("nav:0").performClick()
            if(ui.onAllNodesWithContentDescription("返回推荐").fetchSemanticsNodes().isNotEmpty()) ui.onNodeWithContentDescription("返回推荐").performClick()
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
