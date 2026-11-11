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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class,ExperimentalMaterial3Api::class)
@Composable fun HistoryScreen(history:List<History>,resume:(History)->Unit,remove:(HistoryGroupKey)->Unit,clear:()->Unit,model:AppModel?=null) {
    // This screen is not composed during playback. At most 100 records are grouped
    // once per changed snapshot, never on the player's position sampling tick.
    val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val imeVisible=WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)>0
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
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
        Row(Modifier.fillMaxWidth().heightIn(min=68.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("历史",Modifier.weight(1f),style=MaterialTheme.typography.headlineMedium)
            IconButton(onClick={searchOpen=!searchOpen}) { Icon(painterResource(R.drawable.ic_search),"搜索历史") }
            IconButton(onClick={filtersOpen=true}) { Icon(painterResource(R.drawable.ic_sort),"筛选历史") }
        }
        if(searchOpen) CollapsingHomeSearch(searchScroll,statusBars=false) {
        ContentSearchField(query,{query=it.take(100)},"搜索片名、来源或集数",onSearch={keyboard?.hide()},modifier=Modifier.fillMaxWidth().padding(vertical=4.dp).testTag("historySearch")) {
            if(query.isNotEmpty()) IconButton(onClick={query=""},modifier=Modifier.size(48.dp)) { Icon(Icons.Default.Close,"清空历史关键词") }
        }
        }
        if(filtersOpen) ModalBottomSheet(onDismissRequest={filtersOpen=false}) {
        SheetSystemBars()
        Column(Modifier.padding(horizontal=20.dp,vertical=16.dp)) {
        Text("筛选历史",style=MaterialTheme.typography.titleLarge)

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
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Text("${groups.size} 部 · ${filtered.size} 条记录",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(history.isNotEmpty()) TextButton(onClick={clearing=true},colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) { Text("清空历史") }
            if(query.isNotEmpty() || source!=null || period!=HistoryPeriod.ALL || kind!=HistoryKind.ALL) TextButton(onClick={query="";source=null;period=HistoryPeriod.ALL;kind=HistoryKind.ALL}) { Text("重置") }
        }
        Button(shape=MaterialTheme.shapes.medium,onClick={filtersOpen=false},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("完成") }
        }
    }
    }
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.weight(1f).testTag("historyGroups")) {
        if(groups.isEmpty()) item { EmptyPanel(if(history.isEmpty()) "还没有观看记录" else "没有匹配记录",if(history.isEmpty()) "播放影片后，可在这里继续观看。" else "试试其他关键词或重置筛选。",R.drawable.ic_history) }
        groups.forEachIndexed { index,group ->
            val latest=group.latest
            val id=group.key.stableKey
            item(key="group:$id") {
                var menu by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().testTag("historyGroup:$id")) {
                    if(index==0 && model!=null) Box {
                        Poster(latest.playback.video,model,Modifier.fillMaxWidth().aspectRatio(1.95f),shape=MaterialTheme.shapes.small,contentScale=ContentScale.Crop)
                        FilledTonalIconButton(onClick={resume(latest)},modifier=Modifier.align(Alignment.BottomEnd).padding(12.dp).size(48.dp).testTag("continue:$id")) { Icon(painterResource(R.drawable.ic_play),"继续观看") }
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        if(index>0 && model!=null) Poster(latest.playback.video,model,Modifier.size(80.dp,90.dp),shape=MaterialTheme.shapes.small,contentScale=ContentScale.Crop)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(latest.playback.video.title,style=if(index==0) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
                            Text("${latest.playback.episode.name} · ${HistoryGroups.progress(latest)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(latest.playback.video.source.name,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if(index>0 || model==null) IconButton(onClick={resume(latest)},modifier=Modifier.size(48.dp).testTag("continue:$id")) { Icon(painterResource(R.drawable.ic_play),"继续观看") }
                        Box {
                            IconButton(onClick={menu=true}) { Icon(Icons.Default.MoreVert,"管理记录 ${latest.playback.video.title}") }
                            DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                                DropdownMenuItem(text={Text(if(expanded==id) "收起分集" else "分集（${group.episodes.size}）")},onClick={expanded=if(expanded==id) null else id;expandedEpisode=null;menu=false})
                                DropdownMenuItem(text={Text("删除记录")},onClick={deleting=group.key;menu=false})
                            }
                        }
                    }
                    if(index>0) ContentDivider()
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
