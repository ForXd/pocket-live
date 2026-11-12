// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

internal fun watchFraction(p:Playback):Float? = if(p.video.source.kind==SourceKind.LIVE || p.duration<=0) null
    else (p.position.toDouble()/p.duration).coerceIn(0.0,1.0).toFloat()
internal fun episodeProgress(video:Video,history:List<History>):Map<String,Playback> = history
    .filter { it.playback.video.source.key==video.source.key && it.playback.video.id==video.id }
    .sortedBy { it.updated }.associate { it.playback.episode.url to it.playback }

/** Unknown legacy durations never imply an invented percentage or a loading animation. */
@Composable internal fun WatchProgress(playback:Playback,modifier:Modifier=Modifier) {
    if(playback.video.source.kind==SourceKind.LIVE) return
    val fraction=watchFraction(playback)
    val label=if(fraction==null) "已观看 · 时长未知" else if(fraction>=1f) "已看完" else "已看 ${(fraction*100).toInt()}%"
    Column(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(progress={fraction ?: 0f},modifier=Modifier.fillMaxWidth().height(3.dp).semantics { stateDescription=label },
            color=MaterialTheme.colorScheme.primary,trackColor=MaterialTheme.colorScheme.onSurface.copy(alpha=.12f),gapSize=0.dp,drawStopIndicator={})
    }
}

/** Static cached wave: the filled area grows horizontally; no per-cell animation loop. */
@Composable internal fun EpisodeProgressButton(episode:Episode,playback:Playback?,selected:Boolean,onClick:()->Unit,
    modifier:Modifier=Modifier,showQuality:Boolean=false) {
    val fraction=playback?.let(::watchFraction)
    val label=playback?.let { if(fraction==null) "已看过" else if(fraction>=1f) "已看完" else "已看 ${(fraction*100).toInt()}%" }
    val waveColor=MaterialTheme.colorScheme.primary.copy(alpha=.12f)
    Surface(onClick=onClick,shape=MaterialTheme.shapes.small,color=glassFill(selected),contentColor=MaterialTheme.colorScheme.onSurface,
        modifier=modifier.semantics { this.selected=selected; if(label!=null) stateDescription=label }) {
        Column(Modifier.fillMaxWidth().drawWithCache {
            val fill=fraction ?: 0f
            val edge=size.width*fill
            val amplitude=minOf(6.dp.toPx(),edge,(size.width-edge).coerceAtLeast(0f))
            val wave=androidx.compose.ui.graphics.Path().apply {
                moveTo(0f,0f);lineTo(edge,0f)
                cubicTo(edge+amplitude,size.height*.18f,edge+amplitude,size.height*.32f,edge,size.height*.5f)
                cubicTo(edge-amplitude,size.height*.68f,edge-amplitude,size.height*.82f,edge,size.height)
                lineTo(0f,size.height);close()
            }
            onDrawBehind { if(fill>=1f) drawRect(waveColor) else if(fill>0f) drawPath(wave,waveColor) }
        }.padding(horizontal=8.dp,vertical=12.dp),horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(episode.name,style=MaterialTheme.typography.labelLarge,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if(label!=null) Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2)
            if(showQuality && episode.quality.evidence!=QualityEvidence.UNKNOWN) Text(episode.quality.label,style=MaterialTheme.typography.labelSmall,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}
