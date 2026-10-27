package org.streampocket

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
        ui.waitUntil(5000) { ui.onAllNodesWithTag("searchInput").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("searchInput").performTextReplacement(query)
        val start=android.os.SystemClock.elapsedRealtime();var first=-1L;var done=-1L;var allCompleted=false
        ui.onNodeWithContentDescription("开始搜索").performClick()
        ui.waitUntil(120_000) {
            val text=ui.onAllNodes(hasText("已完成",substring=true)).fetchSemanticsNodes().firstOrNull()
                ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
            val numbers=Regex("已完成 (\\d+)/(\\d+) 个源.*?· (\\d+) 条结果").find(text)
            if(numbers!=null) {
                val count=numbers.groupValues[3].toInt()
                if(count>0 && first<0) first=android.os.SystemClock.elapsedRealtime()-start
                allCompleted=numbers.groupValues[1]==numbers.groupValues[2] && numbers.groupValues[2]!="0"
                val stopped=ui.onAllNodesWithText("取消搜索").fetchSemanticsNodes().isEmpty()
                if(allCompleted || (numbers.groupValues[2]!="0" && stopped)) done=android.os.SystemClock.elapsedRealtime()-start
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
