// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Close
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
    val state by model.state.collectAsStateWithLifecycle()
    val progress = remember(detail.video,state.saved.history) { episodeProgress(detail.video,state.saved.history) }
    val recent = remember(detail,state.saved.history) { detailResume(detail,state.saved.history)?.playback }
    val verified = state.probeEpisodes[detail.video.probeKey()]
    val lines = remember(detail) { detail.lines.filter { it.episodes.isNotEmpty() } }
    val primary = recent?.episode ?: verified?.let { match -> lines.firstNotNullOfOrNull { l -> l.episodes.find { it.url==match.url } } }
        ?: lines.firstOrNull { it.name == state.probeLines[detail.video.probeKey()] }?.episodes?.firstOrNull()
        ?: lines.firstOrNull()?.episodes?.firstOrNull()
    var choosing by rememberSaveable(detail.video.id,detail.video.source.key) { mutableStateOf(false) }
    var expanded by rememberSaveable(detail.video.id,detail.video.source.key) { mutableStateOf(false) }
    val synopsis = remember(detail.description) { detail.description.replace("&nbsp;"," ",ignoreCase=true).replace('\u00a0',' ').trim() }

    if(choosing) HandleOnlySheet(onDismissRequest={choosing=false}) {
        var selected by rememberSaveable { mutableIntStateOf(lines.indexOfFirst { line -> line.episodes.any { it.url==primary?.url } }.coerceAtLeast(0)) }
        Column(Modifier.fillMaxWidth().padding(horizontal=PageGutter,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            SheetHeading("选集") { choosing=false }
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
        val coverHeight = detailCoverHeight(maxHeight)
        Column(Modifier.fillMaxSize()) {
            val motion=LocalCardReturn.current
            TitleCover(detail.video,model,coverHeight,{motion.commit(model::closeDetail)})
            Row(Modifier.fillMaxWidth().padding(horizontal=PageGutter,vertical=6.dp).smokeGlass(MaterialTheme.shapes.large).padding(horizontal=10.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                val fraction = recent?.takeIf { it.duration > 0 }?.let { (it.position.toFloat()/it.duration).coerceIn(0f,1f) }
                Box(Modifier.size(56.dp),contentAlignment=Alignment.Center) {
                    if(fraction!=null) CircularProgressIndicator(progress={fraction},modifier=Modifier.fillMaxSize().testTag("detailProgress"),
                        strokeWidth=3.dp,color=MaterialTheme.colorScheme.tertiary,trackColor=MaterialTheme.colorScheme.surfaceVariant)
                    FilledIconButton(onClick={primary?.let { model.play(detail.video,it) }},enabled=primary!=null,shape=CircleShape,
                        modifier=Modifier.size(48.dp).testTag("detailPlay")) {
                        Icon(painterResource(R.drawable.ic_play),if(recent!=null) "继续观看" else "立即播放")
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(if(recent!=null && primary!=null) "继续${primary.name}" else primary?.name ?: "暂无可播放剧集",style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                    val quality = primary?.quality ?: VideoQuality()
                    val resolution = when {
                        quality.evidence==QualityEvidence.MEASURED -> quality.label
                        primary!=null && verified?.url==primary.url && state.probeResolutions[detail.video.probeKey()]!=null -> state.probeResolutions.getValue(detail.video.probeKey())
                        quality.evidence!=QualityEvidence.UNKNOWN -> quality.label
                        else -> detail.video.quality.takeUnless { it.evidence==QualityEvidence.MEASURED }?.label ?: "清晰度未知"
                    }
                    Text("${detail.video.source.name} · $resolution",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                GlassButton(onClick={choosing=true},enabled=lines.isNotEmpty(),modifier=Modifier.testTag("detailEpisodes")) { Text("选集") }
            }

            if(synopsis.isNotEmpty()) Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal=PageGutter,vertical=8.dp)) {
                var overflow by remember(synopsis) { mutableStateOf(false) }
                Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                    Text("剧情简介",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                    if(expanded || overflow) GlassTextButton(onClick={expanded=!expanded}) { Text(if(expanded) "收起简介" else "展开简介") }
                }
                Text(synopsis,modifier=Modifier.weight(1f,false).then(if(expanded) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                    maxLines=if(expanded) Int.MAX_VALUE else 3,overflow=TextOverflow.Ellipsis,
                    onTextLayout={if(!expanded) overflow=it.hasVisualOverflow},style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)

            }
        }
    }
}

private fun detailCoverHeight(height: androidx.compose.ui.unit.Dp) = minOf(height*.42f,340.dp)

@Composable internal fun PendingDetail(video: Video, model: AppModel, back: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = detailCoverHeight(maxHeight)
        Column {
            TitleCover(video,model,height,back)
            Box(Modifier.fillMaxSize().testTag("detailLoading"),contentAlignment=Alignment.Center) {
                Column(Modifier.padding(24.dp).widthIn(max=320.dp),horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    PocketLoading()
                    Text("正在加载剧集…",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    GlassTextButton(onClick=back) { Text("取消详情请求") }
                }
            }
        }
    }
}

@Composable internal fun TitleCover(video:Video,model:AppModel,height:androidx.compose.ui.unit.Dp,back:()->Unit,label:String="返回") {
    Box(Modifier.posterDestination()) {
        Poster(video,model,Modifier.fillMaxWidth().height(height),contentScale=ContentScale.Crop,large=true)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Transparent,Color.Black.copy(alpha=.82f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
            RecommendationRating(video)
            Text(video.title,style=MaterialTheme.typography.headlineLarge,color=Color.White,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
        val close=LocalPosterClose.current
        if(close!=null) GlassIconButton(onClick=close,modifier=Modifier.align(Alignment.TopEnd).padding(8.dp),
            colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=Color.Black.copy(alpha=.52f),contentColor=Color.White)) {
            Icon(androidx.compose.material.icons.Icons.Default.Close,"关闭详情")
        } else PocketBack(back, Modifier.align(Alignment.TopStart).padding(start=PageGutter,top=6.dp), label = label,overVideo=true)
    }
}
