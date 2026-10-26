package org.streampocket

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.net.InetAddress

class SubscriptionUiDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun groupsCollapseRestoreAndKeepManagementActions() {
        val server=MockWebServer();server.start()
        val base="http://fixture.test:${server.port}"
        val sources=(1..40).map { Source("精选来源 $it","$base/api/$it") }
        val a=Subscription("group-a","我的影视订阅","$base/sub",System.currentTimeMillis(),sources)
        val b=Subscription("group-b","我的直播订阅","$base/live",System.currentTimeMillis(),listOf(Source("直播来源","$base/live.m3u",SourceKind.LIVE)))
        runBlocking { Storage(ui.activity.application).write(Saved(subscriptions=listOf(a,b),allowHttp=true)) }
        server.enqueue(MockResponse().setBody("""{"name":"我的影视订阅","sources":[{"name":"精选来源 1","url":"$base/api/1"},{"name":"新增来源","url":"$base/new"}]}"""))
        val restoration=StateRestorationTester(ui)
        lateinit var model:AppModel
        ui.runOnUiThread {
            ui.activity.viewModelStore.clear()
            model=ViewModelProvider(ui.activity,object:ViewModelProvider.Factory {
                override fun <T:ViewModel> create(modelClass:Class<T>):T {
                    @Suppress("UNCHECKED_CAST")
                    return AppModel(ui.activity.application,object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) }) as T
                }
            })[AppModel::class.java]
        }
        ui.runOnUiThread {
            ui.activity.findViewById<android.view.ViewGroup>(android.R.id.content).apply {
                (getChildAt(0) as? androidx.compose.ui.platform.ComposeView)?.disposeComposition()
                removeAllViews()
            }
        }
        restoration.setContent { PocketTheme { Pocket(model) } }
        fun scroll(tag:String)=ui.onNodeWithTag("subscriptionsList").performScrollToNode(hasTestTag(tag))
        try {
            ui.waitUntil(10000) { !model.state.value.loading }
            ui.onNodeWithText("订阅",useUnmergedTree=true).performClick()
            ui.onNodeWithText("精选来源 1").assertDoesNotExist()
            captureUi(ui,"subscriptions-collapsed")
            scroll("subscription:group-a")
            ui.onNodeWithTag("subscription:group-a").performClick()
            ui.onNodeWithTag("subscriptionsList").performScrollToNode(hasContentDescription("启用或停用 精选来源 1"))
            ui.onNodeWithContentDescription("启用或停用 精选来源 1").performClick()
            ui.waitUntil(5000) { sources[0].key in model.state.value.saved.disabled }
            captureUi(ui,"subscriptions-expanded")
            ui.onNodeWithContentDescription("来源选项 精选来源 1").performClick()
            ui.onNodeWithText("广告精确路径规则").performClick()
            ui.onNodeWithText("当前源的广告精确路径").assertIsDisplayed()
            ui.onNodeWithText("取消").performClick()
            assertTrue(sources[0].key in model.state.value.saved.disabled)
            ui.onNodeWithTag("subscriptionsList").performScrollToNode(hasText("精选来源 40"))
            ui.onNodeWithText("精选来源 40").assertIsDisplayed()
            scroll("subscription:group-a")
            ui.onNodeWithTag("subscription:group-a").performClick()
            ui.onNodeWithText("精选来源 1").assertDoesNotExist()
            assertTrue(sources[0].key in model.state.value.saved.disabled)
            scroll("subscription:group-b")
            ui.onNodeWithTag("subscription:group-b").performClick()
            restoration.emulateSavedInstanceStateRestore()
            ui.onNodeWithTag("subscriptionsList").performScrollToNode(hasText("直播来源"))
            ui.onNodeWithText("直播来源").assertIsDisplayed()
            scroll("subscription:group-a")
            ui.onNodeWithTag("subscription:group-a").performClick()
            ui.onNodeWithText("直播来源").assertDoesNotExist()
            ui.onNodeWithContentDescription("管理订阅 我的影视订阅").performClick()
            ui.onNodeWithText("更新订阅").performClick()
            ui.waitUntil(15000) { model.state.value.saved.subscriptions.first().sources.size==2 }
            assertTrue(sources[0].key in model.state.value.saved.disabled)
            ui.onNodeWithTag("subscriptionsList").performScrollToNode(hasText("新增来源"))
            ui.onNodeWithText("新增来源").assertIsDisplayed()
            scroll("subscription:group-a")
            ui.onNodeWithTag("subscription:group-a").performClick()
            scroll("subscription:group-b")
            ui.onNodeWithContentDescription("管理订阅 我的直播订阅").performClick()
            ui.onNodeWithText("删除订阅").performClick()
            ui.onNodeWithText("取消").performClick()
            assertEquals(2,model.state.value.saved.subscriptions.size)
            ui.onNodeWithContentDescription("管理订阅 我的直播订阅").performClick()
            ui.onNodeWithText("删除订阅").performClick()
            ui.onNodeWithText("删除",useUnmergedTree=true).performClick()
            ui.waitUntil(5000) { model.state.value.saved.subscriptions.size==1 }
            assertEquals("group-a",model.state.value.saved.subscriptions.single().id)
        } finally { ui.runOnUiThread { ui.activity.viewModelStore.clear() };server.shutdown() }
    }
}

internal fun captureUi(ui:androidx.compose.ui.test.junit4.AndroidComposeTestRule<androidx.test.ext.junit.rules.ActivityScenarioRule<MainActivity>,MainActivity>,name:String) {
    ui.waitForIdle();android.os.SystemClock.sleep(400);ui.waitForIdle()
    val instrument=InstrumentationRegistry.getInstrumentation()
    instrument.waitForIdleSync()
    val suffix=InstrumentationRegistry.getArguments().getString("layoutSize","normal")
    val dir=java.io.File(instrument.targetContext.getExternalFilesDir(null),"ui-v070-$suffix").apply { mkdirs() }
    instrument.uiAutomation.takeScreenshot()?.let { b -> try { java.io.File(dir,"$name.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } } finally { b.recycle() } }
}
