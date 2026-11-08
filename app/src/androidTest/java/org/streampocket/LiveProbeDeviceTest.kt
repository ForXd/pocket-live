package org.streampocket

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Opt-in: private source configuration stays outside the APK and repository. */
class LiveProbeDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun liveQueryAndRepeatedCancellationReleaseResources() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveProbe")=="true")
        val input=java.io.File(ui.activity.getExternalFilesDir(null),"private-subscription.json")
        assertTrue("Private source fixture must be provisioned externally",input.exists())
        val sources=SubscriptionParser.parse(input.readText()).sources.filter { it.kind==SourceKind.VOD }
        input.delete()
        ui.runOnUiThread { ui.activity.viewModelStore.clear() }
        runBlocking { Storage(ui.activity.application).write(Saved(allowHttp=true,subscriptions=listOf(Subscription("live-probe","测试订阅","https://fixture.invalid",1,sources)))) }
        lateinit var model:AppModel
        ui.runOnUiThread {
            model=ViewModelProvider(ui.activity,object:ViewModelProvider.Factory {
                override fun <T:ViewModel> create(modelClass:Class<T>):T {
                    @Suppress("UNCHECKED_CAST") return AppModel(ui.activity.application) as T
                }
            })[AppModel::class.java]
            ui.activity.setContent { PocketTheme { Pocket(model) } }
        }
        try {
            ui.waitUntil(10000) { !model.state.value.loading }
            val players=PlaybackMetrics.created
            ui.onNodeWithTag("searchInput").performTextInput("谷雨")
            ui.onNodeWithTag("searchInput").performImeAction()
            ui.waitUntil(65000) { !model.state.value.searching && !model.state.value.probeRunning }
            val s=model.state.value
            InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {
                putInt("configured_sources",sources.size);putInt("total",s.total);putInt("completed",s.completed);putInt("failures",s.failures);putInt("returned",s.results.size);putString("query",s.searchQuery)
            })
            assertTrue("Live query must return actual matches",s.results.any { SearchRules.relevance(it,"谷雨")>0 })
            assertTrue("Live query must actually attempt media validation",s.probes.isNotEmpty())
            assertTrue(s.probes.values.count { it==ProbeStatus.READY }<=2)
            assertEquals(distinctSearchDomains(sources).size,s.total)
            ui.waitUntil(10000) { model.activeProbeCalls()==0 }
            captureUi(ui,"live-guyu-probes")
            val result=android.os.Bundle().apply {
                putInt("sources_configured",sources.size);putInt("domains_searched",s.total)
                putInt("results",s.results.size);putInt("probe_ready",s.probes.values.count { it==ProbeStatus.READY })
                putInt("probe_failed",s.probes.values.count { it==ProbeStatus.FAILED });putInt("resolutions",s.probeResolutions.size)
            }
            repeat(10) {
                ui.runOnIdle { model.search("谷雨");model.setSearchProbeActive(true) }
                ui.runOnIdle { model.setSearchProbeActive(false);model.cancelSearch() }
                ui.waitUntil(10000) { model.activeProbeCalls()==0 && model.activeSearchCalls()==0 && !model.state.value.probeRunning }
            }
            assertEquals(players,PlaybackMetrics.created)
            assertEquals(0,PlaybackMetrics.samplers)
            assertEquals(0,MediaCallOwner.active.get())
            result.putInt("search_calls_after_cancel",model.activeSearchCalls());result.putInt("probe_calls_after_cancel",model.activeProbeCalls());result.putLong("players_created",PlaybackMetrics.created-players)
            InstrumentationRegistry.getInstrumentation().sendStatus(0,result)
        } finally { ui.runOnUiThread { ui.activity.viewModelStore.clear() };runBlocking { Storage(ui.activity.application).write(Saved()) } }
    }
}
