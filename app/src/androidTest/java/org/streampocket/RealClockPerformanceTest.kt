package org.streampocket

import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Dns
import okhttp3.mockwebserver.*
import org.junit.Test
import org.junit.Assert.*
import java.net.InetAddress

/** No Compose test rule/clock. Real Activity Choreographer and shell input events. */
class RealClockPerformanceTest {
    /** Same input cadence and renderer, with no repository/network/player work. */
    @Test fun simpleListRendererBaseline() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        fun shell(command:String):String = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                activity.viewModelStore.clear()
                activity.setContent {
                    MaterialTheme(colorScheme=darkColorScheme()) {
                        Surface(Modifier.fillMaxSize()) {
                            LazyColumn(Modifier.safeDrawingPadding(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                items(1000,key={it}) { index ->
                                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                                        Text("Result $index",style=MaterialTheme.typography.titleMedium)
                                        Text("Source · baseline",style=MaterialTheme.typography.bodySmall)
                                    } }
                                }
                            }
                        }
                    }
                }
            }
            Thread.sleep(5000)
            shell("dumpsys gfxinfo org.streampocket reset")
            repeat(30) { shell("input swipe 240 650 240 300 300");Thread.sleep(200) }
            Thread.sleep(2000)
            val output=java.io.File(instrument.targetContext.getExternalFilesDir(null),"baseline-real-clock-scroll-frames.txt")
            output.writeText(shell("dumpsys gfxinfo org.streampocket framestats"))
            instrument.sendStatus(0,android.os.Bundle().apply {
                putString("frame_file",output.absolutePath);putInt("swipes",30)
                putString("scope","simple static Compose list,real frame clock,same software renderer; comparison only,not phone performance")
            })
        } finally { scenario.close() }
    }
    @Test fun aggregateAndScrollWithRealFrameClock() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        fun shell(command:String):String = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }
        fun awaitCondition(condition:()->Boolean) {
            val end=android.os.SystemClock.elapsedRealtime()+90000
            while(!condition()) { if(android.os.SystemClock.elapsedRealtime()>end) fail("Timed out awaiting real Activity state");Thread.sleep(50) }
        }
        val server=MockWebServer();server.start();val base="http://fixture.test:${server.port}"
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.requestUrl!!.encodedPath=="/sub") return MockResponse().setBody((0..9).joinToString(",","{\"sources\":[","]}") { """{"name":"Source$it","url":"$base/api$it"}""" })
                return MockResponse().setBody((0..99).joinToString(",","{\"list\":[","]}") { """{"vod_id":"$it","vod_name":"Result $it"}""" })
            }
        }
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        lateinit var model:AppModel
        var traceStarted=false
        try {
            scenario.onActivity { activity ->
                activity.viewModelStore.clear()
                model=ViewModelProvider(activity,object:ViewModelProvider.Factory {
                    override fun <T:ViewModel> create(modelClass:Class<T>):T {
                        @Suppress("UNCHECKED_CAST")
                        return AppModel(activity.application,object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) }) as T
                    }
                })[AppModel::class.java]
                activity.setContent { MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xffa2e5ce),background=Color(0xff101918),surface=Color(0xff172422))) { Surface { Pocket(model) } } }
            }
            awaitCondition { !model.state.value.loading }
            scenario.onActivity { model.allowHttp(true) };awaitCondition { model.state.value.saved.allowHttp }
            scenario.onActivity { model.importSubscription("$base/sub") };awaitCondition { model.state.value.saved.subscriptions.size==1 }
            val start=android.os.SystemClock.elapsedRealtime()
            scenario.onActivity { model.search("Result") }
            awaitCondition { model.state.value.results.isNotEmpty() };val first=android.os.SystemClock.elapsedRealtime()-start
            awaitCondition { !model.state.value.searching };val complete=android.os.SystemClock.elapsedRealtime()-start
            assertEquals(1000,model.state.value.results.size);assertEquals(0,model.state.value.failures)
            Thread.sleep(3000)
            val trace=InstrumentationRegistry.getArguments().getString("captureTrace")=="true"
            if(trace) { shell("atrace --async_start -b 4096 -a org.streampocket gfx view sched disk dalvik");traceStarted=true }
            shell("dumpsys gfxinfo org.streampocket reset")
            repeat(30) { shell("input swipe 240 650 240 300 300");Thread.sleep(200) }
            Thread.sleep(2000)
            val output=java.io.File(instrument.targetContext.getExternalFilesDir(null),"real-clock-scroll-frames.txt")
            output.writeText(shell("dumpsys gfxinfo org.streampocket framestats"))
            if(trace) {
                val traceOutput=java.io.File(instrument.targetContext.getExternalFilesDir(null),"app-list-atrace.txt")
                traceOutput.writeText(shell("atrace --async_stop"))
                traceStarted=false
            }
            instrument.sendStatus(0,android.os.Bundle().apply {
                putLong("first_result_ms",first);putLong("all_results_ms",complete);putInt("results",1000);putInt("swipes",30)
                putString("frame_file",output.absolutePath);putString("scope","real Activity frame clock; debug software emulator480x800; injected DNS/controlled HTTP; no physical phone claim")
            })
        } finally {
            if(traceStarted) shell("atrace --async_stop")
            scenario.onActivity { model.cancelSearch() };scenario.close();server.shutdown()
        }
    }
}
