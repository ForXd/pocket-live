// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable fun HistoryScreen(history:List<History>,resume:(History)->Unit,remove:(HistoryGroupKey)->Unit,clear:()->Unit,model:AppModel?=null) {
    // This screen is not composed during playback. At most 100 records are grouped
    // once per changed snapshot, never on the player's position sampling tick.
    val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val imeVisible=WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)>0
    var query by rememberSaveable { mutableStateOf("") }
    var period by rememberSaveable { mutableStateOf(HistoryPeriod.ALL) }
    var kind by rememberSaveable { mutableStateOf(HistoryKind.ALL) }
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceMenu by remember { mutableStateOf(false) }
    val now=remember(history) { System.currentTimeMillis() }
    val filtered=remember(history,query,source,period,kind,now) { queryHistory(history,query,source,period,kind,now) }
    val groups=remember(filtered) { HistoryGroups.group(filtered) }
    val sources=remember(history) { history.map { it.playback.video.source }.distinctBy { it.key } }

    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedEpisode by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<HistoryGroupKey?>(null) }
    var clearing by remember { mutableStateOf(false) }
    val searchScroll=rememberSaveable(saver=HomeSearchScroll.Saver) { HomeSearchScroll() }
    LaunchedEffect(imeVisible) { if(imeVisible) searchScroll.reveal() }
    val scrollConnection=rememberSearchScrollConnection(searchScroll,!imeVisible && groups.isNotEmpty())
    Column(Modifier.fillMaxSize().nestedScroll(scrollConnection)) {
    Column(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        CollapsingHomeSearch(searchScroll,statusBars=false) {
        ContentSearchField(query,{query=it.take(100)},"搜索片名、来源或集数",onSearch={keyboard?.hide()},modifier=Modifier.fillMaxWidth().padding(vertical=4.dp).testTag("historySearch")) {
            if(query.isNotEmpty()) IconButton(onClick={query=""},modifier=Modifier.size(48.dp)) { Icon(Icons.Default.Close,"清空历史关键词") }
        }
        }
        if(!imeVisible) {
        LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            items(HistoryPeriod.entries) { option -> ContentFilterChip(selected=period==option,onClick={period=option},label={Text(option.label)},modifier=Modifier.testTag("historyPeriod:${option.name}")) }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Box {
                OutlinedButton(shape=MaterialTheme.shapes.small,border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f)),colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.onSurface),onClick={sourceMenu=true},modifier=Modifier.widthIn(max=180.dp).testTag("historySource")) { Text(sources.find { it.key==source }?.name ?: "全部来源",maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,modifier=Modifier.weight(1f,false));Icon(Icons.Default.ArrowDropDown,null) }
                DropdownMenu(expanded=sourceMenu,onDismissRequest={sourceMenu=false}) {
                    DropdownMenuItem(text={Text("全部来源")},onClick={source=null;sourceMenu=false})
                    sources.forEach { option -> DropdownMenuItem(text={Text(option.name)},onClick={source=option.key;sourceMenu=false}) }
                }
            }
            LazyRow(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(6.dp)) { items(HistoryKind.entries) { option -> ContentFilterChip(selected=kind==option,onClick={kind=option},label={Text(option.label)}) } }
        }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Text("${groups.size} 部 · ${filtered.size} 条记录",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(history.isNotEmpty()) TextButton(onClick={clearing=true},colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) { Text("清空历史") }
            if(query.isNotEmpty() || source!=null || period!=HistoryPeriod.ALL || kind!=HistoryKind.ALL) TextButton(onClick={query="";source=null;period=HistoryPeriod.ALL;kind=HistoryKind.ALL}) { Text("重置") }
        }
    }
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.weight(1f).testTag("historyGroups")) {
        if(groups.isEmpty()) item { EmptyPanel(if(history.isEmpty()) "还没有观看记录" else "没有匹配记录",if(history.isEmpty()) "播放影片后，可在这里继续观看。" else "试试其他关键词或重置筛选。",R.drawable.ic_history) }
        groups.forEach { group ->
            val latest=group.latest
            val id=group.key.stableKey
            item(key="group:$id") {
                ContentCard(modifier=Modifier.fillMaxWidth().testTag("historyGroup:$id")) {
                    Box(Modifier.fillMaxWidth().background(Color(0xff101012))) {
                        if(model!=null) Poster(latest.playback.video,model,Modifier.fillMaxWidth().height(172.dp),shape=RectangleShape,contentScale=ContentScale.Crop)
                        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color(0xee101012)))))
                        Column(Modifier.fillMaxWidth().padding(start=16.dp,end=16.dp,top=88.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(latest.playback.video.title,style=MaterialTheme.typography.titleLarge,color=Color.White)
                            Text("${latest.playback.episode.name} · ${HistoryGroups.progress(latest)}",style=MaterialTheme.typography.bodyMedium,color=Color(0xffe2dce0))
                        }
                    }
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("${group.entries.map { it.playback.video.source.key }.distinct().size} 个来源 · 最近：${latest.playback.video.source.name}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(latest.playback.video.subtitle.isNotBlank()) Text(latest.playback.video.subtitle,style=MaterialTheme.typography.bodySmall,maxLines=2)
                        Text(historyTime(latest.updated),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Button(shape=MaterialTheme.shapes.medium,onClick={resume(latest)},modifier=Modifier.heightIn(min=48.dp).testTag("continue:$id")) {
                            Text(if(latest.playback.video.source.kind==SourceKind.LIVE) "打开直播" else "继续观看")
                        }
                            TextButton(onClick={expanded=if(expanded==id) null else id;expandedEpisode=null},modifier=Modifier.heightIn(min=48.dp)) { Text(if(expanded==id) "收起分集" else "分集（${group.episodes.size}）") }
                            TextButton(onClick={deleting=group.key},modifier=Modifier.heightIn(min=48.dp),colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) { Text("删除记录") }
                        }
                    }
                }
            }
            if(expanded==id) group.episodes.forEach { episode ->
                val episodeId="$id:${episode.key}"
                item(key="episode-group:$episodeId") {
                    ContentCard(Modifier.fillMaxWidth().padding(start=12.dp).testTag("historyEpisodeGroup:$episodeId")) {
                        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick={expandedEpisode=if(expandedEpisode==episodeId) null else episodeId},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("expandEpisode:$episodeId")) {
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                    Text(episode.latest.playback.episode.name,style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurface)
                                    Text("${episode.entries.size} 条来源记录 · 最近：${episode.latest.playback.video.source.name}",style=MaterialTheme.typography.bodySmall)
                                }
                                Text(if(expandedEpisode==episodeId) "收起" else "展开")
                            }
                            Text(HistoryGroups.progress(episode.latest),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(horizontal=12.dp))
                            TextButton(onClick={resume(episode.latest)},modifier=Modifier.heightIn(min=48.dp).testTag("continueEpisode:$episodeId")) { Text("继续本集") }
                        }
                    }
                }
                if(expandedEpisode==episodeId) items(episode.entries,key={"record:$id:${it.playback.key}"}) { entry ->
                    ContentCard(onClick={resume(entry)},modifier=Modifier.fillMaxWidth().padding(start=24.dp).testTag("historyEpisode:${entry.playback.key}")) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(entry.playback.video.source.name,style=MaterialTheme.typography.titleSmall)
                            Text(entry.playback.episode.name,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                            Text(HistoryGroups.progress(entry),style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    }
    deleting?.let { key -> AlertDialog(onDismissRequest={deleting=null},title={Text("删除这部剧的记录？")},text={Text("删除这部剧在所有合并来源下的观看断点，其他剧目保留。")},confirmButton={TextButton(onClick={remove(key);deleting=null}) { Text("删除") }},dismissButton={TextButton(onClick={deleting=null}) { Text("取消") }}) }
    if(clearing) AlertDialog(onDismissRequest={clearing=false},title={Text("清空观看历史？")},text={Text("所有剧目的观看断点将被删除，订阅仍会保留。")},confirmButton={TextButton(onClick={clear();clearing=false}) { Text("清空") }},dismissButton={TextButton(onClick={clearing=false}) { Text("取消") }})
}
