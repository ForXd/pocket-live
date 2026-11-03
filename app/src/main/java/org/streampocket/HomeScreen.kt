// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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

/** Outside the scroll container so search is always reachable above recommendations/results. */
@Composable fun HomeSearchBar(query:String,onQuery:(String)->Unit,searchMode:Boolean,submit:()->Unit,clear:()->Unit) {
    Surface(color=MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxWidth().statusBarsPadding(),contentAlignment=Alignment.Center) {
            TextField(query,onQuery,modifier=Modifier.widthIn(max=1100.dp).fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp)
                .testTag("searchInput").semantics { contentDescription="影片或频道名称" },singleLine=true,
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={if(query.isNotBlank()) submit()}),
                placeholder={Text("搜索影片、剧集或频道")},shape=RoundedCornerShape(28.dp),
                colors=TextFieldDefaults.colors(focusedContainerColor=MaterialTheme.colorScheme.surfaceContainer,unfocusedContainerColor=MaterialTheme.colorScheme.surfaceContainer,
                    focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent),
                leadingIcon={Icon(Icons.Default.Search,null)},trailingIcon={
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        AnimatedVisibility(query.isNotEmpty() || searchMode,enter=fadeIn(tween(120))+expandHorizontally(tween(180)),exit=fadeOut(tween(90))+shrinkHorizontally(tween(150))) {
                            IconButton(onClick=clear,modifier=Modifier.size(48.dp)) { Icon(Icons.Default.Close,"清空并返回推荐") }
                        }
                        FilledIconButton(onClick=submit,enabled=query.isNotBlank(),modifier=Modifier.padding(end=4.dp).size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowForward,"开始搜索") }
                    }
                })
        }
    }
}

@Composable fun SearchResultsScreen(s:UiState,model:AppModel,query:String,compact:Boolean,back:()->Unit,subscriptions:()->Unit) {
    val progress by animateFloatAsState(if(s.total==0) 0f else s.completed.toFloat()/s.total,tween(180),label="searchProgress")
    LazyVerticalGrid(columns=GridCells.Adaptive(320.dp),contentPadding=PaddingValues(20.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.fillMaxSize().testTag("searchResults").pointerInput(model) {
            awaitPointerEventScope { while(true) { if(awaitPointerEvent().type==PointerEventType.Press) model.pinSearchOrder() } }
        }) {
        item(span={GridItemSpan(maxLineSpan)}) {
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("搜索结果",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    TextButton(onClick=back) { Text("返回推荐") }
                }
                Text("“$query” · ${s.results.size} 条结果",style=MaterialTheme.typography.bodyMedium)
                if(s.searching) {
                    LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth())
                    TextButton(onClick=model::cancelSearch) { Text("取消搜索") }
                }
                if (s.total > 0) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val failureHint = if (s.failures > 0) " · ${s.failures} 个失败" else ""
                    Text("已完成 ${s.completed}/${s.total} 个源$failureHint", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s.results.isNotEmpty()) TextButton(onClick = model::sortByQuality) { Text("高清优先 ↓") }
                }
                if (s.results.size == QualityRules.RESULT_LIMIT) Text("已显示前 1000 条，可细化关键词", style = MaterialTheme.typography.bodySmall)
            }
        }
        if(s.results.isEmpty()) item(span={GridItemSpan(maxLineSpan)}) {
            Column {
                if(!compact) EmptyPanel(if(s.searching) "正在寻找内容" else if(s.total == 0) "还没有可搜索的来源" else "暂未找到结果",
                    if(s.searching) "结果会陆续显示，你可以随时取消。" else if(s.total == 0) "添加订阅或启用来源后，再搜索这部影片。" else "试试其他关键词，或检查已启用的订阅来源。",R.drawable.ic_search)
                if (compact && !s.searching) Text(if (s.total == 0) "请先添加或启用订阅来源" else "暂无结果，试试其他关键词")
                if(!s.searching) OutlinedButton(onClick=subscriptions) { Text("管理订阅") }
            }
        }
        items(s.results,key={it.source.key+":"+it.id}) { video ->
            ContentCard(onClick={model.open(video)},modifier=Modifier.fillMaxWidth().animateItem(fadeInSpec=tween(140),placementSpec=null,fadeOutSpec=null)) {
                Row(Modifier.padding(16.dp),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                    Poster(video,model,Modifier.size(88.dp,132.dp))
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        CardTextSlot(video.title,style=MaterialTheme.typography.titleMedium)
                        CardTextSlot(listOf(video.source.name,video.subtitle).filter { it.isNotBlank() }.joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        QualityBadge(video.quality,lastPlayback=true,reserveLines=2)
                    }
                }
            }
        }
    }
}
