package org.streampocket

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
    @Test fun subscriptionDetailsRestorePositionAndKeepManagementActions() {
        val server=MockWebServer();server.start()
        val base="http://fixture.test:${server.port}"
        val sources=(1..40).map { Source("精选来源 $it","$base/api/$it") }
        val a=Subscription("group-a","yifan 精选源列表 (2026-10-13 实测可用) · 影视与直播订阅合集","$base/sub",System.currentTimeMillis(),sources)
        val b=Subscription("group-b","我的直播订阅","$base/live",System.currentTimeMillis(),listOf(Source("直播来源","$base/live.m3u",SourceKind.LIVE)))
        val collections=listOf("光影经典","世界电影","城市剧场","纪录精选","亲子动画","音乐现场","自然探索","人文影像","体育频道","生活频道").mapIndexed { i,name ->
            Subscription("collection-$i",name,"$base/sub/$i",a.updated,listOf(Source(name,"$base/api/collection-$i")))
        }
        val subscriptions=listOf(a)+collections+listOf(b)
        runBlocking { Storage(ui.activity.application).write(Saved(subscriptions=subscriptions,allowHttp=true)) }
        val subscriptionResponse=MockResponse().setBody("""{"name":"我的影视订阅","sources":[{"name":"精选来源 1","url":"$base/api/1"},{"name":"新增来源","url":"$base/new"}]}""")
        server.dispatcher=object:Dispatcher() { override fun dispatch(request:RecordedRequest)=if(request.path=="/sub") subscriptionResponse else MockResponse().setBody("{\"list\":[],\"subjects\":[]}") }
        val restoration=StateRestorationTester(ui)
        lateinit var model:AppModel
        ui.runOnUiThread {
            ui.activity.viewModelStore.clear()
            model=ViewModelProvider(ui.activity,object:ViewModelProvider.Factory {
                override fun <T:ViewModel> create(modelClass:Class<T>):T {
                    @Suppress("UNCHECKED_CAST")
                    return AppModel(ui.activity.application,object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) },{_,_,_->"$base/recommend"}) as T
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
        val list=ui.onNodeWithTag("subscriptionsList")
        val details=ui.onNodeWithTag("subscriptionSources")
        val film=ui.onNodeWithTag("subscription:group-a")
        val live=ui.onNodeWithTag("subscription:group-b")
        val back=ui.onNodeWithContentDescription("返回订阅")
        val firstSwitch=ui.onNodeWithContentDescription("启用或停用 精选来源 1")
        try {
            ui.waitUntil(10000) { !model.state.value.loading }
            ui.onNodeWithTag("nav:1").performClick()
            details.assertDoesNotExist()
            ui.onNodeWithText("精选来源 1").assertDoesNotExist()
            captureUi(ui,"subscriptions-list")
            film.performClick()
            list.assertDoesNotExist()
            details.assertIsDisplayed()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            ui.onNodeWithText(a.name).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Long subscription heading fits in two lines", layouts.single().lineCount <= 2)
            firstSwitch.assertIsOn().performClick()
            ui.waitUntil(5000) { sources[0].key in model.state.value.saved.disabled }
            firstSwitch.assertIsOff()
            ui.onNodeWithContentDescription("来源选项 精选来源 1").assertDoesNotExist()
            captureUi(ui,"subscription-detail")
            details.performScrollToNode(hasText("精选来源 40"))
            ui.onNodeWithText("精选来源 40").assertIsDisplayed()
            restoration.emulateSavedInstanceStateRestore()
            ui.onNodeWithText("精选来源 40").assertIsDisplayed()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            details.assertDoesNotExist()
            film.assertIsDisplayed()

            list.performScrollToKey("subscription:group-b")
            val liveTop=live.assertIsDisplayed().getUnclippedBoundsInRoot().top
            live.performClick()
            list.assertDoesNotExist()
            ui.onNodeWithText("直播来源").assertIsDisplayed()
            ui.onNodeWithText("精选来源 1").assertDoesNotExist()
            back.performClick()
            details.assertDoesNotExist()
            assertEquals("Return restores the tapped subscription",liveTop.value,live.assertIsDisplayed().getUnclippedBoundsInRoot().top.value,1f)

            ui.onNodeWithTag("nav:3").performClick()
            ui.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("settingsSourceRules"))
            ui.onNodeWithTag("settingsSourceRules").performClick()
            ui.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("settingsRule:${sources[0].key}"))
            ui.onNodeWithTag("settingsRule:${sources[0].key}").performClick()
            ui.onNodeWithText("当前源的广告精确路径").assertIsDisplayed()
            ui.onNodeWithText("取消").performClick()
            ui.onNodeWithTag("nav:1").performClick()
            list.performScrollToKey("subscription:group-a")
            ui.onNodeWithTag("swipeSubscription:group-a").performTouchInput { swipeLeft() }
            val row = ui.onNodeWithTag("swipeSubscription:group-a").getUnclippedBoundsInRoot()
            val update = ui.onNodeWithContentDescription("更新订阅").getUnclippedBoundsInRoot()
            val delete = ui.onNodeWithContentDescription("删除订阅").getUnclippedBoundsInRoot()
            assertEquals(row.top.value, update.top.value, 1f)
            assertEquals(row.bottom.value, delete.bottom.value, 1f)
            assertTrue(update.right < delete.left)
            captureUi(ui,"subscription-swipe-actions")
            ui.onNodeWithContentDescription("更新订阅").performClick()
            ui.waitUntil(15000) { model.state.value.saved.subscriptions.first().sources.size==2 }
            assertTrue(sources[0].key in model.state.value.saved.disabled)
            film.performClick()
            details.performScrollToNode(hasText("新增来源"))
            ui.onNodeWithText("新增来源").assertIsDisplayed()
            firstSwitch.assertIsOff()
            val firstRefresh=model.state.value.saved.subscriptions.first().updated
            ui.onNodeWithContentDescription("订阅选项").performClick()
            ui.onNodeWithText("更新订阅").performClick()
            ui.waitUntil(15000) { model.state.value.saved.subscriptions.first().updated>firstRefresh && model.state.value.importing.isEmpty() }
            details.assertIsDisplayed().performScrollToIndex(0)
            ui.onNodeWithText("2 个源 · 已启用 1 个").assertIsDisplayed()
            back.performClick()
            list.performScrollToKey("subscription:group-b")
            // The refresh snackbar temporarily covers the last visible subscription above the dock.
            ui.waitUntil(8000) { model.state.value.message==null }
            ui.onNodeWithTag("swipeSubscription:group-b").performTouchInput { swipeLeft() }
            ui.onNodeWithContentDescription("删除订阅").performClick()
            ui.onNodeWithText("取消").performClick()
            assertEquals(subscriptions.size,model.state.value.saved.subscriptions.size)
            live.performClick()
            ui.onNodeWithContentDescription("订阅选项").performClick()
            ui.onNodeWithText("删除订阅").performClick()
            ui.onNodeWithText("删除",useUnmergedTree=true).performClick()
            ui.waitUntil(5000) { model.state.value.saved.subscriptions.none { it.id==b.id } }
            assertEquals(subscriptions.map { it.id }-b.id,model.state.value.saved.subscriptions.map { it.id })
            list.assertIsDisplayed()
            details.assertDoesNotExist()
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
