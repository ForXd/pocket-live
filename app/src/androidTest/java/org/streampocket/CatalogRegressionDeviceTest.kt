package org.streampocket

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.net.InetAddress

class CatalogRegressionDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var server:MockWebServer
    private lateinit var model:AppModel
    private lateinit var primary:Source
    private lateinit var secondary:Source
    private val failSecondary=java.util.concurrent.atomic.AtomicBoolean(false)
    private val paths=java.util.concurrent.CopyOnWriteArrayList<String>()
    @Before fun setup() {
        server=MockWebServer().apply { start() }
        val base="http://catalog.test:${server.port}"
        val source=Source("分类片库","$base/api")
        primary=source;secondary=Source("第二片库","$base/second")
        val disabled=Source("已停用","$base/disabled")
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                val u=request.requestUrl!!
                paths+=u.encodedPath
                if(u.encodedPath=="/second" && failSecondary.get()) return MockResponse().setResponseCode(503)
                if(u.encodedPath=="/recommend") return MockResponse().setBody("""{"subjects":[]}""")
                if(u.queryParameter("ac")=="list") return MockResponse().setBody("""{"class":[{"type_id":2,"type_name":"电视剧"}],"list":[]}""")
                val page=u.queryParameter("pg")?.toIntOrNull()?:1
                val genre=if(page==3) "悬疑" else "剧情"
                return MockResponse().setBody("""{"pagecount":3,"list":[{"vod_id":"$page","vod_name":"第${page}页影片","type_id":2,"vod_class":"$genre"}]}""")
            }
        }
        ui.runOnUiThread { ui.activity.viewModelStore.clear() }
        runBlocking { Storage(ui.activity.application).write(Saved(allowHttp=true,subscriptions=listOf(Subscription("catalog","片库","$base/sub",1,listOf(source,secondary,disabled))),disabled=setOf(disabled.key))) }
        ui.runOnUiThread {
            model=ViewModelProvider(ui.activity,object:ViewModelProvider.Factory {
                override fun <T:ViewModel> create(clazz:Class<T>):T {
                    @Suppress("UNCHECKED_CAST")
                    return AppModel(ui.activity.application,object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) },{_,_,_->"$base/recommend"}) as T
                }
            })[AppModel::class.java]
            ui.activity.setContent { PocketTheme { Pocket(model) } }
        }
        ui.waitUntil(10000) { !model.state.value.loading }
    }
    @After fun close() { ui.runOnUiThread { ui.activity.viewModelStore.clear() };server.shutdown() }
    @Test fun genreSearchFindsMatchingVideosBeyondFirstPage() {
        ui.onNodeWithText("分类搜索").performClick()
        ui.waitUntil(10000) { !model.state.value.catalogLoading && model.state.value.catalogSource!=null }
        ui.onNodeWithTag("catalogGenrePicker").performClick()
        ui.onNodeWithTag("catalogGenre:MYSTERY").performClick()
        ui.onNodeWithTag("applyCatalogFilters").performClick()
        ui.waitUntil(10000) { !model.state.value.catalogLoading }
        assertTrue("选择悬疑必须能找到第 3 页的影片，不能只过滤首页",model.state.value.catalogVideos.any { it.id=="3" })
    }
    @Test fun keyboardContentMeetsImeWithoutNavigationGap() {
        ui.onNodeWithTag("searchInput").performClick().performTextInput("星河")
        ui.waitUntil(5000) { ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true }
        ui.waitForIdle(); android.os.SystemClock.sleep(500)
        val insets=ViewCompat.getRootWindowInsets(ui.activity.window.decorView)!!
        val imeTop=ui.activity.window.decorView.height-insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        val bounds=ui.onNodeWithTag("recommendationGrid").fetchSemanticsNode().boundsInWindow
        assertEquals("内容应贴合键盘上沿，不重复扣除底栏/导航栏高度",imeTop.toFloat(),bounds.bottom,3f)
    }
    @Test fun cancelledGenreSearchCanRetryWithoutReportingAnEmptyCatalog() {
        ui.waitUntil(10000) { model.state.value.catalogSource!=null }
        val source=model.state.value.catalogSource!!
        ui.runOnIdle { model.browse(source,genres=setOf(Genre.MYSTERY));model.cancelCatalog() }
        assertFalse(model.state.value.catalogLoading)
        assertTrue(model.state.value.catalogError.orEmpty().contains("取消"))
        ui.runOnIdle { model.browse(source,genres=setOf(Genre.MYSTERY)) }
        ui.waitUntil(10000) { !model.state.value.catalogLoading }
        assertNull(model.state.value.catalogError)
        assertEquals(listOf("3"),model.state.value.catalogVideos.map { it.id })
    }

    @Test fun allSourcesAreDefaultAndSourceSelectionsCanBeCombinedRemovedAndCleared() {
        ui.waitUntil(10000) { !model.state.value.catalogLoading && model.state.value.catalogVideos.size==2 }
        assertTrue(model.state.value.catalogSourceKeys.isEmpty())
        assertEquals(setOf(primary.key,secondary.key),model.state.value.catalogVideos.map { it.source.key }.toSet())
        assertFalse(paths.contains("/disabled"))
        ui.onNodeWithText("分类搜索").performClick()
        ui.onNodeWithTag("searchInput").performTextReplacement("影片")
        ui.onNodeWithTag("catalogSourcePicker").performClick()
        ui.onNodeWithTag("catalogSource:${primary.key}").performClick()
        ui.onNodeWithTag("catalogSource:${secondary.key}").performClick()
        ui.onNodeWithTag("applyCatalogFilters").performClick()
        ui.waitUntil(10000) { !model.state.value.catalogLoading && model.state.value.catalogSourceKeys.size==2 }
        assertEquals("影片",model.state.value.catalogQuery)
        ui.onNodeWithTag("activeSource:${primary.key}").performClick()
        ui.waitUntil(10000) { !model.state.value.catalogLoading && model.state.value.catalogSourceKeys==setOf(secondary.key) }
        assertTrue(model.state.value.catalogVideos.all { it.source==secondary })
        ui.onNodeWithTag("activeSource:${secondary.key}").performClick()
        ui.waitUntil(10000) { !model.state.value.catalogLoading && model.state.value.catalogSourceKeys.isEmpty() }
        assertEquals(setOf(primary.key,secondary.key),model.state.value.catalogVideos.map { it.source.key }.toSet())
    }
    @Test fun failingSourceDoesNotDiscardOtherSourcesAndCanRecover() {
        ui.waitUntil(10000) { !model.state.value.catalogLoading && model.state.value.catalogSource!=null }
        failSecondary.set(true)
        ui.runOnIdle { model.browseSources(emptySet(),query="失败恢复") }
        ui.waitUntil(15000) { !model.state.value.catalogLoading }
        assertTrue(model.state.value.catalogVideos.isNotEmpty())
        assertTrue(model.state.value.catalogVideos.all { it.source==primary })
        assertNotNull(model.state.value.catalogError)
        assertTrue(model.state.value.catalogMore)
        failSecondary.set(false)
        ui.runOnIdle { model.browseSources(emptySet(),query="失败恢复",more=true) }
        ui.waitUntil(15000) { !model.state.value.catalogLoading }
        assertNull(model.state.value.catalogError)
        assertTrue(model.state.value.catalogVideos.any { it.source==secondary })
    }

}
