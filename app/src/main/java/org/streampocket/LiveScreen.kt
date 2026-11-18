// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable internal fun LiveScreen(s:UiState,model:AppModel,query:String,subscriptions:()->Unit) {
    val sources=remember(s.saved.subscriptions,s.saved.disabled) { s.saved.enabledSources(SourceKind.LIVE) }
    var picker by remember { mutableStateOf(false) }
    var group by rememberSaveable(s.liveSourceKey) { mutableStateOf("") }
    val list=rememberLazyListState()
    val groups=remember(s.liveChannels) { s.liveChannels.map { it.typeName }.filter { it.isNotBlank() }.distinct() }
    val channels=remember(s.liveChannels,query,group) { s.liveChannels.filter { (group.isEmpty() || it.typeName==group) && (query.isBlank() || it.title.contains(query,true) || it.typeName.contains(query,true)) } }
    LaunchedEffect(sources.map { it.key }) { model.loadLive() }
    DisposableEffect(model) { onDispose { model.cancelLive() } }
    ScrollToTopOnChange(s.liveSourceKey.orEmpty(),query,group) { list.scrollToItem(0) }
    if(picker) HandleOnlySheet(onDismissRequest={picker=false}) {
        SheetSystemBars()
        LazyColumn(contentPadding=PaddingValues(start=PageGutter,end=PageGutter,top=20.dp,bottom=20.dp)) {
            item { Row(verticalAlignment=Alignment.CenterVertically) { Text("直播来源",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);PocketClose { picker=false } } }
            items(sources,key={it.key}) { source -> ListItem(colors=ListItemDefaults.colors(containerColor=androidx.compose.ui.graphics.Color.Transparent),headlineContent={Text(source.name)},leadingContent={RadioButton(source.key==s.liveSourceKey,onClick=null)},modifier=Modifier.clickable { model.loadLive(source.key);picker=false }.testTag("liveSource:${source.key}")) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        if(sources.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal=PageGutter,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                FilterSelector(sources.find { it.key==s.liveSourceKey }?.name ?: "选择直播来源",
                    {picker=true},Modifier.testTag("liveSourcePicker"))
            }
            GlassIconButton(onClick={model.loadLive(refresh=true)},enabled=sources.isNotEmpty() && !s.liveLoading,modifier=Modifier.testTag("liveRefresh")) { Icon(Icons.Default.Refresh,"刷新频道") }
        }
        if(groups.isNotEmpty()) Text("频道分组",Modifier.padding(horizontal=PageGutter,vertical=8.dp),style=MaterialTheme.typography.titleLarge)
        if(groups.isNotEmpty()) LazyRow(contentPadding=PaddingValues(horizontal=PageGutter),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            item { ContentFilterChip(group.isEmpty(),{group=""},label={Text("全部")}) }
            items(groups) { item -> ContentFilterChip(group==item,{group=item},modifier=Modifier.testTag("liveGroup:$item"),label={Text(item)}) }
        }
        if(s.liveLoading) PocketLoading(Modifier.fillMaxWidth().testTag("liveLoading"))
        LazyColumn(state=list,modifier=Modifier.fillMaxSize().testTag("liveChannels"),contentPadding=PaddingValues(start=PageGutter,end=PageGutter,top=20.dp,bottom=20.dp+LocalDockInset.current),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(sources.isEmpty()) item { EmptyPanel("还没有直播来源","添加或启用 IPTV / M3U 订阅后即可观看。",R.drawable.ic_library);GlassTextButton(onClick=subscriptions,modifier=Modifier.fillMaxWidth()) { Text("管理订阅") } }
            s.liveError?.let { error -> item { Text(error,color=MaterialTheme.colorScheme.error);GlassTextButton(onClick={model.loadLive(refresh=true)}) { Text("重试") } } }
            if(sources.isNotEmpty() && !s.liveLoading && s.liveError==null && channels.isEmpty()) item { Text(if(query.isNotBlank() || group.isNotEmpty()) "没有匹配频道" else "此来源没有可用频道",color=MaterialTheme.colorScheme.onSurfaceVariant) }
            if(channels.isNotEmpty()) item { Row(verticalAlignment=Alignment.CenterVertically) { Text("频道",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);Text("${channels.size} 个",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
            items(channels,key={it.source.key+":"+it.id}) { channel -> ContentCard(onClick={model.playLive(channel)},containerColor=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth().testTag("liveChannel:${channel.title}")) {
                Row(Modifier.padding(vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
                    Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow) { Icon(painterResource(R.drawable.ic_live),null,Modifier.padding(10.dp).size(22.dp)) }
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(channel.title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text(channel.typeName.ifBlank { channel.source.name },style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("直播",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.tertiary)
                }
                ContentDivider()
            } }
        }
    }
}
