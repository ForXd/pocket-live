// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.staggeredgrid.*
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
    recommendations:MutableState<Boolean> = rememberSaveable { mutableStateOf(true) },catalogQuery:String=s.catalogQuery,liveTab:MutableState<Boolean> = rememberSaveable { mutableStateOf(false) },liveQuery:String="",onTop:()->Unit={},
    onOpenSearch:()->Unit={},showHeader:Boolean=true) {
    val sources = remember(s.saved.subscriptions, s.saved.disabled) { s.saved.enabledSources(SourceKind.VOD) }
    val recommendationFilters=rememberSaveable { mutableStateOf(false) }
    var recommendationSort by rememberSaveable { mutableStateOf(DiscoverySort.DEFAULT) }
    var catalogSort by rememberSaveable { mutableStateOf(DiscoverySort.DEFAULT) }
    val sortedCatalog=remember(s.catalogVideos,catalogSort) { catalogSort.apply(s.catalogVideos) }
    val catalogVideos=remember(sortedCatalog,s.probes,catalogSort) {
        if(catalogSort==DiscoverySort.DEFAULT || s.probes.isEmpty()) sortedCatalog
        else sortedCatalog.partition { s.probes[it.probeKey()]==ProbeStatus.READY }.let { (ready,remaining) -> ready+remaining }
    }
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
        LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false).testTag("catalogPicker"),contentPadding=PaddingValues(start=PageGutter,end=PageGutter,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Row(verticalAlignment=Alignment.CenterVertically) {
                Text(if(picker=="source") "选择片库" else "筛选题材",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                PocketClose { picker=null }
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
            Row(Modifier.fillMaxWidth().padding(horizontal=PageGutter,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                GlassTextButton(onClick={if(picker=="source") draftSources=emptyList() else { draftGenres=emptyList();draftCategory=null }},modifier=Modifier.testTag("clearCatalogFilters")) { Text("重置") }
                GlassButton(shape=MaterialTheme.shapes.medium,onClick={
                    if(picker=="source") model.browseSources(draftSources.toSet(),query=catalogQuery.trim(),genres=s.catalogGenres)
                    else model.browseSources(s.catalogSourceKeys,draftCategory,catalogQuery.trim(),genres=draftGenres.map { Genre.valueOf(it) }.toSet())
                    picker=null
                },modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("applyCatalogFilters")) { Text("应用") }
            }
        }
        }
    }
    val catalogState=rememberLazyStaggeredGridState()
    val scope=rememberCoroutineScope()
    val latest by rememberUpdatedState(s)
    ScrollToTopOnChange(s.catalogSourceKeys.sorted().toString(),s.catalogCategory.orEmpty(),s.catalogGenres.sortedBy { it.ordinal }.toString(),s.catalogQuery,catalogSort.name) { catalogState.scrollToItem(0) }
    var catalogDemand by remember(s.catalogSourceKeys,s.catalogCategory,s.catalogGenres,s.catalogQuery) { mutableIntStateOf(-1) }
    // Request each result boundary once; sparse pages remain manually resumable.
    LaunchedEffect(recommendations.value,liveTab.value,s.catalogVideos.size,s.catalogLoading,s.catalogSourceKeys,s.catalogCategory,s.catalogGenres,s.catalogQuery) {
        if(!recommendations.value && !liveTab.value && s.catalogVideos.isNotEmpty()) snapshotFlow {
            val layout=catalogState.layoutInfo
            catalogState.firstVisibleItemIndex>0 && layout.totalItemsCount>s.catalogVideos.size &&
                (layout.visibleItemsInfo.maxOfOrNull { it.index } ?: 0)>=layout.totalItemsCount-3
        }.distinctUntilChanged().collect { nearEnd ->
            val current=latest
            if(nearEnd && current.catalogMore && !current.catalogLoading && current.catalogError==null && catalogDemand!=current.catalogVideos.size) {
                catalogDemand=current.catalogVideos.size
                model.browseSources(current.catalogSourceKeys,current.catalogCategory,current.catalogQuery,true,current.catalogGenres)
            }
        }
    }
    fun selectedTabFromState()=if(liveTab.value) 2 else if(recommendations.value) 0 else 1
    val selectedTab=selectedTabFromState()
    val pager=rememberPagerState(initialPage=selectedTab,pageCount={3})
    val toolbarScroll=rememberSaveable(saver=HomeSearchScroll.Saver) { HomeSearchScroll() }
    val connection=rememberSearchScrollConnection(toolbarScroll,showHeader)
    var liveGroup by rememberSaveable(s.liveSourceKey) { mutableStateOf("") }
    if(showHeader) LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            if(page!=selectedTabFromState()) {
                liveTab.value=page==2
                recommendations.value=page==0
                toolbarScroll.reveal()
            }
        }
    }
    val revealTop={ toolbarScroll.reveal();onTop() }
    Column(Modifier.fillMaxSize().nestedScroll(connection)) {
        val header:@Composable ()->Unit = {
            Column(Modifier.fillMaxWidth()) {
                if(showHeader) Row(Modifier.fillMaxWidth().padding(horizontal=PageGutter).testTag("recommendToolbar"),
                    horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        SlidingTabs(selectedTab,3,Modifier.widthIn(max=if(LocalDensity.current.fontScale>1.3f) 236.dp else 198.dp)) {
                            listOf("推荐","分类","直播").forEachIndexed { index,label ->
                                DiscoveryTab(label,selectedTab==index,if(index==2) Modifier.testTag("liveTab") else Modifier) {
                                    scope.launch { pager.animateScrollToPage(index) }
                                }
                            }
                        }
                    }
                    if(selectedTab==0) GlassIconButton(onClick={recommendationFilters.value=true},modifier=Modifier.testTag("recommendCategoryPicker")) {
                        Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_sort),"筛选推荐")
                    }
                    GlassIconButton(onClick=onOpenSearch,modifier=Modifier.testTag("openSearch")) {
                        Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_search),"搜索")
                    }
                }
                if(selectedTab==2) LiveFilters(s,model,liveGroup) { liveGroup=it }
                if(selectedTab==1 && s.catalogSource!=null) {
                    CatalogFilters(s,model,sources,catalogQuery,catalogVideos,catalogSort,{catalogSort=it},::openPicker)
                }
            }
        }
        if(showHeader) CollapsingHomeSearch(toolbarScroll,statusBars=false,content=header) else header()
        val pageContent:@Composable (Int)->Unit = { page ->
            when(page) {
                0 -> RecommendationList(s,model,searchTitle,recommendationSort,{recommendationSort=it},revealTop,recommendationFilters)
                2 -> LiveScreen(s,model,liveQuery,liveGroup,addSource)
                else -> CatalogList(s,model,sources,catalogVideos,catalogState,addSource,::openPicker,{catalogSort=DiscoverySort.DEFAULT},revealTop)
            }
        }
        if(showHeader) HorizontalPager(state=pager,modifier=Modifier.fillMaxSize().testTag("discoveryPages"),pageContent={ pageContent(it) })
        else pageContent(selectedTab)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun CatalogFilters(s:UiState,model:AppModel,sources:List<Source>,catalogQuery:String,
    catalogVideos:List<Video>,catalogSort:DiscoverySort,onSort:(DiscoverySort)->Unit,openPicker:(String)->Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal=PageGutter,vertical=ActionGap).testTag("catalogToolbar")) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(ActionGap),verticalAlignment=Alignment.Top) {
            FlowRow(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(ActionGap),
                verticalArrangement=Arrangement.spacedBy(4.dp)) {
                FilterSelector(if(s.catalogSourceKeys.isEmpty()) "全部来源" else "来源 · ${s.catalogSourceKeys.size}",
                    {openPicker("source")},Modifier.testTag("catalogSourcePicker"))
                FilterSelector(s.categories.find { it.id==s.catalogCategory }?.name
                    ?: if(s.catalogGenres.isEmpty()) "题材 · 不限" else "题材 · ${s.catalogGenres.size}",
                    {openPicker("genre")},Modifier.testTag("catalogGenrePicker"))
            }
            DiscoverySortControl(catalogSort,onSort,catalogVideos,false,compact=true)
        }
        if(s.catalogSourceKeys.isNotEmpty() || s.catalogGenres.isNotEmpty() || s.catalogCategory!=null) {
            LazyRow(Modifier.fillMaxWidth().testTag("activeCatalogFilters"),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                items(sources.filter { it.key in s.catalogSourceKeys },key={it.key}) { selected ->
                    InputChip(selected=true,border=null,onClick={model.browseSources(s.catalogSourceKeys-selected.key,query=catalogQuery.trim(),genres=s.catalogGenres)},
                        label={Text(selected.name,maxLines=1,overflow=TextOverflow.Ellipsis)},trailingIcon={Icon(Icons.Default.Close,"取消${selected.name}",Modifier.size(16.dp))},
                        modifier=Modifier.widthIn(max=200.dp).testTag("activeSource:${selected.key}"))
                }
                items(s.catalogGenres.sortedBy { it.ordinal },key={it.name}) { genre ->
                    InputChip(selected=true,border=null,onClick={model.browseSources(s.catalogSourceKeys,query=catalogQuery.trim(),genres=s.catalogGenres-genre)},
                        label={Text(genre.label)},trailingIcon={Icon(Icons.Default.Close,"取消${genre.label}",Modifier.size(16.dp))},
                        modifier=Modifier.testTag("activeGenre:${genre.name}"))
                }
                s.categories.find { it.id==s.catalogCategory }?.let { category -> item {
                    InputChip(selected=true,border=null,onClick={model.browseSources(s.catalogSourceKeys,query=catalogQuery.trim())},label={Text(category.name)},
                        trailingIcon={Icon(Icons.Default.Close,"取消${category.name}",Modifier.size(16.dp))})
                } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CatalogList(s:UiState,model:AppModel,sources:List<Source>,catalogVideos:List<Video>,
    catalogState:LazyStaggeredGridState,addSource:()->Unit,openPicker:(String)->Unit,resetSort:()->Unit,onTop:()->Unit) {
    val scope=rememberCoroutineScope()
    val showTop by remember { derivedStateOf { catalogState.firstVisibleItemIndex>2 } }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(s.catalogLoading) { if(!s.catalogLoading) refreshing=false }
    PullToRefreshBox(isRefreshing=refreshing && s.catalogLoading,onRefresh={if(!s.catalogLoading && sources.isNotEmpty()) {
        refreshing=true;resetSort();model.shuffleCatalog();scope.launch { catalogState.scrollToItem(0) }
    }},modifier=Modifier.fillMaxSize().testTag("catalogRefresh")) {
        LazyVerticalStaggeredGrid(columns=StaggeredGridCells.Fixed(2),modifier=Modifier.fillMaxSize().testTag("catalogList"),state=catalogState,contentPadding=PaddingValues(start=PageGutter,end=PageGutter,top=8.dp,bottom=16.dp+LocalDockInset.current),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalItemSpacing=12.dp) {
            if(sources.isEmpty()) item(span=StaggeredGridItemSpan.FullLine) {
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    EmptyPanel("从你的订阅开始","添加点播来源即可浏览分类。",R.drawable.ic_library)
                    GlassButton(shape=MaterialTheme.shapes.medium,onClick=addSource) { Text("添加订阅") }
                }
            }
            if(s.catalogSource!=null) {
                if(!s.catalogLoading && catalogVideos.isEmpty() && s.catalogError==null) item(span=StaggeredGridItemSpan.FullLine) {
                    Column(Modifier.padding(vertical=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(if(s.catalogMore) "还没有找到匹配影片" else "当前片库未找到匹配影片",style=MaterialTheme.typography.titleMedium)
                        Text(if(s.catalogMore) "已查找 ${s.catalogScanned} 页，可继续查找。" else if(sources.size>1) "可切换题材或片库；部分来源不提供题材信息。" else "可更换题材；部分来源不提供题材信息。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        GlassTextButton(onClick={openPicker("genre")}) { Text("更换题材") }
                    }
                }
                if(catalogVideos.isNotEmpty()) item(span=StaggeredGridItemSpan.FullLine) { Text("全部作品",style=MaterialTheme.typography.titleLarge) }
                items(catalogVideos,key={it.source.key+":"+it.id}) { video ->
                    PosterGridCard(video,model,Modifier.testTag("catalogVideo:${video.id}"),"catalog",probe=s.probes[video.probeKey()],showProbe=s.catalogQuery.isNotBlank()) { model.open(video) }
                }

                if(s.catalogLoading || s.catalogError!=null || s.catalogMore) item(span=StaggeredGridItemSpan.FullLine) {
                    Column(Modifier.fillMaxWidth().padding(end=if(showTop) IconTarget+ActionGap else 0.dp),
                        verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        if(s.catalogLoading) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                if(!refreshing) LinearProgressIndicator(
                                    progress={ s.catalogCompleted.toFloat()/s.catalogTotal.coerceAtLeast(1) },
                                    modifier=Modifier.weight(1f))
                                GlassTextButton(onClick=model::cancelCatalog) { Text("取消") }
                            }
                        } else {
                            s.catalogError?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                            val refreshOnly=!s.catalogMore && catalogVideos.isNotEmpty()
                            val label=when {
                                refreshOnly -> "刷新分类"
                                s.catalogError!=null && catalogVideos.isEmpty() -> "重试"
                                s.catalogGenres.isNotEmpty() || s.catalogError!=null -> "继续查找"
                                else -> "加载更多"
                            }
                            GlassButton(onClick={
                                if(refreshOnly) model.shuffleCatalog()
                                else model.browseSources(s.catalogSourceKeys,s.catalogCategory,s.catalogQuery,more=s.catalogMore,genres=s.catalogGenres)
                            },modifier=Modifier.fillMaxWidth().testTag("catalogMore")) { Text(label) }
                        }
                    }
                }
            }
        }
        BackToTop(showTop,Modifier.align(Alignment.BottomEnd)) { scope.launch { catalogState.animateScrollToItem(0);onTop() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable private fun RecommendationList(s:UiState,model:AppModel,searchTitle:(String)->Unit,sort:DiscoverySort,onSort:(DiscoverySort)->Unit,onTop:()->Unit,categoryPicker:MutableState<Boolean>) {
    val videos=remember(s.recommendations,sort) { sort.apply(s.recommendations) }
    val grid=rememberLazyStaggeredGridState()
    val scope=rememberCoroutineScope()
    val latest by rememberUpdatedState(s)
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(s.recommendLoading) { if(!s.recommendLoading) refreshing=false }
    ScrollToTopOnChange(s.recommendType,s.recommendTag,sort.name) { grid.scrollToItem(0) }
    var recommendationDemand by remember(s.recommendType,s.recommendTag) { mutableIntStateOf(-1) }
    LaunchedEffect(s.recommendations.size,s.recommendLoading,s.recommendType,s.recommendTag) {
        if(s.recommendations.isNotEmpty()) snapshotFlow {
            grid.firstVisibleItemIndex>0 && (grid.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: 0)>=grid.layoutInfo.totalItemsCount-5
        }.distinctUntilChanged().collect { nearEnd ->
            val current=latest
            if(nearEnd && current.recommendMore && !current.recommendLoading && current.recommendError==null && recommendationDemand!=current.recommendations.size) {
                recommendationDemand=current.recommendations.size;model.recommend(more=true)
            }
        }
    }
    fun refresh() { if(!s.recommendLoading) { refreshing=true;recommendationDemand=-1;model.recommend() } }
    if(categoryPicker.value) HandleOnlySheet(onDismissRequest={categoryPicker.value=false}) {
        var draftType by rememberSaveable { mutableStateOf(s.recommendType) }
        var draftTag by rememberSaveable { mutableStateOf(s.recommendTag) }
        var draftSort by rememberSaveable { mutableStateOf(sort) }
        SheetSystemBars()
        Column(Modifier.fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxWidth().weight(1f,fill=false).testTag("recommendCategorySheet"),contentPadding=PaddingValues(horizontal=PageGutter,vertical=12.dp)) {
                item { Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("推荐分类",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    PocketClose { categoryPicker.value=false }
                } }
                item { Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("tv" to "剧集","movie" to "电影").forEach { (type,label) -> ContentFilterChip(selected=draftType==type,onClick={if(draftType!=type) { draftType=type;draftTag="热门" }},modifier=Modifier.testTag("recommendType:$type"),label={Text(label)}) }
                    Spacer(Modifier.weight(1f))
                    GlassIconButton(onClick={refresh();categoryPicker.value=false},modifier=Modifier.size(48.dp)) { Icon(Icons.Default.Refresh,"刷新推荐") }
                } }
                item { Text("排序",Modifier.padding(top=8.dp),style=MaterialTheme.typography.titleSmall) }
                item { FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    DiscoverySort.entries.filter { it!=DiscoverySort.UPDATED }.forEach { option ->
                        ContentFilterChip(selected=draftSort==option,onClick={draftSort=option},label={Text(option.label)})
                    }
                } }
                item { Text("题材",Modifier.padding(top=8.dp),style=MaterialTheme.typography.titleSmall) }
                item { FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Recommendations.tags(draftType).forEach { tag ->
                        ContentFilterChip(selected=draftTag==tag,onClick={draftTag=tag},reserveSelectionSpace=true,
                            modifier=Modifier.testTag("recommendCategory:$tag"),label={Text(tag)})
                    }
                } }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal=PageGutter,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                GlassTextButton(onClick={draftTag="热门";draftSort=DiscoverySort.DEFAULT}) { Text("重置") }
                GlassButton(shape=MaterialTheme.shapes.medium,onClick={categoryPicker.value=false;onSort(draftSort);if(draftType!=s.recommendType || draftTag!=s.recommendTag) model.recommend(draftType,draftTag)},modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("applyRecommendCategory")) { Text("应用") }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
    PullToRefreshBox(isRefreshing=refreshing,onRefresh={refresh()},modifier=Modifier.weight(1f).testTag("recommendRefresh")) {
    LazyVerticalStaggeredGrid(columns=StaggeredGridCells.Fixed(2),state=grid,
        modifier=Modifier.fillMaxSize().testTag("recommendationGrid"),contentPadding=PaddingValues(start=PageGutter,end=PageGutter,top=12.dp,bottom=16.dp+LocalDockInset.current),
        horizontalArrangement=Arrangement.spacedBy(12.dp),verticalItemSpacing=12.dp) {
        if(!s.recommendLoading && s.recommendError==null && s.recommendations.isEmpty()) item(span=StaggeredGridItemSpan.FullLine) { Text("当前分类暂无推荐，请切换分类。") }
        videos.firstOrNull()?.let { featured ->
            item(key=featured.id,span=StaggeredGridItemSpan.FullLine,contentType="featured") {
                FeaturedRecommendation(featured,model) { model.openRecommendation(featured) }
            }
            if(videos.size>1) item(key="recommend-heading",span=StaggeredGridItemSpan.FullLine) {
                Row(Modifier.fillMaxWidth().padding(top=12.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("为你推荐",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    Text(s.recommendTag,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(videos.drop(1),key={it.id},contentType={"recommendation"}) { video ->
            PosterGridCard(video,model,Modifier.testTag("recommendation:${video.id}"),"recommend") { model.openRecommendation(video) }
        }

        if(s.recommendLoading && !refreshing) item(span=StaggeredGridItemSpan.FullLine) { LoadingStatus(if(s.recommendAppending) "正在加载更多…" else "正在加载推荐…",Modifier.fillMaxWidth()) }
        s.recommendError?.let { error -> item(span=StaggeredGridItemSpan.FullLine) {
            Column { Text("推荐加载失败：$error")
                GlassTextButton(onClick={model.recommend(more=s.recommendAppending)}) { Text("重试推荐") } }
        } }
        if(!s.recommendMore && !s.recommendLoading && videos.isNotEmpty()) item(span=StaggeredGridItemSpan.FullLine) { Text("已显示本次推荐",Modifier.fillMaxWidth().padding(12.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        if(s.recommendMore && !s.recommendLoading && s.recommendError==null) item(span=StaggeredGridItemSpan.FullLine) { GlassButton(shape=MaterialTheme.shapes.medium,onClick={model.recommend(more=true)}) { Text("更多推荐") } }
    }
    val showTop by remember { derivedStateOf { grid.firstVisibleItemIndex>3 } }
    BackToTop(showTop,Modifier.align(Alignment.BottomEnd)) { scope.launch { grid.animateScrollToItem(0);onTop() } }
    }
    }
}

@Composable internal fun BackToTop(visible:Boolean,modifier:Modifier=Modifier,onClick:()->Unit) {
    androidx.compose.animation.AnimatedVisibility(visible,modifier.padding(bottom=LocalDockInset.current).padding(16.dp)) {
        GlassIconButton(onClick=onClick,modifier=Modifier.size(48.dp).testTag("backToTop")) {
            Icon(Icons.Default.KeyboardArrowUp,"回到顶部")
        }
    }
}

/** Artwork meets the outer clip; no inset surface or independently rounded poster. */
@Composable private fun RowScope.DiscoveryTab(label:String,selected:Boolean,modifier:Modifier=Modifier,onClick:()->Unit) {
    Tab(selected=selected,onClick=onClick,modifier=modifier.weight(1f).heightIn(min=IconTarget),
        selectedContentColor=MaterialTheme.colorScheme.primary,unselectedContentColor=MaterialTheme.colorScheme.onSurfaceVariant) {
        Text(label,style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}

@Composable private fun DiscoveryCard(modifier:Modifier=Modifier,onClick:()->Unit,content:@Composable ColumnScope.()->Unit) {
    val interaction=remember { MutableInteractionSource() }
    Card(onClick=onClick,modifier=modifier.pressFeedback(interaction),interactionSource=interaction,
        shape=MaterialTheme.shapes.large,
        colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow),
        elevation=CardDefaults.cardElevation(defaultElevation=3.dp,pressedElevation=1.dp),content=content)
}

@Composable internal fun RecommendationRating(video:Video,modifier:Modifier=Modifier,prefix:String="recommend") {
    val rating=video.rating?.toString()
    if(rating!=null) Surface(modifier=modifier.testTag("${prefix}Rating:${video.id}").semantics { contentDescription="${if(prefix=="recommend") "豆瓣" else "来源"}评分 $rating 分" },
        shape=RoundedCornerShape(50),color=Color(0xdd111113),contentColor=Color.White) {
        Row(Modifier.padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Default.Star,null,Modifier.size(12.dp),tint=Color(0xffffc12b))
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
            if(compact) GlassIconButton(onClick={expanded=true},modifier=Modifier.size(48.dp).testTag("discoverySort")) { Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_sort),"排序：${selected.label}") }
            else GlassTextButton(onClick={expanded=true},modifier=Modifier.heightIn(min=48.dp).testTag("discoverySort")) { Text("排序：${selected.label}");Icon(Icons.Default.ArrowDropDown,null) }
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
    val anchor = rememberPosterAnchor(video, model)
    DiscoveryCard(onClick={ anchor.open(video, onClick) },modifier=modifier.fillMaxWidth()) {
        Box(anchor.modifier) {
            Poster(video,model,Modifier.fillMaxWidth().aspectRatio(2f / 3f).testTag("${prefix}Poster:${video.id}"),shape=RectangleShape,containerColor=MaterialTheme.colorScheme.surfaceContainer,contentScale=ContentScale.Crop)
            RecommendationRating(video,Modifier.align(Alignment.TopEnd).padding(8.dp),prefix)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            if(showProbe) MediaProbeIndicator(probe)
            Text(video.title,modifier=Modifier.testTag("${prefix}Title:${video.id}"),style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.onSurface,maxLines=2,overflow=TextOverflow.Ellipsis)
            val metadata=listOf(video.typeName,video.subtitle).filter { it.isNotBlank() }.joinToString(" · ")
            if(metadata.isNotBlank()) Text(metadata,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}

/** Artwork-backed glass information plate; text is never part of the blurred layer. */
@Composable private fun FeaturedRecommendation(video:Video,model:AppModel,onClick:()->Unit) {
    val anchor = rememberPosterAnchor(video, model, large=true)
    DiscoveryCard(modifier=Modifier.fillMaxWidth().testTag("recommendation:${video.id}"),onClick={ anchor.open(video, onClick) }) {
        Box(anchor.modifier) {
        Poster(video,model,Modifier.fillMaxWidth().aspectRatio(16f / 9f).testTag("recommendPoster:${video.id}"),shape=RectangleShape,contentScale=ContentScale.Crop,large=true)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.12f),Color.Black.copy(alpha=.8f)))))
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(20.dp),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                RecommendationRating(video)
                Text(video.title,Modifier.testTag("recommendTitle:${video.id}"),style=MaterialTheme.typography.headlineLarge,color=Color.White,maxLines=2,overflow=TextOverflow.Ellipsis)

            }
            Surface(shape=androidx.compose.foundation.shape.CircleShape,color=Color.Black.copy(alpha=.42f),contentColor=Color.White) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,"查看简介",Modifier.size(48.dp).padding(12.dp))
            }
        }
        }
    }
}
