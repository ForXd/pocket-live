package org.streampocket

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Dns
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** 10 controlled sources x100 results; real incremental aggregation and LazyColumn. */
class SearchListDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun boundedThousandResultsAndScroll() {
        androidx.test.espresso.IdlingPolicies.setMasterPolicyTimeout(120,TimeUnit.SECONDS)
        val instrument=InstrumentationRegistry.getInstrumentation()
        val server=MockWebServer();server.start();val base="http://fixture.test:${server.port}"
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.requestUrl!!.encodedPath=="/sub") return MockResponse().setBody((0..9).joinToString(",","{\"sources\":[","]}") { """{"name":"Source$it","url":"$base/api$it"}""" })
                return MockResponse().setBody((0..99).joinToString(",","{\"list\":[","]}") { """{"vod_id":"$it","vod_name":"Result $it"}""" })
            }
        }
        lateinit var model:AppModel
        ui.runOnUiThread {
            ui.activity.viewModelStore.clear()
            model=ViewModelProvider(ui.activity,object:ViewModelProvider.Factory {
                override fun <T:ViewModel> create(clazz:Class<T>):T {
                    @Suppress("UNCHECKED_CAST")
                    return AppModel(ui.activity.application,object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) }) as T
                }
            })[AppModel::class.java]
            ui.activity.setContent { MaterialTheme { Pocket(model) } }
        }
        try {
            ui.waitUntil(60000) { !model.state.value.loading }
            ui.runOnIdle { model.allowHttp(true) };ui.waitUntil(60000) { model.state.value.saved.allowHttp }
            ui.runOnIdle { model.importSubscription("$base/sub") };ui.waitUntil(60000) { model.state.value.saved.subscriptions.size==1 }
            ui.onNodeWithTag("searchInput").performTextReplacement("Result")
            val start=android.os.SystemClock.elapsedRealtime()
            ui.onNodeWithText("开始搜索").performClick()
            ui.onNodeWithTag("searchInput").assertIsNotFocused()
            ui.waitUntil(60000) { model.state.value.results.isNotEmpty() }
            val first=android.os.SystemClock.elapsedRealtime()-start
            ui.waitUntil(60000) { !model.state.value.searching }
            val complete=android.os.SystemClock.elapsedRealtime()-start
            assertEquals(1000,model.state.value.results.size);assertEquals(10,model.state.value.completed);assertEquals(0,model.state.value.failures)
            instrument.uiAutomation.executeShellCommand("dumpsys gfxinfo org.streampocket reset").use { fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() } }
            val list=ui.onNode(hasScrollAction())
            repeat(30) { list.performTouchInput { swipeUp(durationMillis=300) } }
            val frames=instrument.uiAutomation.executeShellCommand("dumpsys gfxinfo org.streampocket framestats").use { fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().toString(Charsets.UTF_8) } }
            val file=java.io.File(instrument.targetContext.getExternalFilesDir(null),"search-scroll-frames.txt");file.writeText(frames)
            instrument.sendStatus(0,android.os.Bundle().apply {
                putLong("first_result_ms",first);putLong("all_results_ms",complete);putInt("results",1000);putInt("swipes",30);putString("framestats_path",file.absolutePath)
                putString("measurement_scope","debug instrumentation software emulator controlled HTTP, not physical phone")
            })
        } finally { ui.runOnUiThread { model.cancelSearch() };server.shutdown() }
    }
}
