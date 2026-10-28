// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable fun HistoryScreen(history:List<History>,resume:(History)->Unit,remove:(HistoryGroupKey)->Unit,clear:()->Unit,model:AppModel?=null) {
    // This screen is not composed during playback. At most 100 records are grouped
    // once per changed snapshot, never on the player's position sampling tick.
    val groups=remember(history) { HistoryGroups.group(history) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedEpisode by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<HistoryGroupKey?>(null) }
    var clearing by remember { mutableStateOf(false) }
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.testTag("historyGroups")) {
        item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Text("继续观看",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall)
                if(groups.isNotEmpty()) TextButton(onClick={clearing=true},modifier=Modifier.heightIn(min=48.dp),colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) { Text("清空历史") }
            }
            Text("按剧集与分集整理 · 各来源独立保存进度",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(groups.isEmpty()) item { EmptyPanel("继续上次的精彩","每部剧独立保存断点，展开可查看各集记录。",R.drawable.ic_history) }
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
                                Text("上次观看 · ${latest.playback.episode.name}",style=MaterialTheme.typography.bodyMedium)
                                Text(HistoryGroups.progress(latest),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        FilledTonalButton(onClick={resume(latest)},modifier=Modifier.align(androidx.compose.ui.Alignment.End).heightIn(min=48.dp).testTag("continue:$id")) {
                            Text(if(latest.playback.video.source.kind==SourceKind.LIVE) "打开直播" else "继续观看")
                        }
                        ContentDivider()
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            TextButton(onClick={expanded=if(expanded==id) null else id;expandedEpisode=null},modifier=Modifier.weight(1f)) { Text(if(expanded==id) "收起分集" else "查看分集（${group.episodes.size}）") }
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
    deleting?.let { key -> AlertDialog(onDismissRequest={deleting=null},title={Text("删除这部剧的记录？")},text={Text("删除这部剧在所有合并来源下的观看断点，其他剧目保留。")},confirmButton={TextButton(onClick={remove(key);deleting=null}) { Text("删除") }},dismissButton={TextButton(onClick={deleting=null}) { Text("取消") }}) }
    if(clearing) AlertDialog(onDismissRequest={clearing=false},title={Text("清空观看历史？")},text={Text("所有剧目的观看断点将被删除，订阅仍会保留。")},confirmButton={TextButton(onClick={clear();clearing=false}) { Text("清空") }},dismissButton={TextButton(onClick={clearing=false}) { Text("取消") }})
}
