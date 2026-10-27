package org.streampocket

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Rule
import org.junit.Assert.*

class HistoryGroupsUiTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun groupedCardsExpandContinueAndDelete() {
        val source=Source("源一","https://one.example/api")
        fun history(id:String,ep:Int,src:Source=source)=History(Playback(Video(src,id,"剧目$id"),Episode("第${ep}集","https://media.example/$id/$ep.mp4"),ep*1000L),ep.toLong())
        val a=history("a",1);val a2=history("a",2);val b=history("b",3);val other=history("a",4,source.copy(name="源二",url="https://two.example/api"))
        val sameEpisode=a.copy(playback=a.playback.copy(video=a.playback.video.copy(source=source.copy(name="源三",url="https://three.example/api")),episode=a.playback.episode.copy(name="EP01"),position=81000),updated=5)
        val records=mutableStateOf(listOf(a2,a,b,other,sameEpisode));var selected:History?=null
        ui.runOnUiThread { ui.activity.setContent { PocketTheme { androidx.compose.material3.Surface(Modifier.safeDrawingPadding()) { HistoryScreen(records.value,{selected=it},{key -> records.value=HistoryGroups.remove(Saved(history=records.value),key).history},{records.value=emptyList()}) } } } }
        val id=HistoryGroups.key(a.playback.video).stableKey
        ui.onNodeWithTag("historyGroups").performScrollToNode(hasTestTag("historyGroup:$id"))
        ui.onNodeWithTag("continue:$id").performClick();ui.runOnIdle { assertEquals(sameEpisode,selected) }
        ui.onNodeWithText("查看分集（3）").performClick()
        val episodeId="$id:${HistoryGroups.episodeKey(a)}"
        ui.onNodeWithTag("historyGroups").performScrollToNode(hasTestTag("continueEpisode:$episodeId"))
        ui.onNodeWithTag("continueEpisode:$episodeId").performClick();ui.runOnIdle { assertEquals(sameEpisode,selected) }
        ui.onNodeWithTag("expandEpisode:$episodeId").performClick()
        ui.onNodeWithTag("historyGroups").performScrollToNode(hasTestTag("historyEpisode:${sameEpisode.playback.key}"))
        ui.onNodeWithTag("historyEpisode:${sameEpisode.playback.key}").performClick();ui.runOnIdle { assertEquals(81000L,selected!!.playback.position) }
        ui.onNodeWithTag("historyGroups").performScrollToNode(hasTestTag("historyEpisode:${a.playback.key}"))
        ui.onNodeWithTag("historyEpisode:${a.playback.key}").performClick();ui.runOnIdle { assertEquals(a,selected) }
        val instrument=InstrumentationRegistry.getInstrumentation()
        val dir=java.io.File(instrument.targetContext.getExternalFilesDir(null),"history-code7").apply { mkdirs() }
        instrument.uiAutomation.takeScreenshot()?.let { image -> try { java.io.File(dir,"expanded.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() } }
        ui.onNodeWithTag("historyGroups").performScrollToNode(hasTestTag("historyGroup:$id"))
        ui.onNode(hasText("删除记录") and hasAnyAncestor(hasTestTag("historyGroup:$id"))).performClick()
        ui.onNodeWithText("删除").performClick();ui.runOnIdle { assertEquals(listOf(b),records.value) }
        ui.onNodeWithTag("historyGroups").performScrollToIndex(0)
        ui.onNodeWithText("清空历史").performClick();ui.onNodeWithText("清空").performClick()
        ui.onNodeWithText("继续上次的精彩").assertExists()
        instrument.sendStatus(0,android.os.Bundle().apply { putString("history_ui_result","grouped latest continue;expanded episode continue;delete one group;clear passed;Compose semantics not OS touch") })
    }
}
