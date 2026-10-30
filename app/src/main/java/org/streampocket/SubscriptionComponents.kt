// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable internal fun SubscriptionHeader(sub:Subscription,expanded:Boolean,enabledCount:Int,importing:Boolean,onExpand:()->Unit,onUpdate:()->Unit,onCancel:()->Unit,onDelete:()->Unit) {
    var menuOpen by remember(sub.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=if(expanded) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(4.dp),verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick=onExpand,modifier=Modifier.weight(1f).heightIn(min=80.dp).testTag("subscription:${sub.id}").semantics { stateDescription=if(expanded) "已展开" else "已收起" },shape=MaterialTheme.shapes.medium,contentPadding=PaddingValues(12.dp)) {
                Icon(if(expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,if(expanded) "收起来源" else "展开来源",Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(sub.name,style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurface,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text("${if(sub.isLocalJson) "本地 JSON · " else ""}${sub.sources.size} 个源 · 已启用 $enabledCount 个",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box {
                IconButton(onClick={menuOpen=true},modifier=Modifier.size(48.dp)) { Icon(Icons.Default.MoreVert,"管理订阅 ${sub.name}") }
                DropdownMenu(expanded=menuOpen,onDismissRequest={menuOpen=false}) {
                    DropdownMenuItem(text={Text(if(sub.isLocalJson) "重新选择 JSON 文件" else "更新订阅")},enabled=!importing,onClick={menuOpen=false;onUpdate()})
                    DropdownMenuItem(text={Text("删除订阅",color=MaterialTheme.colorScheme.error)},onClick={menuOpen=false;onDelete()})
                }
            }
        }
        if(expanded || importing) {
            HorizontalDivider(Modifier.padding(horizontal=16.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(if(importing) "正在更新订阅…" else "更新于 ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(sub.updated))}",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(importing) TextButton(onClick=onCancel) { Text("取消") }
            }
        }
    }
}

@Composable internal fun SubscriptionSourceRow(source:Source,enabled:Boolean,onToggle:()->Unit,onRules:()->Unit) {
    var menuOpen by remember(source.key) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().padding(start=16.dp),shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
            Row(Modifier.weight(1f).toggleable(value=enabled,role=Role.Switch,onValueChange={onToggle()}).semantics { contentDescription="启用或停用 ${source.name}" }.padding(8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(source.name,style=MaterialTheme.typography.bodyLarge)
                    Text(if(source.kind==SourceKind.LIVE) "直播 M3U" else "点播 API",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked=enabled,onCheckedChange=null)
            }
            if(source.kind==SourceKind.VOD) Box {
                IconButton(onClick={menuOpen=true},modifier=Modifier.size(48.dp)) { Icon(Icons.Default.MoreVert,"来源选项 ${source.name}") }
                DropdownMenu(expanded=menuOpen,onDismissRequest={menuOpen=false}) {
                    DropdownMenuItem(text={Text("广告精确路径规则")},onClick={menuOpen=false;onRules()})
                }
            }
        }
    }
}
