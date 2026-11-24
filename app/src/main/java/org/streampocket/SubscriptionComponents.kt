// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

private val Subscription.updateLabel: String get() = if (isLocalJson) "重新选择 JSON 文件" else "更新订阅"
private fun Subscription.summary(disabled: Set<String>): String {
    val origin = if (isLocalJson) "本地 JSON · " else ""
    val enabled = sources.count { it.key !in disabled }
    return "${origin}${sources.size} 个源 · 已启用 $enabled 个"
}

private val groupStartShape = ContentCardShape.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp))
private val groupEndShape = ContentCardShape.copy(topStart = CornerSize(0.dp), topEnd = CornerSize(0.dp))

private fun groupRowShape(index: Int, lastIndex: Int): Shape = when {
    lastIndex == 0 -> ContentCardShape
    index == 0 -> groupStartShape
    index == lastIndex -> groupEndShape
    else -> RectangleShape
}

@Composable private fun GroupRow(shape: Shape, showDivider: Boolean, content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            content()
            if (showDivider) ContentDivider(Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable internal fun SubscriptionList(
    state: UiState,
    listState: LazyListState,
    onOpen: (Subscription) -> Unit,
    onAdd: () -> Unit,
    onExport: () -> Unit,
    onUpdate: (Subscription) -> Unit,
    onCancel: (String) -> Unit,
    onDelete: (Subscription) -> Unit
) {
    var revealedId by remember { mutableStateOf<String?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("subscriptionsList"),
        state = listState,
        contentPadding = PaddingValues(start = PageGutter, end = PageGutter, bottom = 20.dp + LocalDockInset.current)
    ) {
        item(key = "header") {
            PageHeading("订阅", inset = 0.dp) {
                GlassIconButton(onClick = onAdd) { Icon(Icons.Default.Add, "添加订阅") }
            }
        }
        item(key = "actions") {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("我的订阅", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                GlassButton(onClick = onExport) { Text("分享片源") }
            }
        }
        if (state.saved.subscriptions.isEmpty()) item {
            EmptyPanel("还没有订阅", "添加订阅后，在首页搜索或浏览内容。", R.drawable.ic_library)
        }
        itemsIndexed(state.saved.subscriptions, key = { _, sub -> "subscription:${sub.id}" }) { index, sub ->
            val lastIndex = state.saved.subscriptions.lastIndex
            val shape = groupRowShape(index, lastIndex)
            val importing = sub.id in state.importing
            SwipeActionCard(
                "swipeSubscription:${sub.id}", revealedId == sub.id, { revealedId = if (it) sub.id else null },
                128.dp, if (importing) "显示取消和删除" else "显示更新和删除", shape = shape,
                actions = {
                    SwipeTile(onClick = { revealedId = null; if (importing) onCancel(sub.id) else onUpdate(sub) }) {
                        if (importing) Icon(Icons.Default.Close, "取消更新") else Icon(Icons.Default.Refresh, sub.updateLabel)
                    }
                    SwipeTile(onClick = { revealedId = null; onDelete(sub) }, destructive = true) {
                        Icon(Icons.Default.Delete, "删除订阅")
                    }
                }
            ) {
                GroupRow(shape, index < lastIndex) {
                    PreferenceRow(sub.name, if (importing) "正在更新订阅…" else sub.summary(state.saved.disabled),
                        { onOpen(sub) }, Modifier.testTag("subscription:${sub.id}")) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable internal fun SubscriptionDetail(
    sub: Subscription,
    state: UiState,
    listState: LazyListState,
    onBack: () -> Unit,
    onUpdate: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Source) -> Unit
) {
    val importing = sub.id in state.importing
    val sources = remember(sub.sources, state.sourceAvailability) {
        sub.sources.sortedBy { when (state.sourceAvailability[it.key]) { true -> 0; null -> 1; false -> 2 } }
    }
    var showActions by remember(sub.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        BackHeading(sub.name, "返回订阅", onBack) {
            Box {
                GlassIconButton(onClick = { showActions = true }) { Icon(Icons.Default.MoreVert, "订阅选项") }
                GlassMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                    DropdownMenuItem(text = { Text(if (importing) "取消更新" else sub.updateLabel) },
                        onClick = { showActions = false; if (importing) onCancel() else onUpdate() })
                    DropdownMenuItem(text = { Text("删除订阅", color = MaterialTheme.colorScheme.error) },
                        onClick = { showActions = false; onDelete() })
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("subscriptionSources"),
            state = listState,
            contentPadding = PaddingValues(start = PageGutter, end = PageGutter, bottom = 20.dp + LocalDockInset.current)
        ) {
            item(key = "summary") {
                Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(sub.summary(state.saved.disabled), style = MaterialTheme.typography.titleMedium)
                    Text(if (importing) "正在更新订阅…" else "更新于 ${historyTime(sub.updated)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(sources, key = { _, source -> source.key }) { index, source ->
                GroupRow(groupRowShape(index, sources.lastIndex), index < sources.lastIndex) {
                    SubscriptionSourceRow(source, source.key !in state.saved.disabled,
                        { onToggle(source) }, state.sourceAvailability[source.key])
                }
            }
        }
    }
}

@Composable private fun SubscriptionSourceRow(source: Source, enabled: Boolean, onToggle: () -> Unit, availability: Boolean?) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp)
        .toggleable(value = enabled, role = Role.Switch, onValueChange = { onToggle() })
        .semantics { contentDescription = "启用或停用 ${source.name}" }
        .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(source.name, style = MaterialTheme.typography.titleMedium)
            Text(if (source.kind == SourceKind.LIVE) "直播 M3U" else "点播 API",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text(when (availability) { true -> "● 最近搜索成功"; false -> "● 最近搜索失败"; null -> "暂无搜索记录" },
                style = MaterialTheme.typography.labelMedium,
                color = when (availability) {
                    true -> Color(if (colors.background.luminance() < 0.5f) 0xff79d69b else 0xff26864b)
                    false -> colors.error
                    null -> colors.onSurfaceVariant
                })
        }
        PocketSwitch(enabled)
    }
}
