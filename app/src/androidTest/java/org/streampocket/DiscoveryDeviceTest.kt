package org.streampocket

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class DiscoveryDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun recommendationsCategoriesPostersRetryAndSearch() {
        ui.runOnUiThread { ui.activity.viewModelStore.clear() }
        kotlinx.coroutines.runBlocking { Storage(ui.activity.application).write(Saved(allowHttp=true)) }
        val server=MockWebServer();server.start()
        val base="http://fixture.test:${server.port}"
        val fail=AtomicBoolean(false)
        val image=android.graphics.Bitmap.createBitmap(16,24,android.graphics.Bitmap.Config.ARGB_8888)
        image.eraseColor(android.graphics.Color.BLUE)
        val png=java.io.ByteArrayOutputStream().also { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                val url=request.requestUrl!!
                return when(url.encodedPath) {
                    "/recommend" -> {
                        if(fail.get()) MockResponse().setResponseCode(503)
                        else {
                            val start=url.queryParameter("page")!!.toInt()
                            val type=url.queryParameter("type")!!
                            val count=if(start==1) 20 else 1
                            val rows=(1..count).joinToString(",") { """{"id":"$type-$start-$it","title":"$type 推荐 $start-$it","rate":"8.5","cover":"$base/cover.png"}""" }
                            MockResponse().setHeader("Cache-Control","no-store").setBody("""{"subjects":[$rows]}""")
                        }
                    }
                    "/cover.png" -> MockResponse().setBody(Buffer().write(png)).setHeader("Content-Type","image/png")
                    "/sub" -> MockResponse().setBody("""{"name":"发现测试","sources":[{"name":"分类来源","url":"$base/api"}]}""")
                    "/api" -> if(url.queryParameter("ids")!=null) MockResponse().setBody("""{"list":[{"vod_play_from":"测试","vod_play_url":"第一集${'$'}$base/test.m3u8"}]}""")
                        else MockResponse().setBody("""{"class":[{"type_id":"1","type_name":"电视剧"}],"pagecount":2,"list":[{"vod_id":"fixture","vod_name":"分类结果","vod_pic":"$base/cover.png"}]}""")
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
                    return AppModel(ui.activity.application,object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) },
                        { type,tag,page -> "$base/recommend?type=$type&tag=${java.net.URLEncoder.encode(tag,"UTF-8")}&page=$page" }) as T
                }
            })[AppModel::class.java]
            ui.activity.setContent { PocketTheme { Pocket(model) } }
        }
        try {
            ui.waitUntil(15000) { !model.state.value.loading }
            assertTrue(model.state.value.saved.allowHttp)
            ui.runOnIdle { model.importSubscription("$base/sub") }
            ui.waitUntil(15000) { model.state.value.saved.subscriptions.any { it.url=="$base/sub" } }
            ui.onNodeWithText("发现",useUnmergedTree=true).performClick()
            ui.waitUntil(15000) { model.state.value.recommendations.size==20 && !model.state.value.recommendLoading }
            ui.onNodeWithText("tv 推荐 1-1").assertExists()
            ui.waitUntil(15000) { ui.onAllNodesWithContentDescription("tv 推荐 1-1 封面").fetchSemanticsNodes().isNotEmpty() }
            ui.runOnIdle { model.recommend(more=true) }
            ui.waitUntil(15000) { model.state.value.recommendations.size==21 }
            assertFalse(model.state.value.recommendMore)
            ui.onNodeWithText("电影",useUnmergedTree=true).performClick()
            ui.waitUntil(15000) { model.state.value.recommendations.firstOrNull()?.title=="movie 推荐 1-1" }
            fail.set(true)
            ui.onNodeWithTag("recommendFilters").performScrollToNode(hasText("最新"))
            ui.onNodeWithText("最新").performClick()
            ui.waitUntil(15000) { model.state.value.recommendError!=null }
            ui.onNodeWithText("重试推荐").assertExists()
            fail.set(false);ui.onNodeWithText("重试推荐").performClick()
            ui.waitUntil(15000) { model.state.value.recommendError==null && !model.state.value.recommendLoading }
            ui.onNodeWithText("movie 推荐 1-1").performClick()
            ui.waitUntil(15000) { !model.state.value.searching && model.state.value.results.isNotEmpty() }
            ui.onNodeWithTag("searchInput").assertTextContains("movie 推荐 1-1")
            ui.onNodeWithText("发现",useUnmergedTree=true).performClick()
            ui.onNodeWithText("订阅分类").performClick()
            ui.waitUntil(15000) { model.state.value.categories.isNotEmpty() }
            ui.onNodeWithText("电视剧").performClick()
            ui.waitUntil(15000) { model.state.value.catalogCategory=="1" && !model.state.value.catalogLoading }
            ui.onNodeWithText("在当前来源和分类中搜索").performTextInput("测试")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            ui.onNodeWithText("分类搜索").performScrollTo().performClick()
            ui.waitUntil(15000) { model.state.value.catalogQuery=="测试" && !model.state.value.catalogLoading }
            // The keyboard reduces the viewport; scroll the lazy list before selecting its result.
            ui.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
                .performScrollToNode(hasText("分类结果"))
            ui.onNodeWithText("分类结果").performClick()
            ui.waitUntil(15000) { model.state.value.detail!=null }
            assertEquals("fixture",model.state.value.detail!!.video.id)
            ui.runOnIdle { model.refreshStorage() }
            ui.waitUntil(15000) { (model.state.value.storageUsage?.posterBytes ?: 0)>0 }
            val usage=model.state.value.storageUsage!!
            assertTrue(usage.posterBytes+usage.responseBytes+usage.userDataBytes<StorageBudget.TOTAL_DISK)
            ui.runOnIdle { model.clearCache() }
            ui.waitUntil(15000) { model.state.value.storageUsage?.posterBytes==0L }
        } finally { ui.runOnUiThread { ui.activity.viewModelStore.clear() };server.shutdown() }
    }
    @Test fun liveDoubanAndCoverDecode()=runBlocking {
        val network=Network({false})
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val posters=PosterStore(java.io.File(context.cacheDir,"live-poster-test"))
        try {
            val page=Recommendations.parse(network.text(Recommendations.url("tv","热门",1)),1)
            assertTrue("Live recommendation response must contain subjects",page.videos.isNotEmpty())
            val cover=page.videos.first { it.poster!=null }
            assertNotNull("Live poster must decode",posters.load(cover.poster,network))
            assertTrue(posters.usage()>0)
        } finally { posters.clear();network.close() }
    }
}
