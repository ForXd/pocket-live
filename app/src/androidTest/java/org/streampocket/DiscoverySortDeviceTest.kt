package org.streampocket

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.*
import org.junit.Assert.*

class DiscoverySortDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun sortMenuReordersAndPreservesTabChoicesWithMissingMetadata() {
        val source=Source("排序测试","https://fixture.test/api")
        val low=Video(source,"a","Alpha",rating=7.0,updatedAt=1000)
        val high=Video(source,"b","Beta",rating=9.0,updatedAt=2000)
        val unknown=Video(source,"c","Charlie")
        var snapshot by mutableStateOf(UiState(loading=false,recommendations=listOf(low,high,unknown),
            saved=Saved(subscriptions=listOf(Subscription("s","测试订阅",source.url,1,listOf(source)))),
            catalogSource=source,catalogVideos=listOf(low,high,unknown)))
        lateinit var model:AppModel
        ui.runOnUiThread {
            ui.activity.viewModelStore.clear()
            model=ViewModelProvider(ui.activity)[AppModel::class.java]
            ui.activity.setContent { PocketTheme { androidx.compose.material3.Surface(Modifier.safeDrawingPadding()) { DiscoverScreen(snapshot,model,{},{}) } } }
        }
        fun choose(label:String,grid:Boolean) {
            ui.onNodeWithTag(if(grid) "recommendationGrid" else "catalogList").performScrollToNode(hasTestTag("discoverySort"))
            ui.onNodeWithTag("discoverySort").performClick()
            ui.onNodeWithText(label).performClick()
        }
        try {
            choose("评分最高",true)
            ui.onNodeWithTag("recommendationGrid").performScrollToNode(hasTestTag("featured:b"))
            ui.onNodeWithTag("featured:b").assertExists()
            // Another loaded page must participate in the existing sort.
            ui.runOnIdle { snapshot=snapshot.copy(recommendations=snapshot.recommendations+Video(source,"d","Delta",rating=10.0)) }
            ui.onNodeWithTag("recommendationGrid").performScrollToNode(hasTestTag("featured:d"))
            ui.onNodeWithTag("featured:d").assertExists()
            ui.onNodeWithTag("recommendationGrid").performScrollToNode(hasTestTag("discoverySort"))
            ui.onNodeWithTag("discoverySort").performClick()
            ui.onNodeWithText("最近更新").assertIsNotEnabled()
            ui.onNodeWithText("默认顺序").performClick()
            ui.onNodeWithTag("recommendationGrid").performScrollToNode(hasTestTag("featured:a"))
            choose("评分最高",true)
            ui.onNodeWithText("订阅分类").performClick()
            choose("最近更新",false)
            ui.onNodeWithTag("catalogList").performScrollToNode(hasTestTag("catalogVideo:b"))
            val b=ui.onNodeWithTag("catalogVideo:b").getUnclippedBoundsInRoot().top
            val a=ui.onNodeWithTag("catalogVideo:a").getUnclippedBoundsInRoot().top
            assertTrue(b<a)
            captureUi(ui,"discovery-sort-updated")
            ui.onNodeWithText("剧集推荐").performClick()
            ui.onNodeWithTag("recommendationGrid").performScrollToNode(hasTestTag("discoverySort"))
            ui.onNodeWithTag("discoverySort").assertTextContains("评分最高",substring=true)
            captureUi(ui,"discovery-sort-rating")
        } finally { ui.runOnUiThread { ui.activity.viewModelStore.clear() } }
    }
}
