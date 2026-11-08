// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

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
    Column(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(query,{query=it.take(100)},singleLine=true,keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Search),keyboardActions=androidx.compose.foundation.text.KeyboardActions(onSearch={keyboard?.hide()}),label={Text("片名、来源或集数")},modifier=Modifier.fillMaxWidth().testTag("historySearch"),shape=MaterialTheme.shapes.medium)
        if(!imeVisible) {
        LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            items(HistoryPeriod.entries) { option -> FilterChip(selected=period==option,onClick={period=option},label={Text(option.label)},modifier=Modifier.testTag("historyPeriod:${option.name}")) }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Box {
                TextButton(onClick={sourceMenu=true},modifier=Modifier.widthIn(max=160.dp).testTag("historySource")) { Text(sources.find { it.key==source }?.name ?: "全部来源",maxLines=1) }
                DropdownMenu(expanded=sourceMenu,onDismissRequest={sourceMenu=false}) {
                    DropdownMenuItem(text={Text("全部来源")},onClick={source=null;sourceMenu=false})
                    sources.forEach { option -> DropdownMenuItem(text={Text(option.name)},onClick={source=option.key;sourceMenu=false}) }
                }
            }
            LazyRow(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(6.dp)) { items(HistoryKind.entries) { option -> FilterChip(selected=kind==option,onClick={kind=option},label={Text(option.label)}) } }
        }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Text("${groups.size} 部 · ${filtered.size} 条记录",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(query.isNotEmpty() || source!=null || period!=HistoryPeriod.ALL || kind!=HistoryKind.ALL) TextButton(onClick={query="";source=null;period=HistoryPeriod.ALL;kind=HistoryKind.ALL}) { Text("重置") }
        }
    }
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.weight(1f).testTag("historyGroups")) {
        item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                if(history.isNotEmpty()) TextButton(onClick={clearing=true},modifier=Modifier.heightIn(min=48.dp),colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) { Text("清空历史") }
            }
        }
        if(groups.isEmpty()) item { EmptyPanel(if(history.isEmpty()) "还没有观看记录" else "没有匹配记录",if(history.isEmpty()) "播放影片后，可在这里继续观看。" else "试试其他关键词或重置筛选。",R.drawable.ic_history) }
        groups.forEach { group ->
            val latest=group.latest
            val id=group.key.stableKey
            item(key="group:$id") {
                ContentCard(modifier=Modifier.fillMaxWidth().testTag("historyGroup:$id")) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                            if(model!=null) Poster(latest.playback.video,model,Modifier.size(88.dp,132.dp))
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text(latest.playback.video.title,style=MaterialTheme.typography.titleMedium)
                                Text("${group.entries.map { it.playback.video.source.key }.distinct().size} 个来源 · 最近：${latest.playback.video.source.name}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(latest.playback.episode.name,style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.primary)
                                if(latest.playback.video.subtitle.isNotBlank()) Text(latest.playback.video.subtitle,style=MaterialTheme.typography.bodySmall,maxLines=2)
                                Text(historyTime(latest.updated),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(HistoryGroups.progress(latest),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            TextButton(onClick={resume(latest)},modifier=Modifier.heightIn(min=48.dp).testTag("continue:$id")) {
                            Text(if(latest.playback.video.source.kind==SourceKind.LIVE) "打开直播" else "继续观看")
                        }
                            TextButton(onClick={expanded=if(expanded==id) null else id;expandedEpisode=null},modifier=Modifier.weight(1f)) { Text(if(expanded==id) "收起分集" else "分集（${group.episodes.size}）") }
                            TextButton(onClick={deleting=group.key},modifier=Modifier.weight(1f),colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) { Text("删除记录") }
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
