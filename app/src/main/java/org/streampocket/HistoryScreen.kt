// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class,ExperimentalMaterial3Api::class)
@Composable fun HistoryScreen(history:List<History>,resume:(History)->Unit,remove:(HistoryGroupKey)->Unit,clear:()->Unit,model:AppModel?=null,searchState:MutableState<Boolean> = rememberSaveable { mutableStateOf(false) }) {
    // This screen is not composed during playback. At most 100 records are grouped
    // once per changed snapshot, never on the player's position sampling tick.
    val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val imeVisible=WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)>0
    var searchOpen by searchState
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    androidx.activity.compose.BackHandler(enabled=searchOpen && !filtersOpen && !imeVisible) { searchOpen=false;query="" }
    var period by rememberSaveable { mutableStateOf(HistoryPeriod.ALL) }
    var kind by rememberSaveable { mutableStateOf(HistoryKind.ALL) }
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceMenu by remember { mutableStateOf(false) }
    val now=remember(history) { System.currentTimeMillis() }
    val filtered=remember(history,query,source,period,kind,now) { queryHistory(history,query,source,period,kind,now) }
    val groups=remember(filtered) { HistoryGroups.group(filtered) }
    val sources=remember(history) { history.map { it.playback.video.source }.distinctBy { it.key } }

    var revealed by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRemoval by remember { mutableStateOf<HistoryGroup?>(null) }
    var clearing by remember { mutableStateOf(false) }
    val searchScroll=rememberSaveable(saver=HomeSearchScroll.Saver) { HomeSearchScroll() }
    LaunchedEffect(imeVisible) { if(imeVisible) searchScroll.reveal() }
    val scrollConnection=rememberSearchScrollConnection(searchScroll,!imeVisible && groups.isNotEmpty())
    Column(Modifier.fillMaxSize().nestedScroll(scrollConnection)) {
    Column(Modifier.fillMaxWidth()) {
        if(!searchOpen) PageHeading("历史") {
            GlassIconButton(onClick={ searchScroll.reveal();searchOpen=true }) { Icon(painterResource(R.drawable.ic_search),"搜索历史") }
            GlassIconButton(onClick={filtersOpen=true}) { Icon(painterResource(R.drawable.ic_sort),"筛选历史") }
        }
        if(searchOpen) CollapsingHomeSearch(searchScroll,statusBars=false) {
        SearchHeader {
        ContentSearchField(query,{query=it.take(100)},"搜索片名、来源或集数",onSearch={keyboard?.hide()},modifier=Modifier.fillMaxWidth().padding(vertical=4.dp).testTag("historySearch")) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(query.isNotEmpty()) IconButton(onClick={query=""},modifier=Modifier.size(48.dp)) { Icon(Icons.Default.Close,"清空历史关键词") }
                IconButton(onClick={keyboard?.hide()},modifier=Modifier.size(48.dp)) { Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowForward,"搜索观看历史") }
            }
        }
        }
        }
        if(searchOpen) Row(Modifier.fillMaxWidth().padding(horizontal=PageGutter),horizontalArrangement=Arrangement.End) {
            GlassTextButton(onClick={filtersOpen=true}) { Text("筛选历史") }
        }
        if(filtersOpen) HandleOnlySheet(onDismissRequest={filtersOpen=false}) {
        SheetSystemBars()
        Column(Modifier.padding(horizontal=PageGutter,vertical=16.dp)) {
        SheetHeading("筛选历史") { filtersOpen=false }

        LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            items(HistoryPeriod.entries) { option -> ContentFilterChip(selected=period==option,onClick={period=option},label={Text(option.label)},modifier=Modifier.testTag("historyPeriod:${option.name}")) }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Box {
                FilterSelector(sources.find { it.key==source }?.name ?: "全部来源",{sourceMenu=true},Modifier.widthIn(max=180.dp).testTag("historySource"))
                GlassMenu(expanded=sourceMenu,onDismissRequest={sourceMenu=false}) {
                    DropdownMenuItem(text={Text("全部来源")},onClick={source=null;sourceMenu=false})
                    sources.forEach { option -> DropdownMenuItem(text={Text(option.name)},onClick={source=option.key;sourceMenu=false}) }
                }
            }
            LazyRow(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(8.dp)) { items(HistoryKind.entries) { option -> ContentFilterChip(selected=kind==option,onClick={kind=option},label={Text(option.label)}) } }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Text("${groups.size} 部 · ${filtered.size} 条记录",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(history.isNotEmpty()) GlassTextButton(onClick={clearing=true},colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) { Text("清空历史") }
            if(query.isNotEmpty() || source!=null || period!=HistoryPeriod.ALL || kind!=HistoryKind.ALL) GlassTextButton(onClick={query="";source=null;period=HistoryPeriod.ALL;kind=HistoryKind.ALL}) { Text("重置") }
        }
        GlassButton(shape=MaterialTheme.shapes.medium,onClick={filtersOpen=false},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("完成") }
        }
    }
    }
    val datedGroups=remember(groups,now) {
        val zone=java.time.ZoneId.systemDefault()
        val today=java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        groups.groupBy { group ->
            when(java.time.Instant.ofEpochMilli(group.latest.updated).atZone(zone).toLocalDate()) {
                today -> "今天"
                today.minusDays(1) -> "昨天"
                else -> "更早"
            }
        }
    }
    LazyColumn(contentPadding=PaddingValues(start=PageGutter,end=PageGutter,top=20.dp,bottom=20.dp+LocalDockInset.current),verticalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.weight(1f).testTag("historyGroups")) {
        if(groups.isEmpty()) item { EmptyPanel(if(history.isEmpty()) "还没有观看记录" else "没有匹配记录",if(history.isEmpty()) "播放影片后，可在这里继续观看。" else "试试其他关键词或重置筛选。",R.drawable.ic_history) }
        datedGroups.forEach { (day,entries) ->
        item(key="day:$day") { Text(day,style=MaterialTheme.typography.titleLarge) }
        items(entries,key={it.key.stableKey}) { group ->
            val shown = group.latest
            val id = group.key.stableKey
                SwipeHistoryCard(id,revealed==id,{revealed=if(it) id else null},{pendingRemoval=group}) {
                Column(Modifier.fillMaxWidth().clip(ContentCardShape).background(MaterialTheme.colorScheme.surfaceContainerLow).clickable { if(revealed==id) revealed=null else resume(shown) }.testTag("historyGroup:$id").padding(MediaCardInset)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        if(model!=null) Poster(shown.playback.video,model,Modifier.size(68.dp,88.dp),shape=MaterialTheme.shapes.small,contentScale=ContentScale.Crop)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                            Text(VideoGroups.familyTitle(shown.playback.video),style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            val season=VideoGroups.identity(shown.playback.video).season
                            Text(listOfNotNull(season?.let { "第 $it 季" },shown.playback.episode.name,shown.playback.video.source.name).joinToString(" · "),
                                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(HistoryGroups.progress(shown),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            WatchProgress(shown.playback)
                        }
                        GlassIconButton(onClick={resume(shown)},modifier=Modifier.align(Alignment.CenterVertically).testTag("continue:$id")) {
                            Icon(painterResource(R.drawable.ic_play),"继续观看")
                        }
                    }
                }
            }
        }
    }
    }
    }
    pendingRemoval?.let { group ->
        GlassAlertDialog(onDismissRequest={pendingRemoval=null},title={Text("删除此剧的观看记录？")},
            text={Text("将删除《${VideoGroups.familyTitle(group.latest.playback.video)}》的观看断点。")},
            confirmButton={GlassTextButton(onClick={remove(group.key);pendingRemoval=null;revealed=null}) { Text("删除") }},
            dismissButton={GlassTextButton(onClick={pendingRemoval=null}) { Text("取消") }})
    }
    if(clearing) GlassAlertDialog(onDismissRequest={clearing=false},title={Text("清空观看历史？")},text={Text("所有剧目的观看断点将被删除，订阅仍会保留。")},confirmButton={GlassTextButton(onClick={clear();clearing=false}) { Text("清空") }},dismissButton={GlassTextButton(onClick={clearing=false}) { Text("取消") }})
}

