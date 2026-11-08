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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class HomeBrowseDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var server:MockWebServer
    private lateinit var model:AppModel
    private val requests=CopyOnWriteArrayList<String>()
    private val failMore=AtomicBoolean(false)
    @Before fun setup() {
        server=MockWebServer().apply { start() }
        val base="http://home.test:${server.port}"
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                val url=request.requestUrl!!
                if(url.encodedPath=="/poster") return MockResponse().setHeader("Content-Type","image/jpeg").setBody(okio.Buffer().write(
                    InstrumentationRegistry.getInstrumentation().context.assets.open("editorial-poster.jpg").use { it.readBytes() }))
                if(url.encodedPath=="/test.mp4") return MockResponse().setHeader("Content-Type","video/mp4").setBody(okio.Buffer().write(
                    InstrumentationRegistry.getInstrumentation().context.assets.open("test.mp4").use { it.readBytes() }))
                requests+=request.path!!
                if(url.encodedPath=="/catalog") {
                    if(url.queryParameter("ids")!=null) return MockResponse().setBody("""{"list":[{"vod_id":"${url.queryParameter("ids")}","vod_name":"片库影片 ${url.queryParameter("ids")}","vod_play_from":"主线路","vod_play_url":"第1集${'$'}$base/test.mp4"}]}""")
                    if(url.queryParameter("ac")=="list") return MockResponse().setBody("""{"class":[{"type_id":1,"type_name":"电影"}],"list":[]}""")
                    val page=url.queryParameter("pg")?.toIntOrNull() ?: 1
                    return MockResponse().setBody("""{"pagecount":2,"list":[${(1..20).joinToString(",") { val id=(page-1)*20+it;"""{"vod_id":"$id","vod_name":"片库影片 $id","type_name":"悬疑","vod_pic":"$base/poster"}""" }}]}""")
                }
                val page=url.queryParameter("page")?.toIntOrNull() ?: 1
                if(page>1 && failMore.get()) return MockResponse().setResponseCode(503)
                // Third page deliberately repeats the second: must stop without an endless request loop.
                val offset=if(page==1) 0 else 20
                return MockResponse().setBody("""{"subjects":[${(1..20).joinToString(",") { val id=offset+it;"""{"id":"$id","title":"${if(id%2==0) "星河深处漫长旅途的故事" else "星河"} $id","rate":"8.5","cover":"$base/poster"}""" }}]}""")
            }
        }
        ui.runOnUiThread { ui.activity.viewModelStore.clear() }
        val source=Source("片库","$base/catalog")
        runBlocking { Storage(ui.activity.application).write(Saved(allowHttp=true,subscriptions=listOf(Subscription("home","片库","$base/sub",1,listOf(source))))) }
        ui.runOnUiThread {
            model=ViewModelProvider(ui.activity,object:ViewModelProvider.Factory {
                override fun <T:ViewModel> create(modelClass:Class<T>):T {
                    @Suppress("UNCHECKED_CAST")
                    return AppModel(ui.activity.application,object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) },{_,_,page->"$base/recommend?page=$page"}) as T
                }
            })[AppModel::class.java]
            ThemePreference.write(ui.activity,if(InstrumentationRegistry.getArguments().getString("appearance")=="dark") ThemeMode.DARK else ThemeMode.LIGHT)
            ui.activity.setContent { PocketTheme { Pocket(model) } }
        }
        ui.waitUntil(10000) { model.state.value.recommendations.size==20 && !model.state.value.recommendLoading }
    }
    @After fun close() { ui.runOnUiThread { ui.activity.viewModelStore.clear() };server.shutdown() }

    @Test fun stickyControlsAlignedPostersPaginationRefreshAndReturnToTop() {
        val grid=ui.onNodeWithTag("recommendationGrid")
        val poster=ui.onNodeWithTag("recommendPoster:1",useUnmergedTree=true).getUnclippedBoundsInRoot()
        assertEquals((poster.right-poster.left).value/0.75f,(poster.bottom-poster.top).value,1f)
        val first=ui.onNodeWithTag("recommendation:1").getUnclippedBoundsInRoot()
        val second=ui.onNodeWithTag("recommendation:2").getUnclippedBoundsInRoot()
        assertEquals(first.top.value,second.top.value,1f);assertEquals(first.bottom.value,second.bottom.value,1f)
        captureUi(ui,"home-expanded")
        grid.performTouchInput { swipeUp(durationMillis=600) }
        ui.onNodeWithTag("searchInput").assertIsNotDisplayed()
        ui.onNodeWithTag("recommendToolbar").assertIsDisplayed()
        ui.onNodeWithTag("discoverySort").assertIsDisplayed()
        val fixedTop=ui.onNodeWithTag("recommendToolbar").getUnclippedBoundsInRoot().top
        grid.performScrollToIndex(17)
        ui.waitUntil(10000) { model.state.value.recommendations.size==40 && !model.state.value.recommendLoading }
        assertEquals(fixedTop.value,ui.onNodeWithTag("recommendToolbar").getUnclippedBoundsInRoot().top.value,1f)
        assertEquals(1,requests.count { it=="/recommend?page=2" })
        captureUi(ui,"home-sticky-paged")
        grid.performScrollToIndex(37)
        ui.waitUntil(10000) { !model.state.value.recommendMore && !model.state.value.recommendLoading }
        assertEquals(40,model.state.value.recommendations.size)
        assertEquals(1,requests.count { it=="/recommend?page=3" })
        ui.onNodeWithTag("backToTop").performClick()
        ui.onNodeWithTag("searchInput").assertIsDisplayed()
        ui.onNodeWithTag("recommendation:1").assertIsDisplayed()
        val refreshes=requests.count { it=="/recommend?page=1" }
        grid.performTouchInput { swipeDown(durationMillis=700) }
        ui.waitUntil(10000) { requests.count { it=="/recommend?page=1" }>refreshes && !model.state.value.recommendLoading }
        assertEquals(20,model.state.value.recommendations.size)
        captureUi(ui,"home-refreshed")
        ui.onNodeWithTag("searchInput").performClick().performTextInput("星河")
        ui.waitUntil(5000) { androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true }
        grid.performTouchInput { swipeUp(durationMillis=600) }
        ui.onNodeWithTag("searchInput").assertIsDisplayed().assertTextContains("星河")
        captureUi(ui,"home-keyboard-pinned")
    }
    @Test fun failedAppendRetainsResultsAndRetriesOnlyOnDemand() {
        failMore.set(true)
        val grid=ui.onNodeWithTag("recommendationGrid")
        grid.performScrollToIndex(17)
        ui.waitUntil(10000) { model.state.value.recommendError!=null }
        assertEquals(20,model.state.value.recommendations.size)
        val failures=requests.count { it=="/recommend?page=2" }
        android.os.SystemClock.sleep(800)
        assertEquals(failures,requests.count { it=="/recommend?page=2" })
        failMore.set(false)
        grid.performScrollToNode(hasText("重试推荐"))
        ui.onNodeWithText("重试推荐").performClick()
        ui.waitUntil(10000) { model.state.value.recommendations.size==40 && !model.state.value.recommendLoading }
        assertNull(model.state.value.recommendError)
    }
    @Test fun catalogFiltersStayPinnedWhileNextPageLoadsAndTopReturns() {
        ui.onNodeWithText("分类搜索").performClick()
        ui.waitUntil(10000) { model.state.value.catalogVideos.size==20 && !model.state.value.catalogLoading }
        val first=ui.onNodeWithTag("catalogVideo:1").getUnclippedBoundsInRoot()
        val second=ui.onNodeWithTag("catalogVideo:2").getUnclippedBoundsInRoot()
        assertEquals(first.top.value,second.top.value,1f)
        assertEquals(first.bottom.value,second.bottom.value,1f)
        val poster=ui.onNodeWithTag("catalogPoster:1",useUnmergedTree=true).getUnclippedBoundsInRoot()
        assertEquals((poster.right-poster.left).value/0.75f,(poster.bottom-poster.top).value,1f)
        val list=ui.onNodeWithTag("catalogList")
        list.performTouchInput { swipeUp(durationMillis=600) }
        ui.onNodeWithTag("catalogSourcePicker").assertIsDisplayed()
        ui.onNodeWithTag("catalogGenrePicker").assertIsDisplayed()
        ui.onNodeWithTag("discoverySort").assertIsDisplayed()
        val top=ui.onNodeWithTag("catalogToolbar").getUnclippedBoundsInRoot().top
        list.performScrollToIndex(18)
        ui.waitUntil(10000) { model.state.value.catalogVideos.size==40 && !model.state.value.catalogLoading }
        assertFalse(model.state.value.catalogMore)
        assertEquals(top.value,ui.onNodeWithTag("catalogToolbar").getUnclippedBoundsInRoot().top.value,1f)
        captureUi(ui,"catalog-sticky-paged")
        ui.onNodeWithTag("backToTop").performClick()
        ui.onNodeWithTag("catalogVideo:1").assertIsDisplayed()
        ui.onNodeWithTag("searchInput").assertIsDisplayed()
    }
    @Test fun searchResultSortAndGenresAlsoRemainPinned() {
        ui.onNodeWithTag("searchInput").performTextInput("片库")
        ui.onNodeWithTag("searchInput").performImeAction()
        ui.waitUntil(10000) { !model.state.value.searching && model.state.value.results.size==20 }
        val results=ui.onNodeWithTag("searchResults")
        results.performTouchInput { swipeUp(durationMillis=600) }
        ui.onNodeWithTag("searchSort").assertIsDisplayed()
        ui.onNodeWithTag("searchCategories").assertIsDisplayed()
        val top=ui.onNodeWithTag("searchToolbar").getUnclippedBoundsInRoot().top
        results.performScrollToIndex(15)
        assertEquals(top.value,ui.onNodeWithTag("searchToolbar").getUnclippedBoundsInRoot().top.value,1f)
        captureUi(ui,"search-sticky")
        ui.onNodeWithTag("backToTop").performClick()
        ui.onNodeWithTag("searchVideo:1").assertIsDisplayed()
        ui.onNodeWithTag("searchInput").assertIsDisplayed()
    }

    @Test fun searchPositionSurvivesDetailPlaybackAndReturnWithoutRefetch() {
        ui.onNodeWithTag("searchInput").performTextInput("片库")
        ui.onNodeWithTag("searchInput").performImeAction()
        ui.waitUntil(10000) { !model.state.value.searching && model.state.value.results.size==20 }
        ui.onNodeWithTag("searchCategories").performScrollToNode(hasTestTag("searchCategory:MYSTERY"))
        ui.onNodeWithTag("searchCategory:MYSTERY").performClick()
        ui.onNodeWithTag("searchSort").performClick()
        ui.onNodeWithText("高清优先").performClick()
        val results=ui.onNodeWithTag("searchResults")
        results.performTouchInput { swipeUp(durationMillis=600) }
        results.performScrollToIndex(12)
        val anchor=ui.onNodeWithTag("searchVideo:12")
        anchor.assertIsDisplayed()
        val before=anchor.getUnclippedBoundsInRoot().top
        val searches=requests.count { it.contains("wd=") }
        repeat(3) {
            anchor.performClick()
            ui.waitUntil(10000) { model.state.value.detail!=null }
            ui.onNodeWithTag("detailPlay").performClick()
            ui.waitUntil(10000) { model.state.value.playback!=null && PlaybackMetrics.created-PlaybackMetrics.released==1L }
            ui.onNodeWithContentDescription("返回选集").performClick()
            ui.waitUntil(10000) { model.state.value.playback==null && PlaybackMetrics.created==PlaybackMetrics.released && PlaybackMetrics.samplers==0 && MediaCallOwner.active.get()==0 }
            ui.onNodeWithContentDescription("返回",useUnmergedTree=true).performClick()
            anchor.assertIsDisplayed()
            assertEquals(before.value,anchor.getUnclippedBoundsInRoot().top.value,1f)
            ui.onNodeWithTag("searchInput").assertIsNotDisplayed()
            assertEquals(searches,requests.count { it.contains("wd=") })
            ui.onNodeWithTag("searchCategory:MYSTERY").assertIsSelected()
            assertEquals(SearchOrder.QUALITY,model.state.value.searchOrder)
        }
        ui.onNodeWithTag("searchSort").performClick()
        ui.onNodeWithText("相关度").performClick()
        ui.onNodeWithTag("searchVideo:1").assertIsDisplayed()
    }

    @Test fun catalogPositionSurvivesPlaybackAndRecommendationPositionSurvivesTabReturn() {
        val recommendations=ui.onNodeWithTag("recommendationGrid")
        recommendations.performTouchInput { swipeUp(durationMillis=600) }
        recommendations.performScrollToIndex(8)
        val recommendation=ui.onNodeWithTag("recommendation:9")
        val recommendationOffset=recommendation.getUnclippedBoundsInRoot().top-recommendations.getUnclippedBoundsInRoot().top
        ui.onNodeWithTag("nav:1").performClick()
        ui.onNodeWithTag("nav:0").performClick()
        recommendation.assertIsDisplayed()
        // Tab switching intentionally reveals search; compare offset relative to the grid.
        assertEquals(recommendationOffset.value,(recommendation.getUnclippedBoundsInRoot().top-recommendations.getUnclippedBoundsInRoot().top).value,1f)
        assertEquals(1,requests.count { it=="/recommend?page=1" })
        ui.onNodeWithText("分类搜索").performClick()
        ui.waitUntil(10000) { !model.state.value.catalogLoading && model.state.value.catalogVideos.size==20 }
        val catalog=ui.onNodeWithTag("catalogList")
        catalog.performTouchInput { swipeUp(durationMillis=600) }
        catalog.performScrollToIndex(8)
        val anchor=ui.onNodeWithTag("catalogVideo:9")
        anchor.assertIsDisplayed()
        val before=anchor.getUnclippedBoundsInRoot().top
        val calls=requests.count { it.startsWith("/catalog") && !it.contains("ids=") }
        anchor.performClick()
        ui.waitUntil(10000) { model.state.value.detail!=null }
        ui.onNodeWithTag("detailPlay").performClick()
        ui.waitUntil(10000) { model.state.value.playback!=null && PlaybackMetrics.created-PlaybackMetrics.released==1L }
        ui.onNodeWithContentDescription("返回选集").performClick()
        ui.onNodeWithContentDescription("返回",useUnmergedTree=true).performClick()
        anchor.assertIsDisplayed()
        assertEquals(before.value,anchor.getUnclippedBoundsInRoot().top.value,1f)
        assertEquals(calls,requests.count { it.startsWith("/catalog") && !it.contains("ids=") })
        ui.onNodeWithTag("discoverySort").performClick()
        ui.onNodeWithText("评分最高").performClick()
        ui.onNodeWithTag("catalogVideo:1").assertIsDisplayed()
    }

    @Test fun pickerContentDragDoesNotDismissButHandleDoes() {
        ui.onNodeWithTag("recommendCategoryPicker").performClick()
        ui.onNodeWithTag("recommendCategorySheet").performTouchInput { swipeDown() }
        ui.onNodeWithTag("recommendCategorySheet").assertIsDisplayed()
        ui.onNodeWithTag("pickerDragHandle").performTouchInput { swipe(start=center,end=center.copy(y=center.y+200f),durationMillis=500) }
        ui.onNodeWithTag("recommendCategorySheet").assertDoesNotExist()
        ui.onNodeWithText("分类搜索").performClick()
        ui.waitUntil(10000) { model.state.value.catalogSource!=null }
        ui.onNodeWithTag("catalogGenrePicker").performClick()
        ui.onNodeWithTag("catalogPicker").performTouchInput { swipeDown() }
        ui.onNodeWithTag("catalogPicker").assertIsDisplayed()
        ui.onNodeWithTag("pickerDragHandle").performTouchInput { swipe(start=center,end=center.copy(y=center.y+200f),durationMillis=500) }
        ui.onNodeWithTag("catalogPicker").assertDoesNotExist()
    }

    @Test fun recommendationCategoryUsesDraftSheetAndResetsForType() {
        val controls=ui.onNodeWithTag("recommendControls")
        controls.performScrollToNode(hasTestTag("recommendType:movie"))
        ui.onNodeWithTag("recommendType:movie").performClick()
        ui.waitUntil(10000) { model.state.value.recommendType=="movie" && !model.state.value.recommendLoading }
        controls.performScrollToNode(hasTestTag("recommendCategoryPicker"))
        ui.onNodeWithTag("recommendCategoryPicker").performClick()
        ui.onNodeWithTag("recommendCategory:经典").performClick()
        assertEquals("热门",model.state.value.recommendTag)
        captureUi(ui,"recommend-category-sheet")
        ui.onNodeWithText("取消").performClick()
        assertEquals("热门",model.state.value.recommendTag)
        ui.onNodeWithTag("recommendCategoryPicker").performClick()
        ui.onNodeWithTag("recommendCategory:经典").performClick()
        ui.onNodeWithTag("applyRecommendCategory").performClick()
        ui.waitUntil(10000) { model.state.value.recommendTag=="经典" && !model.state.value.recommendLoading }
        ui.onNodeWithTag("recommendCategoryPicker").assertContentDescriptionContains("推荐分类：经典")
        controls.performScrollToNode(hasTestTag("recommendType:tv"))
        ui.onNodeWithTag("recommendType:tv").performClick()
        ui.waitUntil(10000) { model.state.value.recommendType=="tv" && model.state.value.recommendTag=="热门" && !model.state.value.recommendLoading }
        val tv=ui.onNodeWithTag("recommendType:tv").getUnclippedBoundsInRoot()
        val movie=ui.onNodeWithTag("recommendType:movie").getUnclippedBoundsInRoot()
        assertEquals(tv.top.value,movie.top.value,1f)
        captureUi(ui,"recommend-one-row")
    }

}
