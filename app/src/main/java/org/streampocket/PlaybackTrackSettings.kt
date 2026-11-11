// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride

/** Replaces Media3's opaque popup while retaining speed, audio and subtitle selection. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
@Composable internal fun PlaybackTrackSettings(player:Player?) {
    if(player==null) return
    var revision by remember(player) { mutableIntStateOf(0) }
    DisposableEffect(player) {
        val listener=object:Player.Listener { override fun onEvents(player:Player,events:Player.Events) { revision++ } }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val speed=remember(player,revision) { player.playbackParameters.speed }
    Text("播放速度",style=MaterialTheme.typography.titleSmall)
    FlowRow(maxItemsInEachRow=3,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf(.5f,.75f,1f,1.25f,1.5f,2f).forEach { value ->
            ContentFilterChip(selected=speed==value,onClick={player.setPlaybackSpeed(value)},modifier=Modifier.weight(1f).testTag("speed:$value")) { Text("${value}×") }
        }
    }
    val tracks=remember(player,revision) { player.currentTracks.groups }
    listOf(C.TRACK_TYPE_AUDIO to "音轨",C.TRACK_TYPE_TEXT to "字幕").forEach { (type,title) ->
        val groups=tracks.filter { it.type==type }
        if(groups.isNotEmpty()) {
            Text(title,style=MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                val params=player.trackSelectionParameters
                ContentFilterChip(selected=if(type==C.TRACK_TYPE_TEXT) type in params.disabledTrackTypes else groups.none { params.overrides.containsKey(it.mediaTrackGroup) },onClick={
                    player.trackSelectionParameters=params.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type,type==C.TRACK_TYPE_TEXT).build()
                }) { Text(if(type==C.TRACK_TYPE_TEXT) "关闭" else "自动") }
                groups.forEachIndexed { groupIndex,group ->
                    for(index in 0 until group.length) if(group.isTrackSupported(index)) {
                        val format=group.getTrackFormat(index)
                        ContentFilterChip(selected=group.isTrackSelected(index) && params.overrides.containsKey(group.mediaTrackGroup),onClick={
                            player.trackSelectionParameters=player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type,false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup,index)).build()
                        }) { Text(format.label?.takeIf { it.isNotBlank() } ?: format.language?.takeIf { it.isNotBlank() && it!="und" } ?: "$title ${groupIndex+1}-${index+1}") }
                    }
                }
            }
        }
    }
}