/** A swipe only reveals the action; deleting always requires an explicit tap. */
@Composable private fun SwipeHistoryCard(id:String,revealed:Boolean,onReveal:(Boolean)->Unit,onDelete:()->Unit,content:@Composable ()->Unit) {
    val haptic=androidx.compose.ui.platform.LocalHapticFeedback.current
    SwipeActionCard("swipeHistory:$id",revealed,onReveal,64.dp,"显示删除按钮",actions={
        SwipeTile(onClick={haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress);onDelete()},
            modifier=Modifier.testTag("deleteHistory:$id"),destructive=true) { Icon(Icons.Default.Delete,"删除") }
    },content=content)
}

@Composable internal fun SwipeTile(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    destructive:Boolean=false,icon:@Composable ()->Unit) {
    Surface(onClick=onClick,enabled=enabled,modifier=modifier.fillMaxHeight().width(56.dp),
        shape=ContentCardShape,border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),
        color=if(destructive) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor=if(destructive) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer) {
        Box(contentAlignment=Alignment.Center) { icon() }
    }
}

@Composable internal fun SwipeActionCard(tag:String,revealed:Boolean,onReveal:(Boolean)->Unit,
    actionWidth:androidx.compose.ui.unit.Dp,actionLabel:String,actions:@Composable RowScope.()->Unit,content:@Composable ()->Unit) {
    val width=with(LocalDensity.current) { actionWidth.toPx() }
    var offset by remember { mutableFloatStateOf(if(revealed) -width else 0f) }
    var release by remember { mutableIntStateOf(0) }
    LaunchedEffect(revealed,width,release) {
        androidx.compose.animation.core.animate(offset,if(revealed) -width else 0f) { value,_->offset=value }
    }
    Box(Modifier.fillMaxWidth().clip(ContentCardShape).testTag(tag).semantics { customActions=listOf(CustomAccessibilityAction(actionLabel) { onReveal(true);true }) }) {
        if(offset < -1f) Row(Modifier.matchParentSize(),horizontalArrangement=Arrangement.spacedBy(8.dp,Alignment.End),
            content=actions)
        Box(Modifier.offset { IntOffset(offset.roundToInt(),0) }.draggable(
            state=rememberDraggableState { offset=(offset+it).coerceIn(-width,0f) },orientation=Orientation.Horizontal,
            onDragStopped={ velocity ->
                val open=if(kotlin.math.abs(velocity)>600f) velocity<0 else offset < -width/2
                onReveal(open)
                release++
            })) { content() }
    }
}
