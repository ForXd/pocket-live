// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.BorderStroke
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DiscoverScreen(s:UiState,model:AppModel,addSource:()->Unit,searchTitle:(String)->Unit) {
    val sources=s.saved.subscriptions.flatMap { it.sources }.distinctBy { it.key }
        .filter { it.kind==SourceKind.VOD && it.key !in s.saved.disabled }
    var recommendationSort by rememberSaveable { mutableStateOf(DiscoverySort.DEFAULT) }
    var catalogSort by rememberSaveable { mutableStateOf(DiscoverySort.DEFAULT) }
    val catalogVideos=remember(s.catalogVideos,catalogSort) { catalogSort.apply(s.catalogVideos) }
    var recommendations by rememberSaveable { mutableStateOf(true) }
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle,model) {
        val observer=androidx.lifecycle.LifecycleEventObserver { _,event ->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_START) model.discoveryForeground()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(sources.map { it.key }) { if(sources.isNotEmpty() && s.catalogSource==null) model.browse(sources.first()) }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex=if(recommendations) 0 else 1,containerColor=MaterialTheme.colorScheme.background) {
            Tab(selected=recommendations,onClick={recommendations=true},text={Text("剧集推荐")})
            Tab(selected=!recommendations,onClick={recommendations=false},text={Text("订阅分类")})
        }
        if(recommendations) { RecommendationList(s,model,searchTitle,recommendationSort) { recommendationSort=it }; return@Column }
    LazyColumn(Modifier.fillMaxSize().testTag("catalogList"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Text("发现与你的分类",style=MaterialTheme.typography.titleLarge)
            Text("内容来自你启用的订阅",style=MaterialTheme.typography.bodySmall)
        }
        if(sources.isEmpty()) item {
            EmptyPanel("从你的订阅开始","添加点播来源后查看最新内容与分类。应用不内置播放源或榜单。",R.drawable.ic_library)
            Button(onClick=addSource) { Text("添加订阅") }
        }
        item { LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            items(sources,key={it.key}) { source -> ContentFilterChip(selected=s.catalogSource?.key==source.key,
                onClick={query="";model.browse(source)},label={Text(source.name)}) }
        } }
        s.catalogSource?.let { source ->
            item { LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                item { ContentFilterChip(selected=s.catalogCategory==null,onClick={model.browse(source,query=query)},label={Text("全部")}) }
                items(s.categories,key={it.id}) { category -> ContentFilterChip(selected=s.catalogCategory==category.id,
                    onClick={model.browse(source,category.id,query)},label={Text(category.name)}) }
            } }
            item {
                OutlinedTextField(query,{query=it.take(100)},label={Text("在当前来源和分类中搜索")},singleLine=true,modifier=Modifier.fillMaxWidth())
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick={model.browse(source,s.catalogCategory,query)}) { Text("分类搜索") }
                    if(s.catalogLoading) TextButton(onClick=model::cancelCatalog) { Text("取消") }
                }
            }
            item { DiscoverySortControl(catalogSort,{catalogSort=it},s.catalogVideos,false) }
            if(s.catalogLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            s.catalogError?.let { message -> item {
                Text(message)
                TextButton(onClick={model.browse(source,s.catalogCategory,query)}) { Text("重新加载") }
            } }
            if(!s.catalogLoading && s.catalogVideos.isEmpty() && s.catalogError==null) item { Text("该来源未返回内容或不支持此分类接口。仍可使用全源搜索。") }
            items(catalogVideos,key={it.source.key+":"+it.id}) { video ->
                DiscoveryCard(onClick={model.open(video)},modifier=Modifier.fillMaxWidth().animateItem().testTag("catalogVideo:${video.id}")) {
                    Row(horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.Top) {
                        Poster(video,model,Modifier.size(88.dp,132.dp),shape=RectangleShape,containerColor=DiscoverySurface)
                        Column(Modifier.weight(1f).padding(top=12.dp,end=12.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            CardTextSlot(video.title,style=MaterialTheme.typography.titleMedium)
                            CardTextSlot(video.subtitle.ifBlank { "暂无剧集说明" },style=MaterialTheme.typography.bodySmall)
                            if(video.rating!=null) Text("来源评分 ${video.rating}",style=MaterialTheme.typography.labelSmall,color=DiscoveryMuted)
                            video.updatedAt?.let { Text("更新 ${updatedDate(it)}",style=MaterialTheme.typography.labelSmall,color=DiscoveryMuted) }
                            Text("查看剧集 →",color=DiscoveryAccent,style=MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            if(s.catalogMore && !s.catalogLoading) item {
                OutlinedButton(onClick={model.browse(source,s.catalogCategory,s.catalogQuery,true)}) { Text("加载下一页 · ${s.catalogPage}/${Catalog.MAX_PAGES}") }
            }
        }
    }
}

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun RecommendationList(s:UiState,model:AppModel,searchTitle:(String)->Unit,sort:DiscoverySort,onSort:(DiscoverySort)->Unit) {
    val videos=remember(s.recommendations,sort) { sort.apply(s.recommendations) }
    val recent=remember(s.saved.history) { HistoryGroups.group(s.saved.history).take(5) }
    LazyVerticalGrid(columns=GridCells.Adaptive(if(LocalDensity.current.fontScale>1.3f) 176.dp else 140.dp),
        modifier=Modifier.fillMaxSize().testTag("recommendationGrid"),contentPadding=PaddingValues(20.dp),
        horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item(span={GridItemSpan(maxLineSpan)}) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("发现好故事",style=MaterialTheme.typography.headlineSmall)
                    Text("豆瓣推荐 · 在你的订阅中搜索",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick={model.recommend()},modifier=Modifier.size(48.dp)) { Icon(Icons.Default.Refresh,"刷新推荐") }
            }
        }
        if(recent.isNotEmpty()) item(span={GridItemSpan(maxLineSpan)},key="continue-watching") {
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("继续观看",style=MaterialTheme.typography.titleMedium)
                BoxWithConstraints {
                    val cardWidth=minOf(maxWidth,360.dp)
                    LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.testTag("continueWatching")) {
                        items(recent,key={it.key.stableKey}) { group ->
                            val entry=group.latest
                            DiscoveryCard(onClick={model.resume(entry)},modifier=Modifier.width(cardWidth).testTag("discoverContinue:${group.key.stableKey}")) {
                                Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Poster(entry.playback.video,model,Modifier.size(80.dp,120.dp),shape=RectangleShape,containerColor=DiscoverySurface)
                                    Column(Modifier.weight(1f).padding(vertical=12.dp).padding(end=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                        Text(entry.playback.video.title,style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                                        Text(entry.playback.episode.name,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                                        Text(HistoryGroups.progress(entry),style=MaterialTheme.typography.labelSmall,color=DiscoveryMuted)
                                        Text(if(entry.playback.video.source.kind==SourceKind.LIVE) "打开直播" else "继续播放",style=MaterialTheme.typography.labelLarge,color=DiscoveryAccent)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item(span={GridItemSpan(maxLineSpan)}) { LazyRow(modifier=Modifier.testTag("recommendFilters"),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            item { SingleChoiceSegmentedButtonRow {
                listOf("tv" to "剧集","movie" to "电影").forEachIndexed { index,(type,label) ->
                    SegmentedButton(selected=s.recommendType==type,onClick={model.recommend(type)},shape=SegmentedButtonDefaults.itemShape(index,2)) { Text(label) }
                }
            } }
            items(Recommendations.tags(s.recommendType)) { tag -> ContentFilterChip(selected=s.recommendTag==tag,onClick={model.recommend(tag=tag)},label={Text(tag)}) }
        } }
        item(span={GridItemSpan(maxLineSpan)}) { DiscoverySortControl(sort,onSort,s.recommendations,true) }
        if(s.recommendLoading) item(span={GridItemSpan(maxLineSpan)}) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        s.recommendError?.let { error -> item(span={GridItemSpan(maxLineSpan)}) {
            Column { Text("推荐暂时无法加载：$error。可以重试，或切换到订阅分类。")
                TextButton(onClick={model.recommend()}) { Text("重试推荐") } }
        } }
        if(!s.recommendLoading && s.recommendError==null && s.recommendations.isEmpty()) item(span={GridItemSpan(maxLineSpan)}) { Text("当前分类暂无推荐，请切换分类。") }
        videos.firstOrNull()?.let { featured ->
            item(span={GridItemSpan(maxLineSpan)}) {
                FeaturedRecommendation(featured,model) { searchTitle(featured.title) }
            }
        }
        if(s.recommendations.size>1) item(span={GridItemSpan(maxLineSpan)}) {
            Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text("值得一看",style=MaterialTheme.typography.titleLarge)
                Text(s.recommendTag,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(videos.drop(1),key={it.id},contentType={"recommendation"}) { video ->
            DiscoveryCard(onClick={searchTitle(video.title)},modifier=Modifier.fillMaxWidth().animateItem().testTag("recommendation:${video.id}")) {
                BoxWithConstraints {
                    val artwork:@Composable (Modifier)->Unit = { size ->
                        Box {
                            Poster(video,model,size.testTag("recommendPoster:${video.id}"),shape=RectangleShape,containerColor=DiscoverySurface)
                            RecommendationRating(video,Modifier.align(Alignment.TopEnd).padding(8.dp))
                        }
                    }
                    val title:@Composable ()->Unit = {
                        CardTextSlot(video.title,modifier=Modifier.padding(horizontal=12.dp,vertical=10.dp).testTag("recommendTitle:${video.id}"),style=MaterialTheme.typography.titleSmall,color=DiscoveryText)
                    }
                    if(maxWidth>=240.dp) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            artwork(Modifier.size(112.dp,168.dp))
                            Box(Modifier.weight(1f)) { title() }
                        }
                    } else {
                        Column {
                            artwork(Modifier.fillMaxWidth().aspectRatio(2f/3f))
                            title()
                        }
                    }
                }
            }
        }

        if(s.recommendMore && !s.recommendLoading) item(span={GridItemSpan(maxLineSpan)}) { OutlinedButton(onClick={model.recommend(more=true)}) { Text("更多推荐") } }
    }
}

private val DiscoverySurface=Color(0xff0e1621)
private val DiscoveryText=Color(0xffe5edf6)
private val DiscoveryMuted=Color(0xffaab8c8)
private val DiscoveryAccent=Color(0xff78d8c6)

/** Artwork meets the outer clip; no inset surface or independently rounded poster. */
@Composable private fun DiscoveryCard(modifier:Modifier=Modifier,onClick:()->Unit,content:@Composable ColumnScope.()->Unit) {
    val interactions=remember { MutableInteractionSource() }
    Card(onClick=onClick,modifier=modifier.pressFeedback(interactions),interactionSource=interactions,shape=RoundedCornerShape(12.dp),
        border=BorderStroke(1.dp,Color(0xff26364a)),
        colors=CardDefaults.cardColors(containerColor=DiscoverySurface,contentColor=DiscoveryText),content=content)
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

@Composable private fun FeaturedRecommendation(video:Video,model:AppModel,open:()->Unit) {
    DiscoveryCard(modifier=Modifier.fillMaxWidth().testTag("featured:${video.id}"),onClick=open) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Box {
                Poster(video,model,Modifier.size(112.dp,168.dp),shape=RectangleShape,containerColor=DiscoverySurface)
                RecommendationRating(video,Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            Column(Modifier.weight(1f).padding(vertical=16.dp).padding(end=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("精选内容",style=MaterialTheme.typography.labelLarge,color=DiscoveryAccent)
                Text(video.title,style=MaterialTheme.typography.titleLarge,maxLines=3,overflow=TextOverflow.Ellipsis)
                Text("在你的订阅中查找",style=MaterialTheme.typography.bodySmall,color=DiscoveryMuted)
                FilledTonalButton(onClick=open,modifier=Modifier.heightIn(min=48.dp),colors=ButtonDefaults.filledTonalButtonColors(containerColor=Color(0xff24483f),contentColor=DiscoveryText)) { Text("搜索此片") }
            }
        }
    }
}

@Composable private fun DiscoverySortControl(selected:DiscoverySort,onSelect:(DiscoverySort)->Unit,videos:List<Video>,recommendation:Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Box {
            OutlinedButton(onClick={expanded=true},modifier=Modifier.heightIn(min=48.dp).testTag("discoverySort")) { Text("排序 · ${selected.label}") }
            DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                DiscoverySort.entries.forEach { option ->
                    DropdownMenuItem(text={Text(option.label)},onClick={onSelect(option);expanded=false},
                        enabled=option!=DiscoverySort.UPDATED || !recommendation,
                        trailingIcon={if(option==selected) Text("已选")})
                }
            }
        }
        Text(when {
            recommendation -> "仅排序已加载的 ${videos.size} 部 · 豆瓣未提供更新时间"
            selected==DiscoverySort.RATING -> "仅排序已加载的 ${videos.size} 部 · 无评分排在最后"
            selected==DiscoverySort.UPDATED -> "仅排序已加载的 ${videos.size} 部 · 无更新时间排在最后"
            else -> "仅排序已加载的 ${videos.size} 部 · 加载下一页后重新排序"
        },style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
