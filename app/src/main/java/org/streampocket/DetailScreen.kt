// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable fun DetailScreen(detail:Detail,model:AppModel) {
    val state by model.state.collectAsState()
    val progress = remember(detail.video,state.saved.history) { episodeProgress(detail.video,state.saved.history) }
    val recent = remember(detail,state.saved.history) { detailResume(detail,state.saved.history)?.playback?.episode }
    val verified = state.probeEpisodes[detail.video.probeKey()]
    val lines = remember(detail) { detail.lines.filter { it.episodes.isNotEmpty() } }
    val primary = recent ?: verified?.let { match -> lines.firstNotNullOfOrNull { l -> l.episodes.find { it.url==match.url } } }
        ?: lines.firstOrNull()?.episodes?.firstOrNull()
    var choosing by rememberSaveable(detail.video.id,detail.video.source.key) { mutableStateOf(false) }
    var expanded by rememberSaveable(detail.video.id,detail.video.source.key) { mutableStateOf(false) }
    val synopsis = remember(detail.description) { detail.description.replace("&nbsp;"," ",ignoreCase=true).replace('\u00a0',' ').trim() }

    if(choosing) HandleOnlySheet(onDismissRequest={choosing=false}) {
        var selected by rememberSaveable { mutableIntStateOf(lines.indexOfFirst { line -> line.episodes.any { it.url==primary?.url } }.coerceAtLeast(0)) }
        Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text("选集",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                GlassTextButton(onClick={choosing=false}) { Text("关闭") }
            }
            if(lines.size>1) LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                items(lines.size) { i -> ContentFilterChip(i==selected,{selected=i},modifier=Modifier.testTag("detailLine:$i"),label={Text(lines[i].name)}) }
            }
            PlaybackEpisodeGrid(lines.getOrNull(selected)?.episodes.orEmpty(),primary?.url.orEmpty(),{ episode ->
                choosing=false
                model.play(detail.video,episode)
            },progress,Modifier.weight(1f,false))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("detailGrid")) {
        val coverHeight = minOf(maxHeight*.42f,340.dp)
        Column(Modifier.fillMaxSize()) {
            Box {
                Poster(detail.video,model,Modifier.fillMaxWidth().height(coverHeight),contentScale=ContentScale.Crop,large=true)
                IconButton(onClick=model::closeDetail,modifier=Modifier.align(Alignment.TopStart).padding(8.dp),
                    colors=IconButtonDefaults.iconButtonColors(containerColor=Color.Black.copy(alpha=.5f),contentColor=Color.White)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回")
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(detail.video.title,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
                GlassButton(onClick={choosing=true},enabled=lines.isNotEmpty(),modifier=Modifier.testTag("detailEpisodes")) { Text("选集") }
                FilledIconButton(onClick={primary?.let { model.play(detail.video,it) }},enabled=primary!=null,shape=CircleShape,
                    modifier=Modifier.size(52.dp).testTag("detailPlay")) {
                    Icon(painterResource(R.drawable.ic_play),if(recent!=null) "继续观看" else "立即播放")
                }
            }
            if(synopsis.isNotEmpty()) Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal=20.dp,vertical=8.dp)) {
                var overflow by remember(synopsis) { mutableStateOf(false) }
                if(expanded) GlassTextButton(onClick={expanded=false}) { Text("收起简介") }
                Text(synopsis,modifier=Modifier.weight(1f,false).then(if(expanded) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                    maxLines=if(expanded) Int.MAX_VALUE else 3,overflow=TextOverflow.Ellipsis,
                    onTextLayout={if(!expanded) overflow=it.hasVisualOverflow},style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(!expanded && overflow) GlassTextButton(onClick={expanded=true}) { Text("展开简介") }
            }
        }
    }
}
