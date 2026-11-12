// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable fun SeasonChips(videos:List<Video>,selected:Video,choose:(Video)->Unit) {
    val seasons=remember(videos) { VideoGroups.seasons(videos) }
    if(seasons.size>1) LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth().testTag("seasonPicker")) {
        items(seasons,key={VideoGroups.workKey(it.first())}) { options ->
            val video=options.first();val identity=VideoGroups.identity(video)
            ContentFilterChip(selected=VideoGroups.sameWork(video,selected),onClick={choose(options.firstOrNull { it.source.key==selected.source.key } ?: video)},
                label={Text(identity.label+(if(seasons.count { VideoGroups.identity(it.first()).season==identity.season }>1 && identity.year.isNotEmpty()) " · ${identity.year}" else ""))},
                modifier=Modifier.testTag("season:${identity.season}:${identity.year}"))
        }
    }
}

/** Shared source rows for search/detail and player; availability never inferred from metadata. */
@Composable fun SourceChoices(videos:List<Video>,selected:Video?,state:UiState,choose:(Video)->Unit,limit:Int=Int.MAX_VALUE) {
    if(videos.size>3 && limit>3) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max=280.dp)) {
            items(videos.take(limit),key={it.probeKey()}) { video -> SourceChoices(listOf(video),selected,state,choose,1) }
        }
        return
    }
    videos.take(limit).forEach { video ->
        Surface(onClick={choose(video)},color=if(selected?.probeKey()==video.probeKey()) glassFill(true) else androidx.compose.ui.graphics.Color.Transparent,
            shape=MaterialTheme.shapes.small,modifier=Modifier.fillMaxWidth().testTag("sourceChoice:${video.source.key}:${video.id}")) {
            Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text(video.source.name,style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                    val label=if(selected?.probeKey()==video.probeKey()) "当前来源" else video.subtitle
                    if(state.probes[video.probeKey()]==ProbeStatus.READY) Text(state.probeResolutions[video.probeKey()]?.let { "播放清单 · $it" } ?: "清晰度未确认",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("probeResolution:${video.id}"))
                    else if(video.quality.evidence!=QualityEvidence.UNKNOWN) QualityBadge(video.quality,lastPlayback=true)
                    if(label.isNotEmpty()) Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                MediaProbeIndicator(state.probes[video.probeKey()],textModifier=Modifier.testTag("probe:${video.id}"))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable fun CollectionControls(current:Video,state:UiState,model:AppModel,choose:(Video)->Unit) {
    val options=remember(current,state.relatedVideos) { VideoGroups.options(current,state.relatedVideos) }
    var sourcesOpen by remember(current.probeKey()) { mutableStateOf(false) }
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        SeasonChips(options,current,choose)
        val sources=remember(options,current) { options.filter { VideoGroups.sameWork(it,current) } }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={sourcesOpen=!sourcesOpen},modifier=Modifier.weight(1f,false).testTag("collectionSources")) { Text(if(sourcesOpen) "收起来源" else "${sources.size} 个来源",maxLines=1) }
            TextButton(onClick={model.related(current,true)},enabled=!state.relatedLoading,modifier=Modifier.testTag("findSeasons")) { Text(if(state.relatedLoading) "查找中…" else "查找更多") }
        }
        if(sourcesOpen) SourceChoices(sources,current,state,choose)
        state.relatedError?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
