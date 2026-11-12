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

class CollectionDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun mergedSourcesSeasonsHistoryAndPlayer() {
        val server=MockWebServer().apply { start() }
        val base="http://collection.test:${server.port}"
        val a=Source("光影资源","$base/a")
        val b=Source("流光资源","http://other.test:${server.port}/b")
        fun video(source:Source,id:String,title:String)=Video(source,id,title,typeName="欧美剧")
        val first=video(a,"s1","风骚律师 第一季")
        val second=video(a,"s2","风骚律师 第二季")
        val history=listOf(History(Playback(first,Episode("第1集","$base/1-1.mp4"),2500,duration=10000),10),History(Playback(second,Episode("第1集","$base/2-1.mp4"),7500,duration=10000),20))
        val assets=InstrumentationRegistry.getInstrumentation().context.assets
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                val url=request.requestUrl!!
                if(url.encodedPath.endsWith(".mp4")) return MockResponse().setHeader("Content-Type","video/mp4").setBody(okio.Buffer().write(assets.open("test.mp4").use { it.readBytes() }))
                if(url.encodedPath=="/poster") return MockResponse().setHeader("Content-Type","image/jpeg").setBody(okio.Buffer().write(assets.open("editorial-poster.jpg").use { it.readBytes() }))
                if(url.encodedPath=="/recommend") return MockResponse().setBody("""{"subjects":[]}""")
                val id=url.queryParameter("ids")
                if(id!=null) {
                    val season=if(id=="s2") 2 else 1
                    val prefix=if(url.encodedPath=="/b") "b" else ""
                    val episodes=(1..10).joinToString("#") { "第${it}集${'$'}$base/$prefix$season-$it.mp4" }
                    return MockResponse().setBody("""{"list":[{"vod_content":"一位律师在选择与命运之间，寻找属于自己的道路。","vod_play_from":"高清","vod_play_url":"$episodes"}]}""")
                }
                return MockResponse().setBody("""{"list":[{"vod_id":"s1","vod_name":"风骚律师 第一季","type_name":"欧美剧","vod_remarks":"全10集","vod_pic":"$base/poster"},{"vod_id":"s2","vod_name":"风骚律师 第二季","type_name":"欧美剧","vod_remarks":"全10集","vod_pic":"$base/poster"}]}""")
            }
        }
        ui.runOnUiThread { ui.activity.viewModelStore.clear() }
        runBlocking { Storage(ui.activity.application).write(Saved(allowHttp=true,subscriptions=listOf(Subscription("collection","影视","$base/sub",1,listOf(a,b))),history=history)) }
        lateinit var model:AppModel
        val args=InstrumentationRegistry.getArguments()
        val appearance=args.getString("appearance","light")
        ui.runOnUiThread {
            model=ViewModelProvider(ui.activity,object:ViewModelProvider.Factory {
                override fun <T:ViewModel> create(clazz:Class<T>):T {
                    @Suppress("UNCHECKED_CAST") return AppModel(ui.activity.application,object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) },{_,_,_->"$base/recommend"}) as T
                }
            })[AppModel::class.java]
            ThemePreference.write(ui.activity,if(appearance=="dark") ThemeMode.DARK else ThemeMode.LIGHT)
            ui.activity.setContent { PocketTheme { Pocket(model) } }
        }
        fun capture(name:String) {
            ui.waitForIdle();android.os.SystemClock.sleep(250)
            val instrument=InstrumentationRegistry.getInstrumentation()
            val dir=java.io.File(instrument.targetContext.getExternalFilesDir(null),"collection-$appearance").apply { mkdirs() }
            instrument.uiAutomation.takeScreenshot()?.let { bitmap -> try { java.io.File(dir,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } } finally { bitmap.recycle() } }
        }
        try {
            ui.waitUntil(10000) { !model.state.value.loading }
            ui.onNodeWithTag("openSearch").performClick()
            ui.onNodeWithTag("searchInput").performTextReplacement("风骚律师")
            ui.onNodeWithContentDescription("开始搜索").performClick()
            ui.waitUntil(15000) { !model.state.value.searching && model.state.value.results.size==4 }
            assertEquals(1,VideoGroups.group(model.state.value.results).size)
            ui.onNodeWithTag("season:2:").assertExists()
            capture("01-search")
            if(args.getString("uxReview")=="true") {
                val done=java.io.File(ui.activity.filesDir,"ui-review-done");done.delete()
                val deadline=android.os.SystemClock.elapsedRealtime()+180000
                while(!done.exists() && android.os.SystemClock.elapsedRealtime()<deadline) { ui.mainClock.advanceTimeBy(100);ui.waitForIdle();android.os.SystemClock.sleep(100) }
                return
            }
            ui.onNodeWithTag("season:2:").performClick()
            ui.onNodeWithTag("searchVideo:s2").assertExists()
            ui.onNodeWithTag("sourceChoice:${b.key}:s2").performScrollTo().performClick()
            ui.waitUntil(10000) { model.state.value.detail?.video?.id=="s2" && model.state.value.detail?.video?.source?.key==b.key }
            ui.onNodeWithTag("detailGrid").performScrollToNode(hasTestTag("season:1:"))
            ui.onNode(hasTestTag("season:1:") and hasAnyAncestor(hasTestTag("detailGrid"))).performClick()
            ui.waitUntil(10000) { model.state.value.detail?.video?.id=="s1" }
            assertEquals(b.key,model.state.value.detail!!.video.source.key)
            ui.onNodeWithTag("detailGrid").performScrollToNode(hasTestTag("episode:第1集"))
            capture("02-detail")
            ui.onNodeWithTag("episode:第1集").performClick()
            ui.waitUntil(15000) { model.state.value.playback!=null && PlaybackMetrics.frames>0 }
            fun nativeView(view:android.view.View):GesturePlayerView? { if(view is GesturePlayerView) return view;if(view is android.view.ViewGroup) for(i in 0 until view.childCount) nativeView(view.getChildAt(i))?.let { return it };return null }
            ui.runOnIdle { nativeView(ui.activity.window.decorView)!!.apply { player!!.pause();controllerShowTimeoutMs=0;showController() } }
            capture("03-player")
            ui.onNodeWithTag("fullscreenToggle").performClick()
            ui.runOnIdle { nativeView(ui.activity.window.decorView)!!.apply { controllerShowTimeoutMs=0;showController() } }
            ui.onNodeWithText("选集").performClick()
            val before=model.state.value.playback!!.sessionId
            ui.onNodeWithTag("season:2:").performClick()
            ui.waitUntil(10000) { ui.onAllNodesWithTag("playbackEpisodes").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(before,model.state.value.playback!!.sessionId)
            capture("04-fullscreen-season")
            ui.onNodeWithText("第3集").performClick()
            ui.waitUntil(10000) { model.state.value.playback?.video?.id=="s2" && model.state.value.playback?.episode?.name=="第3集" }
            assertEquals(0L,model.state.value.playback!!.position)
            ui.runOnIdle { model.stopPlayback();model.closeDetail() }
            ui.waitUntil(10000) { PlaybackMetrics.created==PlaybackMetrics.released }
            ui.onNodeWithTag("nav:2").performClick()
            val id=HistoryGroups.key(first).stableKey
            ui.onNodeWithTag("expandHistory:$id").performClick()
            ui.onNodeWithTag("season:1:").performClick()
            ui.onNodeWithTag("historyGroups").performScrollToIndex(1)
            capture("05-history")
            assertEquals(1,HistoryGroups.group(model.state.value.saved.history).size)
            assertTrue(model.state.value.saved.history.any { it.playback.video.id=="s1" && it.playback.position==2500L })
        } finally { ui.runOnUiThread { ui.activity.viewModelStore.clear() };server.shutdown() }
    }
}
