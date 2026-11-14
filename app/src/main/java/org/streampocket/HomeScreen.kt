// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** Search remains a single input while its parent handles scroll-driven visibility. */
@Composable fun HomeSearchBar(query:String,onQuery:(String)->Unit,searchMode:Boolean,submit:()->Unit,clear:()->Unit,placeholder:String="搜索影片、剧集或频道",clearLabel:String="清空并返回推荐",allowEmpty:Boolean=false) {
    Surface(color=MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
            ContentSearchField(query,onQuery,placeholder,onSearch={if(allowEmpty || query.isNotBlank()) submit()},modifier=Modifier.widthIn(max=1100.dp).fillMaxWidth().padding(horizontal=20.dp,vertical=4.dp)
                .testTag("searchInput").semantics { contentDescription="影片或频道名称" },trailingIcon={
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        AnimatedVisibility(query.isNotEmpty() || searchMode,enter=fadeIn(tween(120))+expandHorizontally(tween(180)),exit=fadeOut(tween(90))+shrinkHorizontally(tween(150))) {
                            IconButton(onClick=clear,modifier=Modifier.size(48.dp)) { Icon(Icons.Default.Close,clearLabel) }
                        }
                        IconButton(onClick=submit,enabled=allowEmpty || query.isNotBlank(),modifier=Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowForward,"开始搜索") }
                    }
                })
        }
    }
}

@Composable fun SearchResultsScreen(s:UiState,model:AppModel,query:String,compact:Boolean,back:()->Unit,subscriptions:()->Unit,onTop:()->Unit={}) {
    var category by rememberSaveable(query) { mutableStateOf(Genre.ALL) }

    var showOthers by rememberSaveable(query) { mutableStateOf(false) }
    val probeLifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model,query,probeLifecycle) {
        val observer=androidx.lifecycle.LifecycleEventObserver { _,event ->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_START) model.setSearchProbeActive(true)
            if(event==androidx.lifecycle.Lifecycle.Event.ON_STOP) model.setSearchProbeActive(false)
        }
        probeLifecycle.addObserver(observer)
        if(probeLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) model.setSearchProbeActive(true)
        onDispose { probeLifecycle.removeObserver(observer);model.setSearchProbeActive(false) }
    }
    var sortMenu by remember { mutableStateOf(false) }
    val matches = remember(s.results, query) { s.results.filter { SearchRules.relevance(it, query) > 0 } }
    val candidates = if (showOthers) s.results else matches
    val candidateGroups=remember(candidates) { VideoGroups.searchGroups(candidates) }
    val counts = remember(candidateGroups) { candidateGroups.flatMap { group -> group.flatMap { Genres.of(it) }.distinct() }.groupingBy { it }.eachCount() }
    val videos = remember(candidates, category) { candidates.filter { category == Genre.ALL || Genres.matches(it, category) } }
    val groups=remember(videos) { VideoGroups.searchGroups(videos) }
    val progress by animateFloatAsState(if(s.total==0) 0f else s.completed.toFloat()/s.total,tween(180),label="searchProgress")
    val grid=rememberLazyGridState()
    val scope=rememberCoroutineScope()
    ScrollToTopOnChange(category.name,showOthers.toString(),s.searchOrder.name) { grid.scrollToItem(0) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).testTag("searchToolbar")) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val failureHint = if (s.failures > 0) " · ${s.failures} 个失败" else ""
                    Text("${groups.size} 部 · ${videos.size} 个片源 · "+(if(s.searching) "已搜索 ${s.completed}/${s.total}" else "${s.completed-s.failures} 个来源")+failureHint, Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick=back,modifier=Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回推荐") }
                    if (s.results.isNotEmpty()) Box {
                        IconButton(onClick={sortMenu=true},modifier=Modifier.size(48.dp).testTag("searchSort")) { Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_sort),"排序：${s.searchOrder.label}") }
                        GlassMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            SearchOrder.entries.forEach { order -> DropdownMenuItem(text = { Text(order.label) },
                                onClick = { model.sortSearch(order); sortMenu = false }, trailingIcon = { if (order == s.searchOrder) Text("已选") }) }
                        }
                    }
                }
        if(s.results.isNotEmpty()) Column {
            Column {
                LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.testTag("searchCategories")) {
                    items(Genre.entries.filter { it != Genre.UNKNOWN || it == category || (counts[it] ?: 0) > 0 }) { option ->
                        ContentFilterChip(selected=category==option,onClick={category=option;model.requestSourceProbe(null)},
                            modifier=Modifier.testTag("searchCategory:${option.name}"),
                            label={Text("${option.label} ${if(option==Genre.ALL) candidateGroups.size else counts[option] ?: 0}")})
                    }
                }
                if(matches.size < s.results.size) TextButton(onClick={showOthers=!showOthers;model.requestSourceProbe(null)},modifier=Modifier.testTag("showOtherResults")) {
                    Text(if(showOthers) "仅看匹配片名" else "展开其他源站结果 · ${s.results.size-matches.size}")
                }
            }
        }
        }
        Box(Modifier.weight(1f)) {
    LazyVerticalGrid(columns=GridCells.Adaptive(320.dp),state=grid,contentPadding=PaddingValues(start=20.dp,end=20.dp,top=8.dp,bottom=16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.fillMaxSize().testTag("searchResults").pointerInput(model) {
            awaitPointerEventScope { while(true) { if(awaitPointerEvent().type==PointerEventType.Press) model.pinSearchOrder() } }
        }) {
        item(span={GridItemSpan(maxLineSpan)}) {
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                if(s.searching) {
                    LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth())
                    TextButton(onClick=model::cancelSearch) { Text("取消搜索") }
                }
                if (s.results.size == QualityRules.RESULT_LIMIT) Text("已显示前 1000 条，可细化关键词", style = MaterialTheme.typography.bodySmall)
            }
        }
        if(videos.isEmpty()) item(span={GridItemSpan(maxLineSpan)}) {
            Column {
                if(!compact) EmptyPanel(if(s.searching) "正在寻找内容" else if(s.total == 0) "还没有可搜索的来源" else if(s.results.isNotEmpty()) "没有匹配的影片" else "暂未找到结果",
                    if(s.searching) "结果会陆续显示，你可以随时取消。" else if(s.total == 0) "添加订阅或启用来源后，再搜索这部影片。" else if(s.results.isNotEmpty()) "当前已加载结果没有该题材，可切换题材或查看其他源站结果。" else "试试其他关键词，或检查已启用的订阅来源。",R.drawable.ic_search)
                if (compact && !s.searching) Text(if (s.total == 0) "请先添加或启用订阅来源" else "暂无结果，试试其他关键词")
                if(!s.searching) GlassButton(shape=MaterialTheme.shapes.medium,onClick=subscriptions) { Text("管理订阅") }
            }
        }
        items(groups,key={VideoGroups.familyKey(it.first())}) { group ->
            SearchWorkCard(group,s,model,s.probeWork,{model.requestSourceProbe(it)},Modifier.animateItem(fadeInSpec=tween(140),placementSpec=null,fadeOutSpec=null))
        }
    }
    val showTop by remember { derivedStateOf { grid.firstVisibleItemIndex>3 } }
    BackToTop(showTop,Modifier.align(Alignment.BottomEnd)) { scope.launch { grid.animateScrollToItem(0);onTop() } }
        }
    }
}

