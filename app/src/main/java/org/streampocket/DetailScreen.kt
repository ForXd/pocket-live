// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Visual state is scoped to a detail, never stored in the playback/session model. */
@Composable fun DetailScreen(detail:Detail,model:AppModel,searchSame:()->Unit) {
    var selected by rememberSaveable(detail.video.source.key,detail.video.id) { mutableIntStateOf(0) }
    var expanded by rememberSaveable(detail.video.source.key,detail.video.id) { mutableStateOf(false) }
    var descriptionOverflows by remember(detail.description) { mutableStateOf(false) }
    val line=detail.lines.getOrNull(selected) ?: detail.lines.firstOrNull()
    LazyVerticalGrid(columns=GridCells.Adaptive(if(LocalDensity.current.fontScale>1.3f) 180.dp else 132.dp),
        modifier=Modifier.fillMaxSize().testTag("detailGrid"),contentPadding=PaddingValues(20.dp),
        horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item(span={GridItemSpan(maxLineSpan)}) {
            Row(horizontalArrangement=Arrangement.spacedBy(18.dp),verticalAlignment=Alignment.CenterVertically) {
                Poster(detail.video,model,Modifier.size(104.dp,156.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(detail.video.source.name,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                    Text(detail.video.title,style=MaterialTheme.typography.headlineSmall)
                    Text("${detail.lines.size} 条线路 · ${line?.episodes?.size ?: 0} 集",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    QualityBadge(detail.video.quality,lastPlayback=true)
                }
            }
        }
        if(detail.description.isNotBlank()) item(span={GridItemSpan(maxLineSpan)}) {
            Column {
                Text(detail.description,onTextLayout={if(!expanded) descriptionOverflows=it.hasVisualOverflow},maxLines=if(expanded) Int.MAX_VALUE else 3,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(expanded || descriptionOverflows) TextButton(onClick={expanded=!expanded}) { Text(if(expanded) "收起简介" else "展开简介") }
            }
        }
        item(span={GridItemSpan(maxLineSpan)}) {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                line?.episodes?.firstOrNull()?.let { first ->
                    Button(onClick={model.play(detail.video,first)},modifier=Modifier.heightIn(min=48.dp)) {
                        Icon(painterResource(R.drawable.ic_play),null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("播放 · ${first.name}")
                    }
                }
                OutlinedButton(onClick=searchSame,modifier=Modifier.heightIn(min=48.dp)) { Text("搜索同名 · 换源") }
            }
        }
        item(span={GridItemSpan(maxLineSpan)}) { Text("选择剧集",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=12.dp)) }
        item(span={GridItemSpan(maxLineSpan)}) {
            LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                itemsIndexed(detail.lines) { index,option ->
                    FilterChip(selected=line===option,onClick={selected=index},label={Text("${option.name} · ${option.episodes.size} 集")},modifier=Modifier.heightIn(min=48.dp))
                }
            }
        }
        line?.let { current -> itemsIndexed(current.episodes,key={index,_->"$selected:$index"}) { _,episode ->
            FilledTonalButton(onClick={model.play(detail.video,episode)},shape=MaterialTheme.shapes.medium,
                modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {
                Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(episode.name,maxLines=2,overflow=TextOverflow.Ellipsis)
                    if(episode.quality.evidence!=QualityEvidence.UNKNOWN) Text(episode.quality.label,style=MaterialTheme.typography.labelSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
            }
        } }
        item(span={GridItemSpan(maxLineSpan)}) { Text("网页解析、脚本源和 DRM 解锁不受支持。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=12.dp)) }
    }
}
