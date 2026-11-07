// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable fun DiscoverScreen(s:UiState,model:AppModel,addSource:()->Unit,searchTitle:(String)->Unit,
    recommendations:MutableState<Boolean> = rememberSaveable { mutableStateOf(true) },catalogQuery:String=s.catalogQuery,onTop:()->Unit={}) {
    val sources = remember(s.saved.subscriptions, s.saved.disabled) { s.saved.enabledSources(SourceKind.VOD) }
    var recommendationSort by rememberSaveable { mutableStateOf(DiscoverySort.DEFAULT) }
    var catalogSort by rememberSaveable { mutableStateOf(DiscoverySort.DEFAULT) }
    val catalogVideos=remember(s.catalogVideos,catalogSort) { catalogSort.apply(s.catalogVideos) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var draftGenres by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var draftSources by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var draftCategory by rememberSaveable { mutableStateOf<String?>(null) }
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle,model) {
        val observer=androidx.lifecycle.LifecycleEventObserver { _,event ->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_START) model.discoveryForeground()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=androidx.compose.ui.platform.LocalFocusManager.current
    fun openPicker(value:String) {
        focus.clearFocus();keyboard?.hide()
        draftGenres=s.catalogGenres.map { it.name };draftCategory=s.catalogCategory;draftSources=s.catalogSourceKeys.toList();picker=value
    }
    LaunchedEffect(sources.map { it.key }) { if(sources.isNotEmpty() && s.catalogSource==null) model.browseSources(emptySet()) }
    if(picker!=null) ModalBottomSheet(onDismissRequest={picker=null},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        SheetSystemBars()
        Column(Modifier.fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false).testTag("catalogPicker"),contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Row(verticalAlignment=Alignment.CenterVertically) {
                Text(if(picker=="source") "选择片库" else "筛选题材",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                TextButton(onClick={picker=null}) { Text("取消") }
            } }
            if(picker=="source") {
                item { Text("不选即搜索所有来源",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                items(sources,key={it.key}) { source ->
                    ListItem(headlineContent={Text(source.name)},leadingContent={Checkbox(checked=source.key in draftSources,onCheckedChange=null)},
                        modifier=Modifier.fillMaxWidth().toggleable(value=source.key in draftSources,role=androidx.compose.ui.semantics.Role.Checkbox) {
                            draftSources=if(source.key in draftSources) draftSources-source.key else draftSources+source.key
                        }.testTag("catalogSource:${source.key}"))
                }
            } else {
                item { Text("多选取并集，不选即不限",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                item { FlowRow(Modifier.fillMaxWidth().testTag("catalogGenres"),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Genre.entries.filter { it!=Genre.UNKNOWN }.forEach { option ->
                        ContentFilterChip(selected=if(option==Genre.ALL) draftGenres.isEmpty() && draftCategory==null else option.name in draftGenres,
                            onClick={
                                draftCategory=null
                                draftGenres=if(option==Genre.ALL) emptyList() else if(option.name in draftGenres) draftGenres-option.name else draftGenres+option.name
                            },
                            modifier=Modifier.testTag("catalogGenre:${option.name}"),reserveSelectionSpace=true,label={Text(option.label)})
                    }
                } }
                if(s.categories.isNotEmpty()) {
                    item { Text("片库分类",style=MaterialTheme.typography.titleSmall) }
                    item { FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        s.categories.forEach { category ->
                            ContentFilterChip(selected=draftCategory==category.id,
                                onClick={draftGenres=emptyList();draftCategory=if(draftCategory==category.id) null else category.id},
                                modifier=Modifier.testTag("sourceCategory:${category.id}"),reserveSelectionSpace=true,label={Text(category.name)})
                        }
                    } }
                }
            }
        }
        run {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={if(picker=="source") draftSources=emptyList() else { draftGenres=emptyList();draftCategory=null }},modifier=Modifier.testTag("clearCatalogFilters")) { Text("重置") }
                Button(onClick={
                    if(picker=="source") model.browseSources(draftSources.toSet(),query=catalogQuery.trim(),genres=s.catalogGenres)
                    else model.browseSources(s.catalogSourceKeys,draftCategory,catalogQuery.trim(),genres=draftGenres.map { Genre.valueOf(it) }.toSet())
                    picker=null
                },modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("applyCatalogFilters")) { Text("应用") }
            }
        }
        }
    }
    val catalogState=rememberLazyListState()
    val scope=rememberCoroutineScope()
    val latest by rememberUpdatedState(s)
    LaunchedEffect(s.catalogSourceKeys,s.catalogCategory,s.catalogGenres,s.catalogQuery,catalogSort) { catalogState.scrollToItem(0) }
    var catalogDemand by remember(s.catalogSourceKeys,s.catalogCategory,s.catalogGenres,s.catalogQuery) { mutableIntStateOf(-1) }
    // Request each result boundary once; sparse pages remain manually resumable.
    LaunchedEffect(recommendations.value,s.catalogVideos.size,s.catalogLoading,s.catalogSourceKeys,s.catalogCategory,s.catalogGenres,s.catalogQuery) {
        if(!recommendations.value && s.catalogVideos.isNotEmpty()) snapshotFlow {
            catalogState.firstVisibleItemIndex>0 && (catalogState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0)>=catalogState.layoutInfo.totalItemsCount-3
        }.distinctUntilChanged().collect { nearEnd ->
            val current=latest
            if(nearEnd && current.catalogMore && !current.catalogLoading && current.catalogError==null && catalogDemand!=current.catalogVideos.size) {
                catalogDemand=current.catalogVideos.size
                model.browseSources(current.catalogSourceKeys,current.catalogCategory,current.catalogQuery,true,current.catalogGenres)
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex=if(recommendations.value) 0 else 1,containerColor=MaterialTheme.colorScheme.background) {
            Tab(selected=recommendations.value,onClick={recommendations.value=true},text={Text("推荐")})
            Tab(selected=!recommendations.value,onClick={recommendations.value=false},text={Text("分类搜索")})
        }
        if(recommendations.value) { RecommendationList(s,model,searchTitle,recommendationSort,{ recommendationSort=it },onTop); return@Column }
        if(s.catalogSource!=null) {
            Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).testTag("catalogToolbar")) {
                    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={openPicker("source")},modifier=Modifier.testTag("catalogSourcePicker")) {
                            Text(if(s.catalogSourceKeys.isEmpty()) "全部来源" else "来源 · ${s.catalogSourceKeys.size}",maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=180.dp));Icon(Icons.Default.ArrowDropDown,null)
                        }
                        FilledTonalButton(onClick={openPicker("genre")},modifier=Modifier.testTag("catalogGenrePicker")) {
                            Text(s.categories.find { it.id==s.catalogCategory }?.name ?: if(s.catalogGenres.isEmpty()) "题材 · 不限" else "题材 · ${s.catalogGenres.size}");Icon(Icons.Default.ArrowDropDown,null)
                        }
                    }
                    if(s.catalogSourceKeys.isNotEmpty() || s.catalogGenres.isNotEmpty() || s.catalogCategory!=null) {
                        LazyRow(Modifier.fillMaxWidth().testTag("activeCatalogFilters"),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            items(sources.filter { it.key in s.catalogSourceKeys },key={it.key}) { selected ->
                                InputChip(selected=true,onClick={model.browseSources(s.catalogSourceKeys-selected.key,query=catalogQuery.trim(),genres=s.catalogGenres)},
                                    label={Text(selected.name,maxLines=1,overflow=TextOverflow.Ellipsis)},trailingIcon={Icon(Icons.Default.Close,"取消${selected.name}",Modifier.size(16.dp))},
                                    modifier=Modifier.widthIn(max=200.dp).testTag("activeSource:${selected.key}"))
                            }
                            items(s.catalogGenres.sortedBy { it.ordinal },key={it.name}) { genre ->
                                InputChip(selected=true,onClick={model.browseSources(s.catalogSourceKeys,query=catalogQuery.trim(),genres=s.catalogGenres-genre)},
                                    label={Text(genre.label)},trailingIcon={Icon(Icons.Default.Close,"取消${genre.label}",Modifier.size(16.dp))},
                                    modifier=Modifier.testTag("activeGenre:${genre.name}"))
                            }
                            s.categories.find { it.id==s.catalogCategory }?.let { category -> item {
                                InputChip(selected=true,onClick={model.browseSources(s.catalogSourceKeys,query=catalogQuery.trim())},label={Text(category.name)},
                                    trailingIcon={Icon(Icons.Default.Close,"取消${category.name}",Modifier.size(16.dp))})
                            } }
                        }
                    }
                    DiscoverySortControl(catalogSort,{catalogSort=it},catalogVideos,false)
                }
        }
        Box(Modifier.weight(1f)) {
        LazyColumn(Modifier.fillMaxSize().testTag("catalogList"),state=catalogState,contentPadding=PaddingValues(start=20.dp,end=20.dp,top=8.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(sources.isEmpty()) item {
                EmptyPanel("从你的订阅开始","添加点播来源即可浏览分类。",R.drawable.ic_library)
                Button(onClick=addSource) { Text("添加订阅") }
            }
            s.catalogSource?.let { source ->
                if(!s.catalogLoading && catalogVideos.isEmpty() && s.catalogError==null) item {
                    Column(Modifier.padding(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(if(s.catalogMore) "还没有找到匹配影片" else "当前片库未找到匹配影片",style=MaterialTheme.typography.titleMedium)
                        Text(if(s.catalogMore) "已查找 ${s.catalogScanned} 页，可继续查找。" else if(sources.size>1) "可切换题材或片库；部分来源不提供题材信息。" else "可更换题材；部分来源不提供题材信息。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick={openPicker("genre")}) { Text("更换题材") }
                    }
                }
                items(catalogVideos,key={it.source.key+":"+it.id}) { video ->
                    DiscoveryCard(onClick={model.open(video)},modifier=Modifier.fillMaxWidth().animateItem().testTag("catalogVideo:${video.id}")) {
                        Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.Top) {
                            Poster(video,model,Modifier.size(88.dp,132.dp),shape=RectangleShape,containerColor=MaterialTheme.colorScheme.surfaceContainer)
                            Column(Modifier.weight(1f).padding(top=12.dp,end=12.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                CardTextSlot(video.title,style=MaterialTheme.typography.titleMedium)
                                if(SearchRules.metadata(video).isNotBlank()) Text(SearchRules.metadata(video).replace(" · ","  "),style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                                if(s.catalogSourceKeys.size!=1) Text(video.source.name,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                                if(video.rating!=null) Text("来源评分 ${video.rating}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                video.updatedAt?.let { Text("更新 ${updatedDate(it)}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
                if(s.catalogLoading) item { Row(verticalAlignment=Alignment.CenterVertically) {
                    LinearProgressIndicator(progress={ if(s.catalogTotal==0) 0f else s.catalogCompleted.toFloat()/s.catalogTotal },modifier=Modifier.weight(1f));TextButton(onClick=model::cancelCatalog) { Text("取消") }
                } }
                s.catalogError?.let { message -> item {
                    Text(message)
                    TextButton(onClick={model.browseSources(s.catalogSourceKeys,s.catalogCategory,s.catalogQuery,more=s.catalogMore,genres=s.catalogGenres)}) { Text("重新加载") }
                } }
                if(s.catalogMore && !s.catalogLoading) item {
                    OutlinedButton(onClick={model.browseSources(s.catalogSourceKeys,s.catalogCategory,s.catalogQuery,true,s.catalogGenres)},modifier=Modifier.fillMaxWidth().testTag("catalogMore")) {
                        Text(if(s.catalogGenres.isEmpty()) "加载更多" else "继续查找")
                    }
                }
            }
        }
        val showTop by remember { derivedStateOf { catalogState.firstVisibleItemIndex>2 } }
        BackToTop(showTop,Modifier.align(Alignment.BottomEnd)) { scope.launch { catalogState.animateScrollToItem(0);onTop() } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable private fun RecommendationList(s:UiState,model:AppModel,searchTitle:(String)->Unit,sort:DiscoverySort,onSort:(DiscoverySort)->Unit,onTop:()->Unit) {
    val videos=remember(s.recommendations,sort) { sort.apply(s.recommendations) }
    val recent=remember(s.saved.history) { HistoryGroups.group(s.saved.history).take(5) }
    val grid=rememberLazyGridState()
    val scope=rememberCoroutineScope()
    val latest by rememberUpdatedState(s)
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(s.recommendLoading) { if(!s.recommendLoading) refreshing=false }
    LaunchedEffect(s.recommendType,s.recommendTag,sort) { grid.scrollToItem(0) }
    var recommendationDemand by remember(s.recommendType,s.recommendTag) { mutableIntStateOf(-1) }
    LaunchedEffect(s.recommendations.size,s.recommendLoading,s.recommendType,s.recommendTag) {
        if(s.recommendations.isNotEmpty()) snapshotFlow {
            grid.firstVisibleItemIndex>0 && (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0)>=grid.layoutInfo.totalItemsCount-5
        }.distinctUntilChanged().collect { nearEnd ->
            val current=latest
            if(nearEnd && current.recommendMore && !current.recommendLoading && current.recommendError==null && recommendationDemand!=current.recommendations.size) {
                recommendationDemand=current.recommendations.size;model.recommend(more=true)
            }
        }
    }
    fun refresh() { if(!s.recommendLoading) { refreshing=true;recommendationDemand=-1;model.recommend() } }
    Column(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).testTag("recommendToolbar")) {
        Column {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Row(Modifier.weight(1f),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    SingleChoiceSegmentedButtonRow(Modifier.weight(1f).padding(end=12.dp)) {
                        listOf("tv" to "剧集","movie" to "电影").forEachIndexed { index,(type,label) ->
                            SegmentedButton(selected=s.recommendType==type,onClick={model.recommend(type)},shape=SegmentedButtonDefaults.itemShape(index,2)) { Text(label) }
                        }
                    }
                    IconButton(onClick={refresh()},modifier=Modifier.size(48.dp)) { Icon(Icons.Default.Refresh,"刷新推荐") }
                }
            }
        }
        Column {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                LazyRow(modifier=Modifier.weight(1f).testTag("recommendFilters"),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    items(Recommendations.tags(s.recommendType)) { tag -> ContentFilterChip(selected=s.recommendTag==tag,onClick={model.recommend(tag=tag)},label={Text(tag)}) }
                }
                DiscoverySortControl(sort,onSort,s.recommendations,true,compact=true)
            }
        }
    }
    PullToRefreshBox(isRefreshing=refreshing,onRefresh={refresh()},modifier=Modifier.weight(1f).testTag("recommendRefresh")) {
    LazyVerticalGrid(columns=GridCells.Fixed(2),state=grid,
        modifier=Modifier.fillMaxSize().testTag("recommendationGrid"),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=12.dp,bottom=16.dp),
        horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(recent.isNotEmpty()) item(span={GridItemSpan(maxLineSpan)},key="continue-watching") {
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("继续观看",style=MaterialTheme.typography.titleMedium)
                BoxWithConstraints {
                    val cardWidth=minOf(maxWidth,360.dp)
                    LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.testTag("continueWatching")) {
                        items(recent,key={it.key.stableKey}) { group ->
                            val entry=group.latest
                            DiscoveryCard(onClick={model.resume(entry)},modifier=Modifier.width(cardWidth).testTag("discoverContinue:${group.key.stableKey}")) {
                                Box {
                                    Column(Modifier.fillMaxWidth().heightIn(min=88.dp).padding(start=68.dp,end=12.dp,top=10.dp,bottom=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp,Alignment.CenterVertically)) {
                                        Text(entry.playback.video.title,style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                                        Text(entry.playback.episode.name,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                                        Text(if(entry.playback.video.source.kind==SourceKind.LIVE) "打开直播" else "继续播放",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                                    }
                                    Box(Modifier.matchParentSize()) {
                                        Poster(entry.playback.video,model,Modifier.width(56.dp).fillMaxHeight(),shape=RectangleShape,containerColor=MaterialTheme.colorScheme.surfaceContainer)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if(!s.recommendLoading && s.recommendError==null && s.recommendations.isEmpty()) item(span={GridItemSpan(maxLineSpan)}) { Text("当前分类暂无推荐，请切换分类。") }
        items(videos,key={it.id},contentType={"recommendation"}) { video ->
            DiscoveryCard(onClick={searchTitle(video.title)},modifier=Modifier.fillMaxWidth().animateItem().testTag("recommendation:${video.id}")) {
                BoxWithConstraints {
                    val artwork:@Composable (Modifier)->Unit = { size ->
                        Box {
                            Poster(video,model,size.testTag("recommendPoster:${video.id}"),shape=RectangleShape,containerColor=MaterialTheme.colorScheme.surfaceContainer,contentScale=ContentScale.Crop)
                            RecommendationRating(video,Modifier.align(Alignment.TopEnd).padding(8.dp))
                        }
                    }
                    val title:@Composable ()->Unit = {
                        CardTextSlot(video.title,modifier=Modifier.padding(horizontal=10.dp,vertical=4.dp).testTag("recommendTitle:${video.id}"),style=MaterialTheme.typography.bodyMedium.copy(fontSize=13.sp,lineHeight=17.sp),color=MaterialTheme.colorScheme.onSurface)
                    }
                    if(maxWidth>=240.dp) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            artwork(Modifier.size(112.dp,168.dp))
                            Box(Modifier.weight(1f)) { title() }
                        }
                    } else {
                        Column {
                            artwork(Modifier.fillMaxWidth().aspectRatio(0.75f))
                            title()
                        }
                    }
                }
            }
        }

        if(s.recommendLoading && !refreshing) item(span={GridItemSpan(maxLineSpan)}) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        s.recommendError?.let { error -> item(span={GridItemSpan(maxLineSpan)}) {
            Column { Text("推荐加载失败：$error")
                TextButton(onClick={model.recommend(more=s.recommendAppending)}) { Text("重试推荐") } }
        } }
        if(!s.recommendMore && !s.recommendLoading && videos.isNotEmpty()) item(span={GridItemSpan(maxLineSpan)}) { Text("已显示本次推荐",Modifier.fillMaxWidth().padding(12.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        if(s.recommendMore && !s.recommendLoading && s.recommendError==null) item(span={GridItemSpan(maxLineSpan)}) { OutlinedButton(onClick={model.recommend(more=true)}) { Text("更多推荐") } }
    }
    val showTop by remember { derivedStateOf { grid.firstVisibleItemIndex>3 } }
    BackToTop(showTop,Modifier.align(Alignment.BottomEnd)) { scope.launch { grid.animateScrollToItem(0);onTop() } }
    }
    }
}

@Composable internal fun BackToTop(visible:Boolean,modifier:Modifier=Modifier,onClick:()->Unit) {
    androidx.compose.animation.AnimatedVisibility(visible,modifier.padding(16.dp)) {
        SmallFloatingActionButton(onClick=onClick,modifier=Modifier.testTag("backToTop"),containerColor=MaterialTheme.colorScheme.secondaryContainer) {
            Icon(Icons.Default.KeyboardArrowUp,"回到顶部")
        }
    }
}

/** Artwork meets the outer clip; no inset surface or independently rounded poster. */
@Composable private fun DiscoveryCard(modifier:Modifier=Modifier,onClick:()->Unit,content:@Composable ColumnScope.()->Unit) {
    val interactions=remember { MutableInteractionSource() }
    Card(onClick=onClick,modifier=modifier.pressFeedback(interactions),interactionSource=interactions,shape=RoundedCornerShape(12.dp),
        colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer,contentColor=MaterialTheme.colorScheme.onSurface),content=content)
}

@Composable private fun RecommendationRating(video:Video,modifier:Modifier=Modifier) {
    val rating=video.rating?.toString()
    if(rating!=null) Surface(modifier=modifier.testTag("recommendRating:${video.id}").semantics { contentDescription="豆瓣评分 $rating 分" },
        shape=RoundedCornerShape(50),color=Color(0xee12212a),contentColor=Color(0xffffc12b)) {
        Row(Modifier.padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Default.Star,null,Modifier.size(16.dp))
            Text(rating,style=MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable private fun DiscoverySortControl(selected:DiscoverySort,onSelect:(DiscoverySort)->Unit,videos:List<Video>,recommendation:Boolean,compact:Boolean=false) {
    var expanded by remember { mutableStateOf(false) }
    val count: @Composable () -> Unit = {
        Text("${videos.size} 部",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val menu: @Composable () -> Unit = {
        Box {
            if(compact) IconButton(onClick={expanded=true},modifier=Modifier.size(48.dp).testTag("discoverySort")) { Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_sort),"排序：${selected.label}") }
            else TextButton(onClick={expanded=true},modifier=Modifier.heightIn(min=48.dp).testTag("discoverySort")) { Text("排序：${selected.label}");Icon(Icons.Default.ArrowDropDown,null) }
            DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                DiscoverySort.entries.forEach { option ->
                    DropdownMenuItem(text={Text(option.label)},onClick={onSelect(option);expanded=false},
                        enabled=option!=DiscoverySort.UPDATED || !recommendation,
                        trailingIcon={if(option==selected) Text("已选")})
                }
            }
        }
    }
    if(compact) menu()
    else if(LocalDensity.current.fontScale>1.3f) {
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) { count();menu() }
    } else {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            count();menu()
        }
    }
}

/** Material 3 1.3 dialogs do not inherit the activity's explicit day/night system-bar appearance. */
@Composable private fun SheetSystemBars() {
    val view=androidx.compose.ui.platform.LocalView.current
    val dark=LocalPocketDark.current
    DisposableEffect(view,dark) {
        val update=Runnable {
            val window=generateSequence(view.parent) { it.parent }
                .filterIsInstance<androidx.compose.ui.window.DialogWindowProvider>().firstOrNull()?.window
            window?.let {
                androidx.core.view.WindowCompat.getInsetsController(it,it.decorView).apply {
                    isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark
                }
                if(android.os.Build.VERSION.SDK_INT>=29) it.isNavigationBarContrastEnforced=false
            }
        }
        view.post(update)
        onDispose { view.removeCallbacks(update) }
    }
}
