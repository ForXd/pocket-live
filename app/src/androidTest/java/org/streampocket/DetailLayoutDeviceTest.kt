package org.streampocket

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import org.junit.*
import org.junit.Assert.*

class DetailLayoutDeviceTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun largeCatalogLineSelectionAndDescriptionRemainReachable() {
        lateinit var model:AppModel
        var searches=0
        val video=Video(Source("本地测试线路","https://fixture.test/api"),"gallery","星河漫游")
        val detail=Detail(video,"一次穿越群星的旅程。".repeat(35),listOf(
            Line("主线路",(1..500).map { Episode("第${it}集","https://fixture.test/a/$it.mp4") }),
            Line("备用线路",listOf(Episode("特别篇","https://fixture.test/b/special.mp4")))))
        ui.runOnUiThread {
            ui.activity.viewModelStore.clear()
            model=ViewModelProvider(ui.activity)[AppModel::class.java]
            ui.activity.setContent { PocketTheme { androidx.compose.material3.Surface(Modifier.safeDrawingPadding()) { DetailScreen(detail,model) { searches++ } } } }
        }
        try {
            ui.waitUntil(10000) { !model.state.value.loading }
            ui.onNodeWithTag("detailGrid").performScrollToNode(hasText("展开简介"))
            ui.onNodeWithText("展开简介").performClick()
            ui.onNodeWithTag("detailGrid").performScrollToNode(hasText("收起简介"))
            ui.onNodeWithText("收起简介").performClick()
            ui.onNodeWithTag("detailGrid").performScrollToIndex(0)
            captureUi(ui,"detail-header")
            ui.onNodeWithTag("detailGrid").performScrollToNode(hasText("第500集"))
            ui.onNodeWithText("第500集").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            captureUi(ui,"detail-last-episode")
            ui.onNodeWithTag("detailGrid").performScrollToNode(hasText("备用线路 · 1 集"))
            ui.onNodeWithText("备用线路 · 1 集").performClick()
            ui.onNodeWithTag("detailGrid").performScrollToNode(hasText("特别篇"))
            ui.onNodeWithText("特别篇").performClick()
            ui.waitUntil(5000) { model.state.value.playback?.episode?.url=="https://fixture.test/b/special.mp4" }
            ui.onNodeWithTag("detailGrid").performScrollToNode(hasText("搜索同名 · 换源"))
            ui.onNodeWithText("搜索同名 · 换源").performClick()
            assertEquals(1,searches)
        } finally { ui.runOnUiThread { ui.activity.viewModelStore.clear() } }
    }
}
