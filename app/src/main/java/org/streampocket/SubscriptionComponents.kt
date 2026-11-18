// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable internal fun SubscriptionHeader(sub:Subscription,expanded:Boolean,enabledCount:Int,importing:Boolean,revealed:Boolean,onReveal:(Boolean)->Unit,onExpand:()->Unit,onUpdate:()->Unit,onCancel:()->Unit,onDelete:()->Unit) {
    val updateLabel = if(sub.isLocalJson) "重新选择 JSON 文件" else "更新订阅"
    SwipeActionCard("swipeSubscription:${sub.id}",revealed,onReveal,128.dp,"显示更新和删除",actions={
        SwipeTile(onClick={onReveal(false);onUpdate()},enabled=!importing) { Icon(Icons.Default.Refresh,updateLabel) }
        SwipeTile(onClick={onReveal(false);onDelete()},destructive=true) { Icon(Icons.Default.Delete,"删除订阅") }
    }) {
    ContentCard(Modifier.fillMaxWidth(),containerColor=MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(4.dp),verticalAlignment=Alignment.CenterVertically) {
            GlassTextButton(onClick=onExpand,modifier=Modifier.weight(1f).heightIn(min=80.dp).testTag("subscription:${sub.id}").semantics { stateDescription=if(expanded) "已展开" else "已收起" },shape=MaterialTheme.shapes.medium,contentPadding=PaddingValues(12.dp)) {
                Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow) { Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_library),null,Modifier.padding(10.dp).size(22.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(sub.name,style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurface,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text("${if(sub.isLocalJson) "本地 JSON · " else ""}${sub.sources.size} 个源 · 已启用 $enabledCount 个",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if(expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,if(expanded) "收起来源" else "展开来源",Modifier.size(20.dp))
            }
        }
        ContentDivider()
        if(expanded || importing) {
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(if(importing) "正在更新订阅…" else "更新于 ${historyTime(sub.updated)}",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(importing) GlassTextButton(onClick=onCancel) { Text("取消") }
            }
        }
    }
}

}

@Composable internal fun SubscriptionSourceRow(source:Source,enabled:Boolean,onToggle:()->Unit,onRules:(()->Unit)?=null,availability:Boolean?=null) {
    var menuOpen by remember(source.key) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().padding(start=16.dp),shape=ContentCardShape,color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(ActionGap)) {
            Row(Modifier.weight(1f).toggleable(value=enabled,role=Role.Switch,onValueChange={onToggle()}).semantics { contentDescription="启用或停用 ${source.name}" }.padding(8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(source.name,style=MaterialTheme.typography.bodyLarge)
                    Text(when(availability) { true->"● 最近搜索成功";false->"● 最近搜索失败";null->"● 待验证" },
                        style=MaterialTheme.typography.labelMedium,color=when(availability) { true->androidx.compose.ui.graphics.Color(if(MaterialTheme.colorScheme.background.luminance()<0.5f) 0xff79d69b else 0xff26864b);false->MaterialTheme.colorScheme.error;null->androidx.compose.ui.graphics.Color(if(MaterialTheme.colorScheme.background.luminance()<0.5f) 0xffe4bf60 else 0xff966a00) })
                    Text(if(source.kind==SourceKind.LIVE) "直播 M3U" else "点播 API",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PocketSwitch(enabled)
            }
            if(source.kind==SourceKind.VOD && onRules!=null) Box {
                GlassIconButton(onClick={menuOpen=true},modifier=Modifier.size(48.dp)) { Icon(Icons.Default.MoreVert,"来源选项 ${source.name}") }
                GlassMenu(expanded=menuOpen,onDismissRequest={menuOpen=false}) {
                    DropdownMenuItem(text={Text("广告精确路径规则")},onClick={menuOpen=false;onRules()})
                }
            }
        }
    }
}
