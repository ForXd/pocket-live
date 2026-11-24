// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The same source list adapts to the inline bottom sheet and the landscape side panel. */
@Composable internal fun PlaybackSourcesSheet(
    playback: Playback, alternatives: List<PlaybackAlternative>, loading: Boolean, error: String?,
    fullscreen: Boolean, keepPosition: Boolean, changeKeepPosition: (Boolean) -> Unit,
    select: (PlaybackAlternative) -> Unit, retry: () -> Unit, close: () -> Unit
) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.tertiary
    FrostedDialog(close, placement = if (fullscreen) GlassPlacement.SIDE else GlassPlacement.BOTTOM,
        immersive = fullscreen, tag = "alternativePanel", sideFraction = 305f / 720f) {
        Column(Modifier.fillMaxWidth().then(if (fullscreen) Modifier.fillMaxHeight() else Modifier)
            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
            if (fullscreen) Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
                Spacer(Modifier.size(36.dp, 4.dp).background(secondary.copy(alpha = .6f), CircleShape))
            }
            SheetHeading("切换同集来源", close)
            Text("${playback.video.title} · ${playback.episode.name}", Modifier.padding(bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            LazyColumn(Modifier.weight(1f, fill = fullscreen).testTag("playbackAlternatives")) {
                item {
                    Row(Modifier.fillMaxWidth().toggleable(value = keepPosition, role = Role.Switch, onValueChange = changeKeepPosition)
                        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("保留当前进度", style = MaterialTheme.typography.titleSmall)
                            Text("${android.text.format.DateUtils.formatElapsedTime(playback.position.coerceAtLeast(0) / 1000)} · 不同来源可能有偏差",
                                style = MaterialTheme.typography.bodySmall, color = secondary)
                        }
                        PocketSwitch(keepPosition)
                    }
                    SourceDivider()
                }
                if (loading) item { LoadingStatus("正在自动试播同集来源…", Modifier.padding(vertical = 8.dp)) }
                items(alternatives, key = { it.episode.url }) { option ->
                    val sameSource = option.video.source.key == playback.video.source.key
                    val title = if (sameSource) option.line.ifBlank { option.video.source.name } else option.video.source.name
                    val detail = listOfNotNull(option.line.takeIf { !sameSource && it.isNotBlank() }, option.episode.name,
                        option.resolution ?: option.episode.quality.takeUnless { it.evidence == QualityEvidence.UNKNOWN }?.label).joinToString(" · ")
                    val selectable = option.probeStatus == ProbeStatus.READY
                    SourceRow(title, detail, Modifier.testTag("alternative:${option.episode.url}").clickable(enabled = selectable, role = Role.Button) { select(option) }) {
                        if(option.probeStatus!=ProbeStatus.UNCHECKED) Text(option.probeStatus.label,
                            style = MaterialTheme.typography.labelSmall, color = if (option.probeStatus == ProbeStatus.READY) accent else secondary)
                        if(selectable) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp), tint = secondary)
                    }
                }
                item {
                    SourceRow(playback.video.source.name, "当前来源", Modifier.testTag("playingSource")) {
                        Text("✓", color = accent, style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (error != null || (!loading && alternatives.isEmpty())) item {
                    Text(error ?: "未找到同名同集的备用地址。可添加其他订阅来源后再试。", Modifier.padding(vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = secondary)
                }
                if (!loading) item {
                    GlassButton(onClick = retry, modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium) {
                        Text("重新查找")
                    }
                }
            }
        }
    }
}

@Composable private fun SourceRow(title: String, detail: String, modifier: Modifier, trailing: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
    SourceDivider()
}

@Composable private fun SourceDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .075f))
}
