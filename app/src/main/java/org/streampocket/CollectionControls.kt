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

@Composable fun SeasonChips(videos:List<Video>,selected:Video,choose:(Video)->Unit,search:Boolean=false) {
    val seasons=remember(videos,search) { if(search) VideoGroups.searchSeasons(videos) else VideoGroups.seasons(videos) }
    val seasonCounts = remember(seasons) { seasons.groupingBy { VideoGroups.identity(it.first()).season }.eachCount() }
    if(seasons.size>1) LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth().testTag("seasonPicker")) {
        items(seasons,key={VideoGroups.workKey(it.first())}) { options ->
            val video=options.first();val identity=VideoGroups.identity(video)
            ContentFilterChip(selected=VideoGroups.sameWork(video,selected),onClick={choose(if(search) video else options.firstOrNull { it.source.key==selected.source.key } ?: video)},
                label={Text(identity.label+(if(seasonCounts.getValue(identity.season)>1 && identity.year.isNotEmpty()) " · ${identity.year}" else ""))},
                modifier=Modifier.testTag("season:${identity.season}:${identity.year}"))
        }
    }
}

/** Shared source rows for search/detail and player; availability never inferred from metadata. */
@Composable fun SourceChoices(videos:List<Video>,selected:Video?,probes:Map<String,ProbeStatus>,resolutions:Map<String,String>,choose:(Video)->Unit,limit:Int=Int.MAX_VALUE,rowInset:androidx.compose.ui.unit.Dp=12.dp,verifiedOnly:Boolean=false,playbackLabel:(Video)->String?={null}) {
    val rows = videos.take(limit)
    val sourceRow: @Composable (Video) -> Unit = { video ->
        val key = video.probeKey()
        val status = probes[key]
        SourceChoiceRow(video, status, resolutions[key]?.takeIf { status==ProbeStatus.READY },
            selected?.probeKey()==key, verifiedOnly, rowInset, playbackLabel(video), choose)
    }
    if(verifiedOnly || rows.size>3) {
        // Keep the same keyed list when a fourth provider arrives during an open search.
        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max=280.dp)) {
            items(rows,key={it.probeKey()}) { video -> sourceRow(video) }
        }
    } else rows.forEach { video -> key(video.probeKey()) { sourceRow(video) } }
}

@Composable private fun SourceChoiceRow(video:Video,status:ProbeStatus?,resolution:String?,selected:Boolean,
    verifiedOnly:Boolean,rowInset:androidx.compose.ui.unit.Dp,playbackLabel:String?,choose:(Video)->Unit) {
    val enabled = !verifiedOnly || status==ProbeStatus.READY
    val qualityText = when {
        resolution!=null -> "实测 · $resolution"
        video.quality.evidence==QualityEvidence.MEASURED -> "最近播放 · ${video.quality.label}"
        video.quality.evidence!=QualityEvidence.UNKNOWN -> video.quality.label
        else -> null
    }
    val label = if(selected) "当前来源" else playbackLabel ?: video.subtitle.split(" · ")
        .map { it.trim() }.filterNot { qualityText!=null && SearchRules.qualityOnly.matches(it) }.joinToString(" · ")
    Surface(onClick={choose(video)},enabled=enabled,color=if(selected) glassFill(true) else androidx.compose.ui.graphics.Color.Transparent,
        shape=MaterialTheme.shapes.small,modifier=Modifier.fillMaxWidth().testTag("sourceChoice:${video.source.key}:${video.id}")) {
        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=rowInset,vertical=8.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(video.source.name,style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                if(verifiedOnly) {
                    // Reserve one metadata line across checking, ready and failed states.
                    val info=listOfNotNull(qualityText,label.takeIf { it.isNotEmpty() }).joinToString(" · ")
                    Text(info,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines=1,overflow=TextOverflow.Ellipsis,modifier=if(resolution!=null) Modifier.testTag("probeResolution:${video.id}") else Modifier)
                } else {
                    if(resolution!=null) Text("实测 · $resolution",style=MaterialTheme.typography.labelSmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.testTag("probeResolution:${video.id}"))
                    else if(qualityText!=null) QualityBadge(video.quality,lastPlayback=true)
                    if(label.isNotEmpty()) Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }
            MediaProbeIndicator(status,textModifier=Modifier.testTag("probe:${video.id}"))
            if(enabled) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun CollectionControls(current:Video,state:PlayerUiState,model:AppModel,choose:(Video)->Unit) {
    val options=remember(current,state.relatedVideos) { VideoGroups.options(current,state.relatedVideos) }
    var sourcesOpen by remember(current.probeKey()) { mutableStateOf(false) }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        SeasonChips(options,current,choose)
        val sources=remember(options,current) { options.filter { VideoGroups.sameWork(it,current) } }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            GlassTextButton(onClick={sourcesOpen=!sourcesOpen},modifier=Modifier.weight(1f,false).testTag("collectionSources")) { Text(if(sourcesOpen) "收起来源" else "${sources.size} 个来源",maxLines=1) }
            GlassTextButton(onClick={model.related(current,true)},enabled=!state.relatedLoading,modifier=Modifier.testTag("findSeasons")) { Text(if(state.relatedLoading) "查找中…" else "查找更多") }
        }
        if(sourcesOpen) SourceChoices(sources,current,state.probes,state.probeResolutions,choose)
        state.relatedError?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
