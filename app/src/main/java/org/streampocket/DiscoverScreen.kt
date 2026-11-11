// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
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
    recommendations:MutableState<Boolean> = rememberSaveable { mutableStateOf(true) },catalogQuery:String=s.catalogQuery,liveTab:MutableState<Boolean> = rememberSaveable { mutableStateOf(false) },liveQuery:String="",onTop:()->Unit={}) {
    val sources = remember(s.saved.subscriptions, s.saved.disabled) { s.saved.enabledSources(SourceKind.VOD) }
    val recommendationFilters=rememberSaveable { mutableStateOf(false) }
    var recommendationSort by rememberSaveable { mutableStateOf(DiscoverySort.DEFAULT) }
    var catalogSort by rememberSaveable { mutableStateOf(DiscoverySort.DEFAULT) }
    val catalogVideos=remember(s.catalogVideos,catalogSort,s.probes) { catalogSort.apply(s.catalogVideos).sortedBy { if(s.probes[it.probeKey()]==ProbeStatus.READY) 0 else 1 } }
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
    val catalogProbeVisible=!recommendations.value && !liveTab.value && s.catalogQuery.isNotBlank()
    DisposableEffect(model,lifecycle,catalogProbeVisible,s.catalogQuery) {
        fun update() { model.setSearchProbeActive(catalogProbeVisible && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED),catalog=true) }
        val observer=androidx.lifecycle.LifecycleEventObserver { _,_->update() }
        lifecycle.addObserver(observer);update()
        onDispose { lifecycle.removeObserver(observer);model.setSearchProbeActive(false,catalog=true) }
    }
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=androidx.compose.ui.platform.LocalFocusManager.current
    fun openPicker(value:String) {
        focus.clearFocus();keyboard?.hide()
        draftGenres=s.catalogGenres.map { it.name };draftCategory=s.catalogCategory;draftSources=s.catalogSourceKeys.toList();picker=value
    }
    LaunchedEffect(sources.map { it.key }) { if(sources.isNotEmpty() && s.catalogSource==null) model.browseSources(emptySet()) }
    if(picker!=null) HandleOnlySheet(onDismissRequest={picker=null}) {
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
                    ListItem(colors=ListItemDefaults.colors(containerColor=androidx.compose.ui.graphics.Color.Transparent),headlineContent={Text(source.name)},leadingContent={Checkbox(checked=source.key in draftSources,onCheckedChange=null)},
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
                GlassButton(shape=MaterialTheme.shapes.medium,onClick={
                    if(picker=="source") model.browseSources(draftSources.toSet(),query=catalogQuery.trim(),genres=s.catalogGenres)
                    else model.browseSources(s.catalogSourceKeys,draftCategory,catalogQuery.trim(),genres=draftGenres.map { Genre.valueOf(it) }.toSet())
                    picker=null
                },modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("applyCatalogFilters")) { Text("应用") }
            }
        }
        }
    }
    val catalogState=rememberLazyGridState()
    val scope=rememberCoroutineScope()
    val latest by rememberUpdatedState(s)
    ScrollToTopOnChange(s.catalogSourceKeys.sorted().toString(),s.catalogCategory.orEmpty(),s.catalogGenres.sortedBy { it.ordinal }.toString(),s.catalogQuery,catalogSort.name) { catalogState.scrollToItem(0) }
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
        val selectedTab=if(liveTab.value) 2 else if(recommendations.value) 0 else 1
        val tabs:@Composable ()->Unit = {
            Tab(unselectedContentColor=MaterialTheme.colorScheme.onSurfaceVariant,selectedContentColor=MaterialTheme.colorScheme.primary,selected=!liveTab.value && recommendations.value,onClick={liveTab.value=false;recommendations.value=true},text={Text("推荐")})
            Tab(unselectedContentColor=MaterialTheme.colorScheme.onSurfaceVariant,selectedContentColor=MaterialTheme.colorScheme.primary,selected=!liveTab.value && !recommendations.value,onClick={liveTab.value=false;recommendations.value=false},text={Text("分类")})
            Tab(unselectedContentColor=MaterialTheme.colorScheme.onSurfaceVariant,selectedContentColor=MaterialTheme.colorScheme.primary,selected=liveTab.value,onClick={liveTab.value=true},text={Text("直播")},modifier=Modifier.testTag("liveTab"))
        }
        Row(Modifier.fillMaxWidth().testTag("recommendToolbar"),verticalAlignment=Alignment.CenterVertically) {
            PrimaryScrollableTabRow(selectedTabIndex=selectedTab,edgePadding=20.dp,containerColor=MaterialTheme.colorScheme.background,modifier=Modifier.weight(1f),divider={},tabs=tabs)
            if(recommendations.value && !liveTab.value) IconButton(onClick={recommendationFilters.value=true},modifier=Modifier.padding(end=12.dp).testTag("recommendCategoryPicker")) { Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_sort),"筛选推荐") }
        }
        if(liveTab.value) { LiveScreen(s,model,liveQuery,addSource);return@Column }
        if(recommendations.value) { RecommendationList(s,model,searchTitle,recommendationSort,{ recommendationSort=it },onTop,recommendationFilters); return@Column }
        if(s.catalogSource!=null) {
            Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).testTag("catalogToolbar")) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    LazyRow(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    item {
                        GlassButton(shape=MaterialTheme.shapes.small,border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f)),colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.onSurface),onClick={openPicker("source")},modifier=Modifier.testTag("catalogSourcePicker")) {
                            Text(if(s.catalogSourceKeys.isEmpty()) "全部来源" else "来源 · ${s.catalogSourceKeys.size}",maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=180.dp));Icon(Icons.Default.ArrowDropDown,null)
                        }
                    }
                    item {
                        GlassButton(shape=MaterialTheme.shapes.small,border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f)),colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.onSurface),onClick={openPicker("genre")},modifier=Modifier.testTag("catalogGenrePicker")) {
                            Text(s.categories.find { it.id==s.catalogCategory }?.name ?: if(s.catalogGenres.isEmpty()) "题材 · 不限" else "题材 · ${s.catalogGenres.size}");Icon(Icons.Default.ArrowDropDown,null)
                        }
                    }
                    }
                    DiscoverySortControl(catalogSort,{catalogSort=it},catalogVideos,false,compact=true)
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
                }
        }
        PullToRefreshBox(isRefreshing=s.catalogLoading,onRefresh={catalogSort=DiscoverySort.DEFAULT;model.shuffleCatalog();scope.launch { catalogState.scrollToItem(0) }},modifier=Modifier.weight(1f).testTag("catalogRefresh")) {
        LazyVerticalGrid(columns=GridCells.Adaptive(if(LocalDensity.current.fontScale>1.3f) 180.dp else if(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp>=600) 180.dp else 150.dp),modifier=Modifier.fillMaxSize().testTag("catalogList"),state=catalogState,contentPadding=PaddingValues(start=20.dp,end=20.dp,top=8.dp,bottom=16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(sources.isEmpty()) item(span={GridItemSpan(maxLineSpan)}) {
                EmptyPanel("从你的订阅开始","添加点播来源即可浏览分类。",R.drawable.ic_library)
                GlassButton(shape=MaterialTheme.shapes.medium,onClick=addSource) { Text("添加订阅") }
            }
            s.catalogSource?.let { source ->
                if(!s.catalogLoading && catalogVideos.isEmpty() && s.catalogError==null) item(span={GridItemSpan(maxLineSpan)}) {
                    Column(Modifier.padding(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(if(s.catalogMore) "还没有找到匹配影片" else "当前片库未找到匹配影片",style=MaterialTheme.typography.titleMedium)
                        Text(if(s.catalogMore) "已查找 ${s.catalogScanned} 页，可继续查找。" else if(sources.size>1) "可切换题材或片库；部分来源不提供题材信息。" else "可更换题材；部分来源不提供题材信息。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick={openPicker("genre")}) { Text("更换题材") }
                    }
                }
                items(catalogVideos,key={it.source.key+":"+it.id}) { video ->
                    PosterGridCard(video,model,Modifier.testTag("catalogVideo:${video.id}"),"catalog",probe=s.probes[video.probeKey()],showProbe=s.catalogQuery.isNotBlank()) { model.open(video) }
                }

                if(s.catalogLoading) item(span={GridItemSpan(maxLineSpan)}) { Row(verticalAlignment=Alignment.CenterVertically) {
                    LinearProgressIndicator(progress={ if(s.catalogTotal==0) 0f else s.catalogCompleted.toFloat()/s.catalogTotal },modifier=Modifier.weight(1f));TextButton(onClick=model::cancelCatalog) { Text("取消") }
                } }
                s.catalogError?.let { message -> item(span={GridItemSpan(maxLineSpan)}) {
                    Text(message)
                    TextButton(onClick={model.browseSources(s.catalogSourceKeys,s.catalogCategory,s.catalogQuery,more=s.catalogMore,genres=s.catalogGenres)}) { Text("重新加载") }
                } }
                if(s.catalogMore && !s.catalogLoading) item(span={GridItemSpan(maxLineSpan)}) {
                    GlassButton(shape=MaterialTheme.shapes.medium,onClick={model.browseSources(s.catalogSourceKeys,s.catalogCategory,s.catalogQuery,true,s.catalogGenres)},modifier=Modifier.fillMaxWidth().testTag("catalogMore")) {
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
@Composable private fun RecommendationList(s:UiState,model:AppModel,searchTitle:(String)->Unit,sort:DiscoverySort,onSort:(DiscoverySort)->Unit,onTop:()->Unit,categoryPicker:MutableState<Boolean>) {
    val videos=remember(s.recommendations,sort) { sort.apply(s.recommendations) }
    val grid=rememberLazyGridState()
    val scope=rememberCoroutineScope()
    val latest by rememberUpdatedState(s)
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(s.recommendLoading) { if(!s.recommendLoading) refreshing=false }
    ScrollToTopOnChange(s.recommendType,s.recommendTag,sort.name) { grid.scrollToItem(0) }
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
    var draftTag by rememberSaveable { mutableStateOf(s.recommendTag) }
    LaunchedEffect(categoryPicker.value) { if(categoryPicker.value) draftTag=s.recommendTag }
    val focus=androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    if(categoryPicker.value) HandleOnlySheet(onDismissRequest={categoryPicker.value=false}) {
        SheetSystemBars()
        Column(Modifier.fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false).testTag("recommendCategorySheet"),contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp)) {
                item { Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("推荐分类",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    TextButton(onClick={categoryPicker.value=false}) { Text("取消") }
                } }
                item { Row(verticalAlignment=Alignment.CenterVertically) {
                    listOf("tv" to "剧集","movie" to "电影").forEach { (type,label) -> ContentFilterChip(selected=s.recommendType==type,onClick={if(s.recommendType!=type) { draftTag="热门";model.recommend(type) }},modifier=Modifier.testTag("recommendType:$type"),label={Text(label)}) }
                    Spacer(Modifier.weight(1f));DiscoverySortControl(sort,onSort,s.recommendations,true,compact=true)
                    IconButton(onClick={refresh();categoryPicker.value=false},modifier=Modifier.size(48.dp)) { Icon(Icons.Default.Refresh,"刷新推荐") }
                } }
                item { FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Recommendations.tags(s.recommendType).forEach { tag ->
                        ContentFilterChip(selected=draftTag==tag,onClick={draftTag=tag},reserveSelectionSpace=true,
                            modifier=Modifier.testTag("recommendCategory:$tag"),label={Text(tag)})
                    }
                } }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={draftTag="热门"}) { Text("重置") }
                GlassButton(shape=MaterialTheme.shapes.medium,onClick={categoryPicker.value=false;model.recommend(tag=draftTag)},modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("applyRecommendCategory")) { Text("应用") }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
    PullToRefreshBox(isRefreshing=refreshing,onRefresh={refresh()},modifier=Modifier.weight(1f).testTag("recommendRefresh")) {
    LazyVerticalGrid(columns=GridCells.Adaptive(if(LocalDensity.current.fontScale>1.3f) 180.dp else if(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp>=600) 180.dp else 110.dp),state=grid,
        modifier=Modifier.fillMaxSize().testTag("recommendationGrid"),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=12.dp,bottom=16.dp),
        horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(!s.recommendLoading && s.recommendError==null && s.recommendations.isEmpty()) item(span={GridItemSpan(maxLineSpan)}) { Text("当前分类暂无推荐，请切换分类。") }
        videos.firstOrNull()?.let { featured ->
            item(key=featured.id,span={GridItemSpan(maxLineSpan)},contentType="featured") {
                FeaturedRecommendation(featured,model) { model.openRecommendation(featured) }
            }
            if(videos.size>1) item(key="recommend-heading",span={GridItemSpan(maxLineSpan)}) {
                Row(Modifier.fillMaxWidth().padding(top=12.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("为你推荐",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    Text(s.recommendTag,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(videos.drop(1),key={it.id},contentType={"recommendation"}) { video ->
            PosterGridCard(video,model,Modifier.testTag("recommendation:${video.id}"),"recommend") { model.openRecommendation(video) }
        }

        if(s.recommendLoading && !refreshing) item(span={GridItemSpan(maxLineSpan)}) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        s.recommendError?.let { error -> item(span={GridItemSpan(maxLineSpan)}) {
            Column { Text("推荐加载失败：$error")
                TextButton(onClick={model.recommend(more=s.recommendAppending)}) { Text("重试推荐") } }
        } }
        if(!s.recommendMore && !s.recommendLoading && videos.isNotEmpty()) item(span={GridItemSpan(maxLineSpan)}) { Text("已显示本次推荐",Modifier.fillMaxWidth().padding(12.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        if(s.recommendMore && !s.recommendLoading && s.recommendError==null) item(span={GridItemSpan(maxLineSpan)}) { GlassButton(shape=MaterialTheme.shapes.medium,onClick={model.recommend(more=true)}) { Text("更多推荐") } }
    }
    val showTop by remember { derivedStateOf { grid.firstVisibleItemIndex>3 } }
    BackToTop(showTop,Modifier.align(Alignment.BottomEnd)) { scope.launch { grid.animateScrollToItem(0);onTop() } }
    }
    }
}

@Composable internal fun BackToTop(visible:Boolean,modifier:Modifier=Modifier,onClick:()->Unit) {
    androidx.compose.animation.AnimatedVisibility(visible,modifier.padding(16.dp)) {
        GlassIconButton(onClick=onClick,modifier=Modifier.size(48.dp).testTag("backToTop")) {
            Icon(Icons.Default.KeyboardArrowUp,"回到顶部")
        }
    }
}

/** Artwork meets the outer clip; no inset surface or independently rounded poster. */
@Composable private fun DiscoveryCard(modifier:Modifier=Modifier,onClick:()->Unit,content:@Composable ColumnScope.()->Unit) {
    ContentCard(modifier=modifier,onClick=onClick,containerColor=MaterialTheme.colorScheme.background,content=content)
}

@Composable private fun RecommendationRating(video:Video,modifier:Modifier=Modifier,prefix:String="recommend") {
    val rating=video.rating?.toString()
    if(rating!=null) Surface(modifier=modifier.testTag("${prefix}Rating:${video.id}").semantics { contentDescription="${if(prefix=="recommend") "豆瓣" else "来源"}评分 $rating 分" },
        shape=RoundedCornerShape(50),color=Color(0xdd111113),contentColor=Color(0xffffc12b)) {
        Row(Modifier.padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Default.Star,null,Modifier.size(12.dp))
            Text(rating,style=MaterialTheme.typography.labelSmall)
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
            GlassMenu(expanded=expanded,onDismissRequest={expanded=false}) {
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
@Composable internal fun SheetSystemBars() {
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

@Composable private fun PosterGridCard(video:Video,model:AppModel,modifier:Modifier,prefix:String,probe:ProbeStatus?=null,showProbe:Boolean=false,onClick:()->Unit) {
    DiscoveryCard(onClick=onClick,modifier=modifier.fillMaxWidth()) {
        Box {
            Poster(video,model,Modifier.fillMaxWidth().aspectRatio(0.75f).testTag("${prefix}Poster:${video.id}"),shape=RectangleShape,containerColor=MaterialTheme.colorScheme.surfaceContainer,contentScale=ContentScale.Crop)
            RecommendationRating(video,Modifier.align(Alignment.TopEnd).padding(8.dp),prefix)
        }
        if(showProbe) {
            val status=probe
            MediaProbeIndicator(status)
        }
        CardTextSlot(video.title,modifier=Modifier.padding(horizontal=2.dp,vertical=8.dp).testTag("${prefix}Title:${video.id}"),style=MaterialTheme.typography.bodyMedium.copy(fontSize=14.sp,lineHeight=20.sp),color=MaterialTheme.colorScheme.onSurface,lines=1)
    }
}

/** The leading recommendation uses the same source-search action as every poster. */
@Composable private fun FeaturedRecommendation(video:Video,model:AppModel,onClick:()->Unit) {
    val background=MaterialTheme.colorScheme.background
    val wide=androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp>=600
    val height=if(wide) 340.dp else 320.dp
    Box(Modifier.fillMaxWidth().testTag("recommendation:${video.id}").clickable(onClick=onClick)) {
        Poster(video,model,Modifier.fillMaxWidth().height(height).testTag("recommendPoster:${video.id}"),shape=RectangleShape,contentScale=ContentScale.Crop,large=true)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(background.copy(alpha=.8f),Color.Transparent,background.copy(alpha=.12f),background))))
        Column(Modifier.fillMaxWidth().padding(start=4.dp,end=4.dp,top=height-108.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(video.title,Modifier.testTag("recommendTitle:${video.id}"),style=MaterialTheme.typography.headlineLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                Text(video.subtitle.ifBlank { "${if(wide) "发现好故事 · " else ""}精选${if(video.source.name.isNotBlank()) "推荐" else "影片"}" },Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                GlassButton(onClick=onClick,shape=MaterialTheme.shapes.small,contentPadding=PaddingValues(horizontal=18.dp,vertical=12.dp)) { Text("查看简介 ›") }
            }
        }
    }
}
