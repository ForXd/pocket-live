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
        fun history(id:String,ep:Int,src:Source=source)=History(Playback(Video(src,id,"剧目$id"),Episode("第${ep}集","https://media.example/$id/$ep.mp4"),ep*1000L,duration=100_000),ep.toLong())
        val a=history("a",1);val a2=history("a",2);val b=history("b",3);val other=history("a",4,source.copy(name="源二",url="https://two.example/api"))
        val sameEpisode=a.copy(playback=a.playback.copy(video=a.playback.video.copy(source=source.copy(name="源三",url="https://three.example/api")),episode=a.playback.episode.copy(name="EP01"),position=81000),updated=5)
        val records=mutableStateOf(listOf(a2,a,b,other,sameEpisode));var selected:History?=null
        ui.runOnUiThread { ui.activity.setContent { PocketTheme { androidx.compose.material3.Surface(Modifier.safeDrawingPadding()) { HistoryScreen(records.value,{selected=it},{key -> records.value=HistoryGroups.remove(Saved(history=records.value),key).history},{records.value=emptyList()}) } } } }
        val id=HistoryGroups.key(a.playback.video).stableKey
        ui.onNodeWithTag("historyGroups").performScrollToNode(hasTestTag("historyGroup:$id"))
        ui.onNodeWithTag("continue:$id").performClick();ui.runOnIdle { assertEquals(sameEpisode,selected) }
        ui.onNodeWithText("分集进度",substring=true).assertDoesNotExist()
        val instrument=InstrumentationRegistry.getInstrumentation()
        ui.onNodeWithTag("swipeHistory:$id").performTouchInput { swipeLeft() }
        ui.runOnIdle { assertEquals(5,records.value.size) }
        ui.onNodeWithTag("deleteHistory:$id").assertIsDisplayed()
        val card=ui.onNodeWithTag("historyGroup:$id").getUnclippedBoundsInRoot()
        val action=ui.onNodeWithTag("deleteHistory:$id").getUnclippedBoundsInRoot()
        assertEquals(card.top.value,action.top.value,1f)
        assertEquals(card.bottom.value,action.bottom.value,1f)
        assertTrue(card.right < action.left)
        captureUi(ui,"history-swipe-delete")
        ui.onNodeWithContentDescription("删除").performClick();ui.runOnIdle { assertEquals(listOf(b),records.value) }
        ui.onNodeWithTag("historyGroups").performScrollToIndex(0)
        ui.onNodeWithContentDescription("筛选历史").performClick()
        ui.onNodeWithText("清空历史").performClick();ui.onNodeWithText("清空").performClick()
        ui.onNodeWithText("还没有观看记录").assertExists()
        instrument.sendStatus(0,android.os.Bundle().apply { putString("history_ui_result","grouped latest continue;delete one group;clear passed;Compose swipe gesture and explicit delete tap") })
    }
}
