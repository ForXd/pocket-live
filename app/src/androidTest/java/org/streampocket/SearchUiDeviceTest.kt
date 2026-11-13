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

class SearchUiDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun relevanceCategoriesAliasesAndCatalogSearch() {
        val server = MockWebServer().apply { start() }
        val base = "http://search.test:${server.port}"
        val source = Source("光影片库", "$base/api")
        ui.runOnUiThread { ui.activity.viewModelStore.clear() }
        runBlocking { Storage(ui.activity.application).write(Saved(allowHttp=true, subscriptions=listOf(
            Subscription("search", "我的片库", "$base/sub", 1, listOf(source))))) }
        val requests = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher = object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                requests.add(request)
                val url=request.requestUrl!!
                if(url.encodedPath=="/poster") return MockResponse().setHeader("Content-Type","image/jpeg").setBody(okio.Buffer().write(
                    InstrumentationRegistry.getInstrumentation().context.assets.open("editorial-poster.jpg").use { it.readBytes() }))
                if(url.encodedPath=="/recommend") return MockResponse().setBody("""{"subjects":[]}""")
                if(url.queryParameter("ids")!=null) return MockResponse().setBody("""{"list":[{"vod_content":"星河故事","vod_play_from":"线路一","vod_play_url":"正片${'$'}$base/movie.mp4"}]}""")
                if(url.queryParameter("t")=="7") return MockResponse().setBody("""{"list":[{"vod_id":"love","vod_name":"星河之恋","type_id":7,"vod_pic":"$base/poster"}]}""")
                if(url.queryParameter("t")=="2") return MockResponse().setBody("""{"list":[{"vod_id":"series","vod_name":"星河 第二季","type_id":2,"type_name":"国产剧","vod_class":"悬疑,犯罪","vod_pic":"$base/poster"}]}""")
                return MockResponse().setBody("""{"class":[{"type_id":1,"type_name":"电影"},{"type_id":2,"type_name":"电视剧"},{"type_id":7,"type_name":"爱情片"}],"list":[
                    {"vod_id":"noise","vod_name":"山海之间","type_name":"动作片","vod_width":3840,"vod_height":2160,"vod_pic":"$base/poster"},
                    {"vod_id":"series","vod_name":"星河 第二季","type_id":2,"type_name":"国产剧","vod_class":"悬疑,犯罪","vod_remarks":"更新至12集","vod_pic":"$base/poster"},
                    {"vod_id":"exact","vod_name":"星河","type_id":1,"type_name":"电影","vod_class":"爱情","vod_year":"2026","vod_remarks":"1080p","vod_pic":"$base/poster"},
                    {"vod_id":"alias","vod_name":"Galaxy","vod_sub":"星河","vod_pic":"$base/poster"}]}""")
            }
        }
        lateinit var model:AppModel
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
        fun capture(name:String) {
            ui.waitForIdle(); android.os.SystemClock.sleep(400)
            val instrument=InstrumentationRegistry.getInstrumentation()
            val dir=java.io.File(instrument.targetContext.getExternalFilesDir(null),"search-v026-${InstrumentationRegistry.getArguments().getString("appearance","light")}").apply { mkdirs() }
            instrument.uiAutomation.takeScreenshot()?.let { b -> try { java.io.File(dir,"$name.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } } finally { b.recycle() } }
        }
        fun top() { ui.onNodeWithTag("searchResults").performScrollToIndex(0) }
        try {
            ui.waitUntil(10000) { !model.state.value.loading }
            ui.onNodeWithTag("openSearch").performClick()
            ui.onNodeWithTag("searchInput").performTextReplacement("星河")
            ui.onNodeWithContentDescription("开始搜索").performClick()
            ui.waitUntil(10000) { !model.state.value.searching && model.state.value.results.size==4 }
            assertEquals("exact",model.state.value.results.first().id)
            ui.onNodeWithTag("searchVideo:noise").assertDoesNotExist()
            ui.onNodeWithTag("searchVideo:exact").assertExists()
            capture("01-results")
            ui.onNodeWithTag("searchCategories").performScrollToNode(hasTestTag("searchCategory:MYSTERY"))
            ui.onNodeWithTag("searchCategory:MYSTERY").performClick()
            ui.onNodeWithTag("searchVideo:exact").assertDoesNotExist()
            ui.onNodeWithTag("searchVideo:series").assertExists()
            capture("02-mystery")
            ui.onNodeWithTag("searchVideo:series").performClick()
            ui.onNodeWithTag("sourceChoice:${source.key}:series").performScrollTo().performClick()
            ui.waitUntil(10000) { model.state.value.detail!=null }
            ui.onNodeWithContentDescription("返回",useUnmergedTree=true).performClick()
            ui.onNodeWithTag("searchCategory:MYSTERY").assertIsSelected()
            ui.onNodeWithTag("searchCategories").performScrollToNode(hasTestTag("searchCategory:ALL"))
            ui.onNodeWithTag("searchCategory:ALL").performClick()
            ui.onNodeWithTag("searchResults").performScrollToNode(hasTestTag("searchVideo:alias"))
            ui.onNodeWithTag("searchVideo:alias").assertExists()
            top()
            ui.onNodeWithTag("showOtherResults").performClick()
            ui.onNodeWithTag("searchSort").performClick()
            ui.onNodeWithText("高清优先",useUnmergedTree=true).performClick()
            assertEquals("noise",model.state.value.results.first().id)
            ui.onNodeWithTag("searchVideo:noise").assertExists()
            // The search bar collapses after scrolling; reveal it before physical touch.
            ui.onNodeWithTag("searchResults").performTouchInput { swipeDown() }
            ui.onNodeWithContentDescription("清空并返回推荐").assertIsDisplayed().performClick()
            capture("03-returned-home")
            ui.onNodeWithText("分类").performClick()
            ui.waitUntil(10000) { model.state.value.categories.isNotEmpty() }
            ui.onNodeWithTag("searchInput").performTextReplacement("星河")
            ui.onNodeWithTag("catalogGenrePicker").performClick()
            ui.onNodeWithTag("catalogPicker").performScrollToNode(hasTestTag("sourceCategory:2"))
            ui.onNodeWithTag("sourceCategory:2").performClick()
            ui.onNodeWithTag("applyCatalogFilters").performClick()
            ui.waitUntil(10000) { !model.state.value.catalogLoading && model.state.value.catalogQuery=="星河" }
            assertTrue(requests.any { it.requestUrl?.queryParameter("t")=="2" && it.requestUrl?.queryParameter("wd")=="星河" })
            ui.onNodeWithTag("catalogList").performScrollToNode(hasTestTag("catalogVideo:series"))
            ui.onAllNodes(hasSetTextAction()).assertCountEquals(1)
            capture("03-catalog")
            ui.onNodeWithTag("catalogList").performScrollToIndex(0)
            ui.onNodeWithTag("catalogGenrePicker").performClick()
            capture("04-genre-picker")
            val genrePositions=Genre.entries.filter { it!=Genre.UNKNOWN }.associateWith { ui.onNodeWithTag("catalogGenre:${it.name}").getUnclippedBoundsInRoot() }
            ui.onNodeWithTag("catalogGenre:ROMANCE").performClick()
            ui.waitForIdle()
            genrePositions.forEach { (option,before) ->
                val after=ui.onNodeWithTag("catalogGenre:${option.name}").getUnclippedBoundsInRoot()
                assertEquals("Genre selection must not move options horizontally",before.left.value,after.left.value,1f)
                assertEquals("Genre selection must not reflow rows",before.top.value,after.top.value,1f)
            }
            ui.onNodeWithTag("applyCatalogFilters").performClick()
            ui.waitUntil(10000) { !model.state.value.catalogLoading && model.state.value.catalogGenres==setOf(Genre.ROMANCE) }
            assertTrue(requests.any { it.requestUrl?.queryParameter("t")=="7" && it.requestUrl?.queryParameter("wd")=="星河" })
            ui.onNodeWithTag("catalogList").performScrollToNode(hasTestTag("catalogVideo:love"))
            ui.onNodeWithTag("catalogVideo:series").assertDoesNotExist()
            capture("04-romance")
            ui.onNodeWithTag("catalogList").performScrollToIndex(0)
            ui.onNodeWithTag("searchInput").performTextReplacement("")
            ui.onNodeWithTag("searchInput").performImeAction()
            ui.waitUntil(10000) { !model.state.value.catalogLoading && model.state.value.catalogQuery.isEmpty() }
            assertTrue(requests.any { it.requestUrl?.queryParameter("t")=="7" && it.requestUrl?.queryParameter("wd")==null })
            ui.onNodeWithTag("catalogList").performScrollToIndex(0)
            ui.onNodeWithTag("catalogGenrePicker").performClick()
            ui.onNodeWithTag("catalogGenre:ROMANCE").performClick()
            ui.onNodeWithTag("catalogGenre:MYSTERY").performClick()
            ui.onNodeWithTag("applyCatalogFilters").performClick()
            ui.waitUntil(10000) { !model.state.value.catalogLoading && model.state.value.catalogGenres==setOf(Genre.MYSTERY) }
            ui.onNodeWithTag("catalogList").performScrollToNode(hasTestTag("catalogVideo:series"))
            ui.onNodeWithTag("catalogVideo:exact").assertDoesNotExist()
            capture("05-mystery-catalog")
            ui.onNodeWithTag("catalogList").performScrollToIndex(0)
            ui.onNodeWithTag("catalogGenrePicker").performClick()
            ui.onNodeWithTag("catalogGenre:ROMANCE").performClick()
            ui.onNodeWithTag("catalogGenre:MYSTERY").assertIsSelected()
            capture("06-multi-picker")
            ui.onNodeWithTag("applyCatalogFilters").performClick()
            ui.waitUntil(10000) { !model.state.value.catalogLoading && model.state.value.catalogGenres.size==2 }
            assertTrue(model.state.value.catalogVideos.any { it.id=="series" })
            assertTrue(model.state.value.catalogVideos.any { it.id=="love" })
            assertTrue(model.state.value.catalogVideos.none { it.id=="noise" })
            capture("07-multi-results")
            // Dismissal discards edits; applying no selections means no genre restriction.
            ui.onNodeWithTag("catalogGenrePicker").performClick()
            ui.onNodeWithTag("clearCatalogFilters").performClick()
            ui.onNodeWithText("取消",useUnmergedTree=true).performClick()
            assertEquals(setOf(Genre.ROMANCE,Genre.MYSTERY),model.state.value.catalogGenres)
            ui.onNodeWithTag("activeGenre:ROMANCE").performClick()
            ui.waitUntil(10000) { !model.state.value.catalogLoading && model.state.value.catalogGenres==setOf(Genre.MYSTERY) }
            ui.onNodeWithTag("activeGenre:MYSTERY").performClick()
            ui.waitUntil(10000) { !model.state.value.catalogLoading && model.state.value.catalogGenres.isEmpty() }
            assertTrue(model.state.value.catalogVideos.any { it.id=="noise" })
            ui.onNodeWithTag("catalogGenrePicker").performClick()
            ui.onNodeWithTag("catalogGenre:ROMANCE").performClick()
            ui.onNodeWithTag("catalogGenre:ROMANCE").performClick()
            ui.onNodeWithTag("applyCatalogFilters").performClick()
            ui.waitUntil(10000) { !model.state.value.catalogLoading }
            assertTrue(model.state.value.catalogGenres.isEmpty())
            capture("08-unrestricted")
            if(InstrumentationRegistry.getArguments().getString("reviewSession")=="true") {
                // Optional manual review keeps the controlled source reachable; ordinary tests never wait here.
                val done=java.io.File(ui.activity.filesDir,"search-review-done").apply { delete() }
                val deadline=android.os.SystemClock.elapsedRealtime()+300_000
                while(!done.exists() && android.os.SystemClock.elapsedRealtime()<deadline) {
                    ui.waitForIdle()
                    android.os.SystemClock.sleep(100)
                }
            }
        } finally { ui.runOnUiThread { ui.activity.viewModelStore.clear() }; server.shutdown() }
    }
}
