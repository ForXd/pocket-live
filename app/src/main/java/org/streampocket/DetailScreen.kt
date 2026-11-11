// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Detail-local selection and scroll state never alter playback until the user presses play. */
@OptIn(ExperimentalMaterial3Api::class,ExperimentalFoundationApi::class)
@Composable fun DetailScreen(detail:Detail,model:AppModel,splitArtwork:Boolean=true,searchSame:()->Unit) {
    val state by model.state.collectAsState()
    val recent=remember(detail,state.saved.history) { detailResume(detail,state.saved.history) }
    var selected by rememberSaveable(detail.video.source.key,detail.video.id) { mutableIntStateOf(detail.lines.indexOfFirst { option -> option.episodes.any { it.url==recent?.playback?.episode?.url } }.coerceAtLeast(0)) }
    var descending by rememberSaveable(detail.video.source.key,detail.video.id) { mutableStateOf(false) }
    var expanded by rememberSaveable(detail.video.source.key,detail.video.id) { mutableStateOf(false) }
    var sheet by rememberSaveable(detail.video.source.key,detail.video.id) { mutableStateOf<String?>(null) }
    var descriptionOverflows by remember(detail.description) { mutableStateOf(false) }
    val line=detail.lines.getOrNull(selected) ?: detail.lines.firstOrNull()
    val latest=recent?.takeIf { entry -> line?.episodes?.any { it.url==entry.playback.episode.url }==true }
    val episodes=remember(line,descending) { if(descending) line?.episodes.orEmpty().asReversed() else line?.episodes.orEmpty() }
    val list=rememberLazyListState()
    val scope=rememberCoroutineScope()
    var toolbarHeight by remember { mutableIntStateOf(0) }
    val wide=splitArtwork && androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp>=840
    val episodeHeader=if(wide || detail.description.isBlank()) 2 else 3
    val showTop by remember(episodeHeader,list) { derivedStateOf { list.firstVisibleItemIndex>episodeHeader+2 } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val backdropHeight=if(LocalDensity.current.fontScale>1.3f) 180.dp else if(maxWidth>=600.dp) 280.dp else 300.dp
        val columns=(((if(wide) maxWidth.value*.57f else maxWidth.value)-40)/(if(LocalDensity.current.fontScale>1.3f) 150 else 80)).toInt().coerceIn(1,6)
        val navigationThreshold=with(LocalDensity.current) { (backdropHeight-80.dp).roundToPx() }
        val pinnedNavigation by remember(list,wide,navigationThreshold) { derivedStateOf {
            !wide && (list.firstVisibleItemIndex>0 || list.firstVisibleItemScrollOffset>navigationThreshold)
        } }
        val rows=remember(episodes,columns) { episodes.chunked(columns) }
        fun showEpisodes() { scope.launch { list.scrollToItem(episodeHeader) } }
        if(sheet!=null) HandleOnlySheet(onDismissRequest={sheet=null}) {
            SheetSystemBars()
            LazyColumn(Modifier.fillMaxWidth().testTag("detailPicker"),contentPadding=PaddingValues(start=20.dp,end=20.dp,bottom=24.dp)) {
                item { Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(if(sheet=="line") "选择线路" else "定位集数",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    TextButton(onClick={sheet=null}) { Text("取消") }
                } }
                if(sheet=="line") itemsIndexed(detail.lines) { index,option ->
                    ListItem(colors=ListItemDefaults.colors(containerColor=androidx.compose.ui.graphics.Color.Transparent),headlineContent={Text(option.name)},supportingContent={Text("${option.episodes.size} 集")},
                        leadingContent={RadioButton(selected=index==selected,onClick=null)},
                        modifier=Modifier.fillMaxWidth().clickable { selected=index;sheet=null;showEpisodes() }.testTag("detailLine:$index"))
                } else itemsIndexed(episodes.chunked(50)) { index,group ->
                    ListItem(colors=ListItemDefaults.colors(containerColor=androidx.compose.ui.graphics.Color.Transparent),headlineContent={Text("${group.first().name} — ${group.last().name}")},
                        modifier=Modifier.fillMaxWidth().clickable {
                            sheet=null;scope.launch { list.scrollToItem(episodeHeader+1+index*50/columns,-toolbarHeight) }
                        }.testTag("episodeRange:$index"))
                }
            }
        }
        Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(if(wide) 24.dp else 0.dp)) {
            if(wide) Poster(detail.video,model,Modifier.weight(.43f).fillMaxHeight().padding(start=20.dp,bottom=24.dp),shape=MaterialTheme.shapes.medium,contentScale=ContentScale.Crop,large=true)
            Column(Modifier.weight(if(wide) .57f else 1f)) {
            if(pinnedNavigation) Row(Modifier.fillMaxWidth().heightIn(min=48.dp).background(MaterialTheme.colorScheme.background).testTag("detailNavigation"),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=model::closeDetail) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回") }
                Text(detail.video.title,Modifier.weight(1f).padding(end=20.dp),style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            Box(Modifier.weight(1f)) {
                LazyColumn(state=list,modifier=Modifier.fillMaxSize().testTag("detailGrid"),contentPadding=PaddingValues(bottom=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    item(key="identity") {
                        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                            if(!wide) Poster(detail.video,model,Modifier.fillMaxWidth().height(backdropHeight),shape=RectangleShape,contentScale=ContentScale.Crop,large=true)
                            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Transparent,MaterialTheme.colorScheme.background))))
                            Column(Modifier.fillMaxWidth().padding(start=24.dp,end=24.dp,top=if(wide) 20.dp else backdropHeight-100.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                                Text(detail.video.title,style=MaterialTheme.typography.headlineLarge,color=MaterialTheme.colorScheme.onSurface)
                                Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Text(detail.video.source.name,Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    if(detail.video.rating!=null) Text("★ ${detail.video.rating}",style=MaterialTheme.typography.labelLarge,color=Color(0xffffd580))
                                }
                                if(detail.video.subtitle.isNotBlank()) Text(detail.video.subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                if(detail.video.quality.evidence!=QualityEvidence.UNKNOWN) QualityBadge(detail.video.quality,lastPlayback=true)
                            }
                        }
                    }
                    if(!wide && detail.description.isNotBlank()) item(key="description") {
                        Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).animateContentSize()) {
                            Text(detail.description,onTextLayout={if(!expanded) descriptionOverflows=it.hasVisualOverflow},maxLines=if(expanded) Int.MAX_VALUE else 3,
                                overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(expanded || descriptionOverflows) TextButton(onClick={expanded=!expanded}) { Text(if(expanded) "收起简介" else "展开简介") }
                        }
                    }
                    item(key="playback-actions") {
                        Column(Modifier.fillMaxWidth()) {
                            if(wide && detail.description.isNotBlank()) Text(detail.description,Modifier.padding(horizontal=20.dp),maxLines=if(expanded) Int.MAX_VALUE else 4,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(wide && detail.description.isNotBlank()) TextButton(onClick={expanded=!expanded}) { Text(if(expanded) "收起简介" else "展开简介") }
                            Column(Modifier.padding(horizontal=8.dp)) {
                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                                    TextButton(onClick={sheet="line"},enabled=detail.lines.size>1,shape=MaterialTheme.shapes.medium,
                                        contentPadding=PaddingValues(horizontal=12.dp,vertical=10.dp),
                                        modifier=Modifier.weight(1f,false).heightIn(min=48.dp).testTag("detailLinePicker").semantics { contentDescription="播放线路：${line?.name ?: "暂无线路"}" }) {
                                        Text(line?.name ?: "暂无线路",maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f,false));Icon(Icons.Default.ArrowDropDown,null,Modifier.size(20.dp))
                                    }
                                    TextButton(onClick=searchSame,shape=MaterialTheme.shapes.medium,contentPadding=PaddingValues(horizontal=12.dp,vertical=10.dp),
                                        modifier=Modifier.heightIn(min=48.dp).testTag("detailSearchSame")) {
                                        Text("同名换源",maxLines=1,overflow=TextOverflow.Ellipsis)
                                    }
                                }
                            }
            Surface(color=MaterialTheme.colorScheme.background) {
                Column {
                    Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        val primary=latest?.playback?.episode ?: line?.episodes?.firstOrNull()
                        Row(Modifier.fillMaxWidth().testTag("detailPlaybackSummary"),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                            primary?.let { episode ->
                                Text(episode.name,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                                latest?.let { entry ->
                                    val time=android.text.format.DateUtils.formatElapsedTime((entry.playback.position/1000).coerceAtLeast(0))
                                    Text("已观看 $time",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier=Modifier.semantics { contentDescription="上次播放位置 $time" })
                                }
                            }
                        }
                        GlassButton(onClick={primary?.let { model.play(detail.video,it) }},enabled=primary!=null,shape=MaterialTheme.shapes.medium,
                            modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("detailPlay")) {
                            Icon(painterResource(R.drawable.ic_play),null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp))
                            Text(if(primary==null) "暂无剧集" else if(latest!=null) "继续观看" else "立即播放")
                        }
                    }
                }
            }

                        }
                    }
                    stickyHeader(key="episode-controls") {
                        Surface(color=MaterialTheme.colorScheme.background) {
                            Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).testTag("episodeToolbar").onSizeChanged { toolbarHeight=it.height }) {
                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                    Text("选集",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                                    if(showTop) IconButton(onClick={scope.launch { list.animateScrollToItem(0) }},modifier=Modifier.testTag("detailBackToTop")) { Icon(Icons.Default.KeyboardArrowUp,"回到详情顶部") }
                                    Text("${episodes.size} 集",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    if(episodes.size>50) TextButton(onClick={sheet="range"},modifier=Modifier.testTag("episodeJump").semantics { contentDescription="定位集数" }) { Text("定位") }
                                    IconToggleButton(checked=descending,onCheckedChange={descending=it;showEpisodes()},modifier=Modifier.testTag("episodeOrder")) {
                                        Icon(painterResource(R.drawable.ic_sort),if(descending) "当前倒序，切换正序" else "当前正序，切换倒序",Modifier.rotate(if(descending) 180f else 0f))
                                    }
                                }

                            }
                        }
                    }
                    if(episodes.isEmpty()) item { Text("当前线路暂无可播放内容",Modifier.padding(horizontal=20.dp,vertical=16.dp),color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    itemsIndexed(rows,key={index,_->"$selected:$descending:$index"}) { _,row ->
                        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp).height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            row.forEach { episode ->
                                val watched=latest?.playback?.episode?.url==episode.url
                                GlassButton(onClick={model.play(detail.video,episode)},shape=MaterialTheme.shapes.small,
                                    border=androidx.compose.foundation.BorderStroke(1.dp,if(watched) MaterialTheme.colorScheme.primary.copy(alpha=.5f) else Color.Transparent),
                                    colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.onSurface,containerColor=glassFill(watched)),
                                    contentPadding=PaddingValues(horizontal=8.dp,vertical=12.dp),modifier=Modifier.weight(1f).fillMaxHeight().heightIn(min=56.dp).testTag("episode:${episode.name}")) {
                                    Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {

                                        Text(episode.name,maxLines=2,overflow=TextOverflow.Ellipsis)
                                        if(episode.quality.evidence!=QualityEvidence.UNKNOWN) Text(episode.quality.label,style=MaterialTheme.typography.labelSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            repeat(columns-row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        }
        if(!pinnedNavigation) IconButton(onClick=model::closeDetail,modifier=Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(alpha=.45f),MaterialTheme.shapes.small)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回",tint=Color.White)
        }
    }
}
