// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow

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
        Row(Modifier.padding(horizontal=20.dp), horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            FilterChip(selected=recommendations,onClick={recommendations=true},label={Text("剧集推荐")})
            FilterChip(selected=!recommendations,onClick={recommendations=false},label={Text("订阅分类")})
        }
        if(recommendations) { RecommendationList(s,model,searchTitle); return@Column }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Text("发现与你的分类",style=MaterialTheme.typography.headlineMedium)
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
                Card(onClick={model.open(video)},modifier=Modifier.fillMaxWidth()) {
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

@Composable private fun RecommendationList(s:UiState,model:AppModel,searchTitle:(String)->Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Text("发现好故事",style=MaterialTheme.typography.headlineMedium)
            Text("豆瓣公开推荐 · 点击封面搜索你的订阅来源",style=MaterialTheme.typography.bodySmall)
        }
        item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            FilterChip(selected=s.recommendType=="tv",onClick={model.recommend("tv")},label={Text("剧集")})
            FilterChip(selected=s.recommendType=="movie",onClick={model.recommend("movie")},label={Text("电影")})
            TextButton(onClick={model.recommend()}) { Text("刷新推荐") }
        } }
        item { LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            items(Recommendations.tags(s.recommendType)) { tag -> FilterChip(selected=s.recommendTag==tag,onClick={model.recommend(tag=tag)},label={Text(tag)}) }
        } }
        if(s.recommendLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        s.recommendError?.let { error -> item {
            Text("推荐暂时无法加载：$error。可以重试，或切换到订阅分类。")
            TextButton(onClick={model.recommend()}) { Text("重试推荐") }
        } }
        if(!s.recommendLoading && s.recommendError==null && s.recommendations.isEmpty()) item { Text("当前分类暂无推荐，请切换分类。") }
        items(s.recommendations.chunked(2), key={it.first().id}) { row ->
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                row.forEach { video ->
                    Card(onClick={searchTitle(video.title)},modifier=Modifier.weight(1f)) {
                        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Poster(video,model,Modifier.fillMaxWidth().aspectRatio(0.7f))
                            Text(video.title,style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                            if(video.subtitle.isNotBlank()) Text(video.subtitle,style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if(row.size==1) Spacer(Modifier.weight(1f))
            }
        }
        if(s.recommendMore && !s.recommendLoading) item { OutlinedButton(onClick={model.recommend(more=true)}) { Text("更多推荐") } }
    }
}
