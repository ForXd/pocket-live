// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal fun fullscreenPlaybackTitle(title: String, episodeName: String, isLive: Boolean): String {
    val name = episodeName.trim()
    if (isLive || name.isEmpty() || name == title) return title
    val label = if (name.all { it in '0'..'9' }) {
        val number = name.trimStart('0').ifEmpty { "0" }
        "第${number}集"
    } else name
    return if (title.isBlank()) label else "$label · $title"
}

@Composable
internal fun FullscreenHeader(
    title: String,
    singleRow: Boolean,
    onExit: () -> Unit,
    onOptions: () -> Unit,
    modifier: Modifier = Modifier,
    episodeActions: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = PageGutter - IconFaceInset, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ActionGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PocketBack(onExit, Modifier.testTag("fullscreenToggle"), label = "退出全屏", overVideo = true)
            Text(
                title, Modifier.weight(1f).testTag("fullscreenTitle"),
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            FullscreenStatus(compact = true)
            if (singleRow) episodeActions()
            GlassIconButton(
                onClick = onOptions,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = Color.Transparent, contentColor = Color.White,
                ),
            ) { Icon(Icons.Default.MoreVert, "更多播放选项") }
        }
        if (!singleRow) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { episodeActions() }
    }
}

@Composable
internal fun FullscreenEpisodeActions(
    compact: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    hasEpisodes: Boolean,
    showSources: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onEpisodes: () -> Unit,
    onSources: () -> Unit,
) {
    val colors = ButtonDefaults.textButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = .55f))
    Row(horizontalArrangement = Arrangement.spacedBy(ActionGap), verticalAlignment = Alignment.CenterVertically) {
        FullscreenStep(compact, R.drawable.ic_previous, "上一集", hasPrevious, colors, onPrevious)
        FullscreenStep(compact, R.drawable.ic_next, "下一集", hasNext, colors, onNext)
        TextButton(onClick = onEpisodes, enabled = hasEpisodes, colors = colors) { Text("选集") }
        if (showSources) TextButton(onClick = onSources, colors = colors) { Text("换源") }
    }
}

@Composable
private fun FullscreenStep(
    compact: Boolean, icon: Int, label: String, enabled: Boolean, colors: ButtonColors, onClick: () -> Unit,
) {
    if (compact) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(IconTarget)) {
            Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(24.dp))
        }
    } else {
        TextButton(onClick = onClick, enabled = enabled, colors = colors, modifier = Modifier.semantics { contentDescription = label }) {
            Text(label)
        }
    }
}
