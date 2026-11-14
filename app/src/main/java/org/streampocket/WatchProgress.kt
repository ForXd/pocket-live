// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.foundation.BorderStroke
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

/** Keep watch progress separate from selection and the episode title. */
@Composable internal fun EpisodeProgressButton(episode:Episode,playback:Playback?,selected:Boolean,onClick:()->Unit,
    modifier:Modifier=Modifier,showQuality:Boolean=false) {
    val fraction=playback?.let(::watchFraction)
    val label=playback?.let { if(fraction==null) "已看过" else if(fraction>=1f) "已看完" else "已看 ${(fraction*100).toInt()}%" }
    Surface(onClick=onClick,shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow,
        border=BorderStroke(1.dp, if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha=.45f)),
        contentColor=MaterialTheme.colorScheme.onSurface,
        modifier=modifier.semantics { this.selected=selected; if(label!=null) stateDescription=label }) {
        Column(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=10.dp),
            verticalArrangement=Arrangement.spacedBy(4.dp,androidx.compose.ui.Alignment.CenterVertically)) {
            Text(episode.name,style=MaterialTheme.typography.labelLarge,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if(label!=null) Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.8f),maxLines=2)
            if(fraction!=null) WatchProgress(playback)
            if(showQuality && episode.quality.evidence!=QualityEvidence.UNKNOWN) Text(episode.quality.label,style=MaterialTheme.typography.labelSmall,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}
