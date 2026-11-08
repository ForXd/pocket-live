// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue

@Composable internal fun RecentWatchingScreen(history:List<History>,model:AppModel) {
    val groups=remember(history) { HistoryGroups.group(history) }
    if(groups.isEmpty()) { EmptyPanel("最近观看","播放影片后，在这里滑动卡片继续观看。",R.drawable.ic_history);return }
    val pager=rememberPagerState(pageCount={groups.size})
    Column(Modifier.fillMaxSize().testTag("recentWatching")) {
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("接着看",style=MaterialTheme.typography.titleLarge)
            Text("${pager.currentPage+1} / ${groups.size}",style=MaterialTheme.typography.labelLarge)
        }
        BoxWithConstraints(Modifier.weight(1f)) {
        val posterHeight=(maxHeight*0.52f).coerceIn(140.dp,320.dp)
        HorizontalPager(state=pager,key={groups[it].key.stableKey},contentPadding=PaddingValues(horizontal=32.dp),pageSpacing=(-12).dp,
            beyondViewportPageCount=1,modifier=Modifier.fillMaxSize().testTag("recentPager")) { page ->
            val entry=groups[page].latest
            Card(Modifier.fillMaxSize().padding(vertical=12.dp).zIndex(1f-((pager.currentPage-page)+pager.currentPageOffsetFraction).absoluteValue).graphicsLayer {
                val distance=((pager.currentPage-page)+pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f,1f)
                translationX=((pager.currentPage-page)+pager.currentPageOffsetFraction)*size.width*0.12f
                scaleX=1f-distance*0.08f;scaleY=1f-distance*0.08f;alpha=1f-distance*0.25f
            },shape=MaterialTheme.shapes.medium,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Poster(entry.playback.video,model,Modifier.fillMaxWidth().height(posterHeight),shape=androidx.compose.ui.graphics.RectangleShape)
                    Column(Modifier.padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(entry.playback.video.title,style=MaterialTheme.typography.headlineSmall)
                        Text(entry.playback.episode.name,style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
                        Text("${entry.playback.video.source.name} · ${historyTime(entry.updated)}",style=MaterialTheme.typography.bodySmall)
                        Text(HistoryGroups.progress(entry),style=MaterialTheme.typography.bodyMedium)
                    }
                }
                Button(shape=MaterialTheme.shapes.medium,onClick={model.resume(entry)},modifier=Modifier.padding(horizontal=20.dp,vertical=12.dp).fillMaxWidth().heightIn(min=48.dp).testTag("recentResume:${groups[page].key.stableKey}")) { Text(if(entry.playback.video.source.kind==SourceKind.LIVE) "打开直播" else "继续观看") }
            }
        }
        }
    }
}