@Composable private fun SearchWorkCard(group:List<Video>,state:UiState,model:AppModel,expandedWork:String?,expand:(String?)->Unit,modifier:Modifier) {
    var selectedWork by rememberSaveable(VideoGroups.familyKey(group.first())) { mutableStateOf(state.probeWork?.takeIf { key -> group.any { VideoGroups.workKey(it)==key } } ?: VideoGroups.workKey(group.first())) }
    LaunchedEffect(state.detail?.video) { state.detail?.video?.takeIf { VideoGroups.familyKey(it)==VideoGroups.familyKey(group.first()) }?.let { selectedWork=VideoGroups.workKey(it) } }
    val options=VideoGroups.searchSources(selectedWork,group).ifEmpty { listOf(group.first()) }
    val expanded=expandedWork!=null && options.any { VideoGroups.workKey(it)==expandedWork }
    val video=options.first()
    ContentCard(modifier.fillMaxWidth().testTag("searchGroup:${VideoGroups.familyKey(video)}")) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expand(if(expanded) null else selectedWork) }.testTag("searchVideo:${video.id}"),
                horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.Top) {
                Poster(video,model,Modifier.size(84.dp,122.dp),shape=MaterialTheme.shapes.small)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Text(video.title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                    val metadata=SearchRules.metadata(video)
                    if(metadata.isNotEmpty()) Text(metadata,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text("${options.size} 个来源",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    GlassButton(onClick={expand(if(expanded) null else selectedWork)},modifier=Modifier.heightIn(min=48.dp),shape=MaterialTheme.shapes.small) { Text(if(expanded) "收起来源" else "查看来源") }
                }
            }
            SeasonChips(group,video,{selectedWork=VideoGroups.workKey(it);expand(null)},search=true)
            if(expanded) {
                val ready=options.any { state.probes[it.probeKey()]==ProbeStatus.READY }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(if(ready) "已找到可用来源 · 已停止探测" else if(state.probeRunning && state.probeWork==selectedWork) "正在检测播放来源…" else "暂未确认可用来源，可手动尝试",
                        Modifier.weight(1f).testTag("probeSummary"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(!state.probeRunning && !ready) TextButton(onClick={model.requestSourceProbe(selectedWork,retry=true)}) { Text("重试") }
                }
                SourceChoices(options.sortedBy { if(state.probes[it.probeKey()]==ProbeStatus.READY) 0 else 1 },state.detail?.video,state,{model.open(it)},Int.MAX_VALUE)
            }
        }
    }
}
