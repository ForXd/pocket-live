package org.streampocket

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Dns
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class DiscoveryRecoveryTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun failedPosterRecoversAfterForegroundWithoutRestart() {
        val server=MockWebServer();server.start()
        val available=AtomicBoolean(false)
        val recommendationFailure=AtomicBoolean(false)
        val coverVersion=java.util.concurrent.atomic.AtomicInteger()
        val coverRequests=java.util.concurrent.atomic.AtomicInteger()
        val base="http://fixture.test:${server.port}"
        val image=android.graphics.Bitmap.createBitmap(8,8,android.graphics.Bitmap.Config.ARGB_8888)
        val bytes=java.io.ByteArrayOutputStream().also { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
        image.recycle()
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest)=if(request.path!!.startsWith("/cover")) {
                coverRequests.incrementAndGet()
                if(available.get()) MockResponse().setBody(Buffer().write(bytes)) else MockResponse().setResponseCode(503)
            } else if(recommendationFailure.get()) MockResponse().setResponseCode(503) else MockResponse().setBody("""{"subjects":[{"id":"1","title":"恢复测试","cover":"$base/cover?version=${coverVersion.get()}"}]}""")
        }
        lateinit var model:AppModel
        ui.runOnUiThread {
            ui.activity.viewModelStore.clear()
            model=androidx.lifecycle.ViewModelProvider(ui.activity,object:androidx.lifecycle.ViewModelProvider.Factory {
                override fun <T:androidx.lifecycle.ViewModel> create(clazz:Class<T>):T {
                    @Suppress("UNCHECKED_CAST")
                    return AppModel(ui.activity.application,object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) },{_,_,_->"$base/recommend"}) as T
                }
            })[AppModel::class.java]
            ui.activity.setContent { PocketTheme { Pocket(model) } }
        }
        try {
            ui.waitUntil(10000) { !model.state.value.loading }
            ui.runOnIdle { model.allowHttp(true) }
            ui.waitUntil(10000) { model.state.value.saved.allowHttp }
            ui.onNodeWithText("发现",useUnmergedTree=true).performClick()
            ui.waitUntil(10000) { server.requestCount>=2 && !model.state.value.recommendLoading }
            ui.onNodeWithContentDescription("暂无封面").assertExists()
            ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            available.set(true)
            ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            ui.waitUntil(7000) { ui.onAllNodesWithContentDescription("恢复测试 封面").fetchSemanticsNodes().isNotEmpty() }
            recommendationFailure.set(true)
            ui.onNodeWithContentDescription("刷新推荐").performClick()
            ui.waitUntil(7000) { model.state.value.recommendError!=null }
            ui.onNodeWithText("恢复测试").assertExists() // transient failure cannot blank existing content
            ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            recommendationFailure.set(false)
            ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            ui.waitUntil(7000) { model.state.value.recommendError==null && !model.state.value.recommendLoading }
            org.junit.Assert.assertEquals(1,model.state.value.recommendations.size)
            val previousCovers=coverRequests.get()
            coverVersion.incrementAndGet()
            ui.onNodeWithContentDescription("刷新推荐").performClick()
            ui.waitUntil(7000) { coverRequests.get()>previousCovers }
            ui.waitUntil(7000) { ui.onAllNodesWithContentDescription("恢复测试 封面").fetchSemanticsNodes().isNotEmpty() }
        } finally { ui.runOnUiThread { ui.activity.viewModelStore.clear() };server.shutdown() }
    }
}
