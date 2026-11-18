package org.streampocket

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Run separately after importing a verified authorized subscription on the device.
 * A missing opt-in is an explicit JUnit skip, never a passed performance measurement. */
class SearchPerformanceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun measureRealIncrementalSearch() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("NOT MEASURED: explicit perfQuery and authorized sources required",args.containsKey("perfQuery"))
        val query=args.getString("perfQuery")!!
        val model=ui.runOnIdle { ViewModelProvider(ui.activity)[AppModel::class.java] }
        ui.onNodeWithTag("openSearch").performClick()
        ui.onNodeWithTag("searchInput").performTextReplacement(query)
        val start=android.os.SystemClock.elapsedRealtime();var first=-1L;var done=-1L;var allCompleted=false
        ui.onNodeWithContentDescription("开始搜索").performClick()
        ui.waitUntil(120_000) {
            val state=model.state.value
            if(state.searchQuery==query.trim() && state.total>0) {
                val elapsed=android.os.SystemClock.elapsedRealtime()-start
                if(first<0 && state.results.isNotEmpty()) first=elapsed
                if(!state.searching) {
                    allCompleted=state.completed==state.total
                    done=elapsed
                }
            }
            done>=0
        }
        assertTrue("No measurable search completion",done>=0)
        InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {
            putLong("actual_search_first_result_ms",first) // -1 explicitly means no result, not zero latency
            putLong("actual_search_finished_ms",done)
            putLong("actual_search_all_sources_ms",if(allCompleted) done else -1)
            putBoolean("all_sources_completed",allCompleted)
            putString("measurement_type","operator-verified-authorized-source-search")
        })
    }
}
