// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DiscoverScreen(s:UiState,model:AppModel,addSource:()->Unit,searchTitle:(String)->Unit) {
    val sources=s.saved.subscriptions.flatMap { it.sources }.distinctBy { it.key }
        .filter { it.kind==SourceKind.VOD && it.key !in s.saved.disabled }
    var recommendations by remember { mutableStateOf(true) }
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle,model) {
        val observer=androidx.lifecycle.LifecycleEventObserver { _,event ->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_START) model.discoveryForeground()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(sources.map { it.key }) { if(sources.isNotEmpty() && s.catalogSource==null) model.browse(sources.first()) }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex=if(recommendations) 0 else 1,containerColor=MaterialTheme.colorScheme.background) {
            Tab(selected=recommendations,onClick={recommendations=true},text={Text("剧集推荐")})
            Tab(selected=!recommendations,onClick={recommendations=false},text={Text("订阅分类")})
        }
        if(recommendations) { RecommendationList(s,model,searchTitle); return@Column }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Text("发现与你的分类",style=MaterialTheme.typography.titleLarge)
            Text("内容来自你启用的订阅 · 按来源返回顺序展示",style=MaterialTheme.typography.bodySmall)
        }
        if(sources.isEmpty()) item {
            EmptyPanel("从你的订阅开始","添加点播来源后查看最新内容与分类。应用不内置播放源或榜单。",R.drawable.ic_library)
            Button(onClick=addSource) { Text("添加订阅") }
        }
        item { LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            items(sources,key={it.key}) { source -> FilterChip(selected=s.catalogSource?.key==source.key,
                onClick={query="";model.browse(source)},label={Text(source.name)}) }
        } }
        s.catalogSource?.let { source ->
            item { LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected=s.catalogCategory==null,onClick={model.browse(source,query=query)},label={Text("全部")}) }
                items(s.categories,key={it.id}) { category -> FilterChip(selected=s.catalogCategory==category.id,
                    onClick={model.browse(source,category.id,query)},label={Text(category.name)}) }
            } }
            item {
                OutlinedTextField(query,{query=it.take(100)},label={Text("在当前来源和分类中搜索")},singleLine=true,modifier=Modifier.fillMaxWidth())
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick={model.browse(source,s.catalogCategory,query)}) { Text("分类搜索") }
                    if(s.catalogLoading) TextButton(onClick=model::cancelCatalog) { Text("取消") }
                }
            }
            if(s.catalogLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            s.catalogError?.let { message -> item {
                Text(message)
                TextButton(onClick={model.browse(source,s.catalogCategory,query)}) { Text("重新加载") }
            } }
            if(!s.catalogLoading && s.catalogVideos.isEmpty() && s.catalogError==null) item { Text("该来源未返回内容或不支持此分类接口。仍可使用全源搜索。") }
            items(s.catalogVideos,key={it.source.key+":"+it.id}) { video ->
                Card(onClick={model.open(video)},modifier=Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically) {
                        Poster(video,model)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(video.title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                            Text(video.subtitle,style=MaterialTheme.typography.bodySmall)
                            Text("查看剧集 →",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium)
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
@Composable private fun RecommendationList(s:UiState,model:AppModel,searchTitle:(String)->Unit) {
    LazyVerticalGrid(columns=GridCells.Adaptive(if(LocalDensity.current.fontScale>1.3f) 176.dp else 140.dp),
        modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),
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
        item(span={GridItemSpan(maxLineSpan)}) { LazyRow(modifier=Modifier.testTag("recommendFilters"),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            item { SingleChoiceSegmentedButtonRow {
                listOf("tv" to "剧集","movie" to "电影").forEachIndexed { index,(type,label) ->
                    SegmentedButton(selected=s.recommendType==type,onClick={model.recommend(type)},shape=SegmentedButtonDefaults.itemShape(index,2)) { Text(label) }
                }
            } }
            items(Recommendations.tags(s.recommendType)) { tag -> FilterChip(selected=s.recommendTag==tag,onClick={model.recommend(tag=tag)},label={Text(tag)}) }
        } }
        if(s.recommendLoading) item(span={GridItemSpan(maxLineSpan)}) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        s.recommendError?.let { error -> item(span={GridItemSpan(maxLineSpan)}) {
            Column { Text("推荐暂时无法加载：$error。可以重试，或切换到订阅分类。")
                TextButton(onClick={model.recommend()}) { Text("重试推荐") } }
        } }
        if(!s.recommendLoading && s.recommendError==null && s.recommendations.isEmpty()) item(span={GridItemSpan(maxLineSpan)}) { Text("当前分类暂无推荐，请切换分类。") }
        s.recommendations.firstOrNull()?.let { featured ->
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
        items(s.recommendations.drop(1),key={it.id}) { video ->
            Column(Modifier.clip(MaterialTheme.shapes.small).clickable { searchTitle(video.title) },verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Poster(video,model,Modifier.fillMaxWidth().aspectRatio(2f/3f))
                Text(video.title,style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                if(video.subtitle.isNotBlank()) Text(video.subtitle,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(s.recommendMore && !s.recommendLoading) item(span={GridItemSpan(maxLineSpan)}) { OutlinedButton(onClick={model.recommend(more=true)}) { Text("更多推荐") } }
    }
}

/** Artwork remains decorative; opaque scrims keep labels legible for arbitrary user posters. */
@Composable private fun FeaturedRecommendation(video:Video,model:AppModel,open:()->Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(Color(0xff151b1c)).clickable(onClick=open)) {
        val wide=maxWidth>=600.dp
        val tallText=LocalDensity.current.fontScale>1.3f
        val imageWidth=if(wide) 330.dp else 260.dp
        val blendStart=((maxWidth-imageWidth)/maxWidth+.08f).coerceIn(0f,.85f)
        Box(Modifier.fillMaxWidth().heightIn(min=if(wide) 360.dp else if(tallText) 480.dp else 390.dp)) {
            Box(Modifier.matchParentSize()) {
                Poster(video,model,Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(imageWidth),shape=androidx.compose.ui.graphics.RectangleShape,containerColor=Color(0xff28332f))
            }
            Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to Color(0xff151b1c),blendStart to Color(0xff151b1c),1f to Color(0xff151b1c).copy(alpha=.08f))))
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color(0xff151b1c).copy(alpha=.3f),Color(0xff151b1c)))))
            Column(Modifier.align(Alignment.BottomStart).padding(24.dp).widthIn(max=420.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("本期推荐",style=MaterialTheme.typography.labelLarge,color=Color(0xffa9decf))
                Text(video.title,style=MaterialTheme.typography.headlineLarge,color=Color.White,maxLines=3,overflow=TextOverflow.Ellipsis)
                if(video.subtitle.isNotBlank()) Text(video.subtitle,style=MaterialTheme.typography.bodyMedium,color=Color(0xffd6dfdd))
                Button(onClick=open,colors=ButtonDefaults.buttonColors(containerColor=Color(0xffdef7ed),contentColor=Color(0xff15382e)),modifier=Modifier.padding(top=4.dp).heightIn(min=48.dp)) { Text("搜索此片") }
                Text("在你的订阅中查找播放资源",style=MaterialTheme.typography.labelSmall,color=Color(0xffb7c3bf))
            }
        }
    }
}
