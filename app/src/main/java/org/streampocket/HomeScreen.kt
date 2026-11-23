// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** Search remains a single input while its parent handles scroll-driven visibility. */
@Composable fun HomeSearchBar(query:String,onQuery:(String)->Unit,searchMode:Boolean,submit:()->Unit,clear:()->Unit,placeholder:String="搜索影片、剧集或频道",clearLabel:String="清空并返回推荐",allowEmpty:Boolean=false,focusOnOpen:Boolean=false,onFocused:()->Unit={},cancelSearch:(()->Unit)?=null) {
    val requester=remember { FocusRequester() }
    LaunchedEffect(focusOnOpen) { if(focusOnOpen) { requester.requestFocus();onFocused() } }
    Surface(color=MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
            ContentSearchField(query,onQuery,placeholder,onSearch={if(allowEmpty || query.isNotBlank()) submit()},modifier=Modifier.widthIn(max=1100.dp).fillMaxWidth().padding(vertical=4.dp)
                .focusRequester(requester).testTag("searchInput").semantics { contentDescription="影片或频道名称" },trailingIcon={
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        AnimatedVisibility(query.isNotEmpty() || searchMode,enter=fadeIn(tween(120))+expandHorizontally(tween(180)),exit=fadeOut(tween(90))+shrinkHorizontally(tween(150))) {
                            GlassIconButton(onClick=clear) { Icon(Icons.Default.Close,clearLabel) }
                        }
                        if (cancelSearch != null) {
                            GlassIconButton(onClick=cancelSearch) { Icon(painterResource(R.drawable.ic_stop_search),"取消搜索") }
                        } else GlassIconButton(onClick=submit,enabled=allowEmpty || query.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.ArrowForward,"开始搜索") }
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
    val presentation by androidx.compose.runtime.produceState(remember(query) { SearchPresentation(emptyList(),query,false) },s.results,query,showOthers) {
        value=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { SearchPresentation(s.results,query,showOthers) }
    }
    val counts=presentation.counts
    val candidateGroups=presentation.groups
    val groups=remember(presentation,category) { presentation.groupsFor(category) }
    val grid=rememberLazyGridState()
    val scope=rememberCoroutineScope()
    ScrollToTopOnChange(category.name,showOthers.toString(),s.searchOrder.name) { grid.scrollToItem(0) }
    val largeTypeLayout = windowSizeDp().width < 300.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale
    val dockInset = LocalDockInset.current
    Column(Modifier.fillMaxSize().padding(bottom=if(largeTypeLayout) dockInset else 0.dp)) {
        if(s.results.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(horizontal=PageGutter).testTag("searchToolbar")) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                LazyRow(Modifier.weight(1f).testTag("searchCategories"),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    items(Genre.entries.filter { it != Genre.UNKNOWN || it == category || (counts[it] ?: 0) > 0 }) { option ->
                        ContentFilterChip(selected=category==option,onClick={category=option;model.requestSourceProbe(null)},
                            modifier=Modifier.testTag("searchCategory:${option.name}"),
                            label={Text("${option.label} ${if(option==Genre.ALL) candidateGroups.size else counts[option] ?: 0}")})
                    }
                }
                Box {
                    GlassIconButton(onClick={sortMenu=true},modifier=Modifier.testTag("searchSort")) {
                        Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_sort),"排序：${s.searchOrder.label}")
                    }
                    GlassMenu(expanded=sortMenu,onDismissRequest={sortMenu=false}) {
                        SearchOrder.entries.forEach { order -> DropdownMenuItem(text={Text(order.label)},
                            onClick={model.sortSearch(order);sortMenu=false},trailingIcon={if(order==s.searchOrder) Text("已选")}) }
                    }
                }
            }
            if(presentation.matchCount < presentation.rows.size) GlassTextButton(onClick={showOthers=!showOthers;model.requestSourceProbe(null)},modifier=Modifier.testTag("showOtherResults")) {
                Text(if(showOthers) "仅看匹配片名" else "展开其他源站结果 · ${presentation.rows.size-presentation.matchCount}")
            }
        }
        Box(Modifier.weight(1f)) {
    LazyVerticalGrid(columns=GridCells.Adaptive(320.dp),state=grid,contentPadding=PaddingValues(start=PageGutter,end=PageGutter,top=8.dp,bottom=16.dp+(if(largeTypeLayout) 0.dp else dockInset)),horizontalArrangement=Arrangement.spacedBy(12.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.fillMaxSize().testTag("searchResults").pointerInput(model) {
            awaitPointerEventScope { while(true) { if(awaitPointerEvent().type==PointerEventType.Press) model.pinSearchOrder() } }
        }) {
        if(s.searching) item(key="search-progress",span={GridItemSpan(maxLineSpan)}) {
            SearchProgress(s.completed,s.total)
        }
        if(s.searching && s.results.isEmpty()) items(3,contentType={"searchPlaceholder"}) {
            SearchResultPlaceholder()
        }
        if (s.results.size == QualityRules.RESULT_LIMIT) item(span={GridItemSpan(maxLineSpan)}) {
            Text("已显示前 1000 条，可细化关键词", style = MaterialTheme.typography.bodySmall)
        }
        if(groups.isEmpty() && !s.searching && presentation.rows === s.results) item(span={GridItemSpan(maxLineSpan)}) {
            Column {
                val unavailable=s.results.isEmpty() && s.failures>0
                val title=when {
                    s.total==0 -> "还没有可搜索的来源"
                    s.results.isNotEmpty() -> "没有匹配的影片"
                    unavailable && s.failures==s.total -> "片源暂时无法响应"
                    unavailable -> "已响应的片源暂无匹配"
                    else -> "暂未找到结果"
                }
                val hint=when {
                    s.total==0 -> "添加订阅或启用来源后，再搜索这部影片。"
                    s.results.isNotEmpty() -> "当前已加载结果没有该题材，可切换题材或查看其他源站结果。"
                    unavailable && s.nonPublicDnsFailures>0 -> "${s.nonPublicDnsFailures} 个片源域名解析到非公网地址，请检查片源地址或代理的 DNS 设置。"
                    unavailable -> "${s.failures} 个片源请求失败，可重新搜索。"
                    else -> "试试其他关键词，或检查已启用的订阅来源。"
                }
                if(compact) { Text(title);if(unavailable) Text(hint,style=MaterialTheme.typography.bodySmall) } else EmptyPanel(title,hint,R.drawable.ic_search)
                if(unavailable) GlassTextButton(onClick={model.search(query)}) { Text("重新搜索") }
                GlassButton(shape=MaterialTheme.shapes.medium,onClick=subscriptions) { Text("管理订阅") }
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

@Composable private fun SearchProgress(completed:Int,total:Int) {
    val tones = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(if(completed==0) "正在查找片源…" else "已查找 $completed/$total 个片源",
            style=MaterialTheme.typography.labelMedium,color=tones.onSurfaceVariant)
        val bar=Modifier.fillMaxWidth().height(3.dp).testTag("searchProgress")
        if(completed==0) LinearProgressIndicator(modifier=bar,color=tones.onSurfaceVariant,trackColor=tones.outlineVariant)
        else LinearProgressIndicator(progress={completed.toFloat()/total.coerceAtLeast(1)},modifier=bar,
            color=tones.onSurfaceVariant,trackColor=tones.outlineVariant)
    }
}

/** Match a result's cover and text column without exposing decorative nodes or running a shimmer. */
@Composable private fun SearchResultPlaceholder() {
    val tones=MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().background(tones.surfaceContainerLow,ContentCardShape).padding(MediaCardInset).testTag("searchResultPlaceholder"),
        horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.size(68.dp,88.dp).background(tones.surfaceContainerHigh,MaterialTheme.shapes.small))
        Column(Modifier.weight(1f).padding(top=4.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.fillMaxWidth(.68f).height(15.dp).background(tones.surfaceContainerHigh,MaterialTheme.shapes.small))
            Spacer(Modifier.fillMaxWidth(.45f).height(10.dp).background(tones.surfaceContainer,MaterialTheme.shapes.small))
            Spacer(Modifier.width(46.dp).height(10.dp).background(tones.surfaceContainer,MaterialTheme.shapes.small))
        }
    }
}

@Composable private fun SearchWorkCard(group:List<Video>,state:UiState,model:AppModel,expandedWork:String?,expand:(String?)->Unit,modifier:Modifier) {
    var selectedWork by rememberSaveable(VideoGroups.familyKey(group.first())) { mutableStateOf(state.probeWork?.takeIf { key -> group.any { VideoGroups.workKey(it)==key } } ?: VideoGroups.workKey(group.first())) }
    LaunchedEffect(state.detail?.video) { state.detail?.video?.takeIf { VideoGroups.familyKey(it)==VideoGroups.familyKey(group.first()) }?.let { selectedWork=VideoGroups.workKey(it) } }
    val options=VideoGroups.searchSources(selectedWork,group).ifEmpty { listOf(group.first()) }
    val expanded=expandedWork!=null && options.any { VideoGroups.workKey(it)==expandedWork }
    val video=options.first()
    val anchor = rememberPosterAnchor(video,model)
    ContentCard(modifier.fillMaxWidth().testTag("searchGroup:${VideoGroups.familyKey(video)}"), containerColor=MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(MediaCardInset),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { expand(if(expanded) null else selectedWork) }.testTag("searchVideo:${video.id}"),
                horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.Top) {
                Poster(video,model,Modifier.size(68.dp,88.dp).then(anchor.modifier),shape=MaterialTheme.shapes.small)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Text(video.title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                    val metadata=SearchRules.metadata(video)
                    if(metadata.isNotEmpty()) Text(metadata,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text("${options.size} 个来源",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if(expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,if(expanded) "收起来源" else "查看来源",Modifier.align(Alignment.CenterVertically))
            }
            SeasonChips(group,video,{selectedWork=VideoGroups.workKey(it);expand(null)},search=true)
            if(expanded) {
                ContentDivider()
                val ready=options.any { state.probes[it.probeKey()]==ProbeStatus.READY }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(if(ready) "已找到可用来源 · 已停止探测" else if(state.probeRunning && state.probeWork==selectedWork) "正在检测播放来源…" else "暂未确认可用来源，可手动尝试",
                        Modifier.weight(1f).testTag("probeSummary"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(!state.probeRunning && !ready) GlassTextButton(onClick={model.requestSourceProbe(selectedWork,retry=true)}) { Text("重试") }
                }
                SourceChoices(options.sortedBy { if(state.probes[it.probeKey()]==ProbeStatus.READY) 0 else 1 },state.detail?.video,state.probes,state.probeResolutions,{ choice -> anchor.open(choice) { model.open(choice) } },Int.MAX_VALUE,rowInset=0.dp)
            }
        }
    }
}
