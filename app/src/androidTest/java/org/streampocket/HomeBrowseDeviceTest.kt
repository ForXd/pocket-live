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

class HomeBrowseDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun twoColumnsFitSixCardsAndSearchFollowsScrollWhileImeKeepsItVisible() {
        val server=MockWebServer().apply { start() }
        val base="http://home.test:${server.port}"
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.requestUrl!!.encodedPath=="/poster") return MockResponse().setHeader("Content-Type","image/jpeg").setBody(okio.Buffer().write(
                    InstrumentationRegistry.getInstrumentation().context.assets.open("editorial-poster.jpg").use { it.readBytes() }))
                return MockResponse().setBody("""{"subjects":[${(1..16).joinToString(",") { """{"id":"$it","title":"星河故事 $it","rate":"8.5","cover":"$base/poster"}""" }}]}""")
            }
        }
        ui.runOnUiThread { ui.activity.viewModelStore.clear() }
        runBlocking { Storage(ui.activity.application).write(Saved(allowHttp=true)) }
        lateinit var model:AppModel
        ui.runOnUiThread {
            model=ViewModelProvider(ui.activity,object:ViewModelProvider.Factory {
                override fun <T:ViewModel> create(modelClass:Class<T>):T {
                    @Suppress("UNCHECKED_CAST")
                    return AppModel(ui.activity.application,object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) },{_,_,_->"$base/recommend"}) as T
                }
            })[AppModel::class.java]
            ThemePreference.write(ui.activity,ThemeMode.LIGHT)
            ui.activity.setContent { PocketTheme { Pocket(model) } }
        }
        try {
            ui.waitUntil(10000) { model.state.value.recommendations.size==16 && !model.state.value.recommendLoading }
            val grid=ui.onNodeWithTag("recommendationGrid")
            ui.onNodeWithTag("searchInput").assertIsDisplayed()
            val before=grid.getUnclippedBoundsInRoot().let { (it.bottom-it.top).value }
            captureUi(ui,"home-expanded")
            grid.performTouchInput { swipeUp(durationMillis=600) }
            ui.waitForIdle()
            ui.onNodeWithTag("searchInput").assertIsNotDisplayed()
            val viewport=grid.getUnclippedBoundsInRoot()
            assertTrue("Collapsed search must return its space to content",(viewport.bottom-viewport.top).value>before+40)
            val visible=(1..16).mapNotNull { id ->
                if(ui.onAllNodesWithTag("recommendation:$id").fetchSemanticsNodes().isEmpty()) null else ui.onNodeWithTag("recommendation:$id").getUnclippedBoundsInRoot()
            }.filter { it.top>=viewport.top && it.bottom<=viewport.bottom && (it.bottom-it.top).value>0 }
            assertTrue("Expected six fully visible cards, got ${visible.size}",visible.size>=6)
            assertEquals("Portrait layout must remain two columns",2,visible.map { it.left.value.toInt() }.distinct().size)
            captureUi(ui,"home-six-cards")
            grid.performTouchInput { swipeDown(durationMillis=600) }
            ui.waitForIdle();ui.onNodeWithTag("searchInput").assertIsDisplayed()
            captureUi(ui,"home-search-returned")
            ui.onNodeWithTag("searchInput").performClick().performTextInput("星河")
            ui.waitUntil(5000) { androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true }
            grid.performTouchInput { swipeUp(durationMillis=600) }
            ui.onNodeWithTag("searchInput").assertIsDisplayed().assertTextContains("星河")
            captureUi(ui,"home-keyboard-pinned")
        } finally { ui.runOnUiThread { ui.activity.viewModelStore.clear() };server.shutdown() }
    }
}
