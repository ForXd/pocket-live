// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal fun recommendationSynopsis(html:String):String? {
    val full=Regex("""<span\b[^>]*class=["'][^"']*all\s+hidden[^"']*["'][^>]*>(.*?)</span>""",setOf(RegexOption.DOT_MATCHES_ALL,RegexOption.IGNORE_CASE)).find(html)?.groupValues?.get(1)
    val summary=full ?: Regex("""<span\b[^>]*property=["']v:summary["'][^>]*>(.*?)</span>""",setOf(RegexOption.DOT_MATCHES_ALL,RegexOption.IGNORE_CASE)).find(html)?.groupValues?.get(1)
    return summary?.replace(Regex("<br\\s*/?>",RegexOption.IGNORE_CASE),"\n")?.replace(Regex("<[^>]*>"),"")
        ?.replace("&nbsp;"," ")?.replace("&quot;","\"")?.replace("&#39;","'")?.replace("&lt;","<")?.replace("&gt;",">")?.replace("&amp;","&")
        ?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString("\n\n")?.take(6000)?.takeIf { it.isNotBlank() }
}

internal fun recommendationMetadata(json:String):String? {
    val subject=JsonData.parse(json).obj()?.get("subject")?.obj() ?: return null
    val facts=listOf("release_year","region","duration").map { subject.scalar(it) }.filter { it.isNotBlank() }
    val genres=subject.arr("types").mapNotNull { it.takeIf { it.isJsonPrimitive }?.asString }.joinToString(" / ")
    val directors=subject.arr("directors").mapNotNull { it.takeIf { it.isJsonPrimitive }?.asString }.take(4).joinToString("、")
    val actors=subject.arr("actors").mapNotNull { it.takeIf { it.isJsonPrimitive }?.asString }.take(5).joinToString("、")
    return listOf(facts.joinToString(" · "),genres,if(directors.isNotBlank()) "导演：$directors" else "",if(actors.isNotBlank()) "主演：$actors" else "")
        .filter { it.isNotBlank() }.joinToString("\n").takeIf { it.isNotBlank() }
}

@Composable internal fun RecommendationPreview(s:UiState,model:AppModel,search:(String)->Unit) {
    val video=s.recommendPreview ?: return
    CardBackHandler(onBack=model::closeRecommendation)
    val motion=LocalCardReturn.current
    val back={ motion.commit(model::closeRecommendation) }
    val wide=windowSizeDp().width.value>=700
    val largeFont=androidx.compose.ui.platform.LocalDensity.current.fontScale>1.3f
    Surface(Modifier.fillMaxSize().testTag("recommendationPreview")) {
        Column(Modifier.safeDrawingPadding()) {
            if(wide) Row(Modifier.fillMaxWidth().padding(horizontal=PageGutter),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                PocketBack(back, label = "返回推荐")
                Text("影片介绍",Modifier.padding(12.dp),style=MaterialTheme.typography.titleMedium)
            }
            if(!wide) TitleCover(video,model,minOf(windowSizeDp().height*.32f,290.dp),back, label = "返回推荐")
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=PageGutter,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                if(wide) FlowRow(maxItemsInEachRow=if(largeFont && !wide) 1 else 2,horizontalArrangement=Arrangement.spacedBy(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Box {
                        Poster(video,model,Modifier.width(220.dp).aspectRatio(.7f),shape=MaterialTheme.shapes.large,contentScale=ContentScale.Crop,large=true)
                        RecommendationRating(video,Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(8.dp))
                    }
                    Column(if(largeFont && !wide) Modifier.fillMaxWidth() else Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text(video.title,style=MaterialTheme.typography.headlineMedium)
                        if(video.subtitle.isNotBlank()) Text(video.subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("豆瓣推荐",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if(!wide) { Text("影片介绍",style=MaterialTheme.typography.titleLarge); Text(video.source.name,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                s.recommendMetadata?.let { Text(it,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("剧情简介",style=MaterialTheme.typography.titleLarge)
                if(s.recommendPreviewLoading) { LoadingStatus("正在获取简介…",Modifier.fillMaxWidth()) }
                else Text(s.recommendSynopsis ?: "暂未获取到简介。你可以重试，或继续搜索片源。",style=MaterialTheme.typography.bodyLarge)
                if(!s.recommendPreviewLoading && s.recommendSynopsis==null) GlassTextButton(onClick={model.openRecommendation(video)}) { Text("重试简介") }
            }
            GlassButton(colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.onSurface,contentColor=MaterialTheme.colorScheme.surface),onClick={model.closeRecommendation();search(video.title)},modifier=Modifier.fillMaxWidth().padding(horizontal=PageGutter,vertical=16.dp).testTag("searchRecommendationSources")) { Text("搜索片源") }
        }
    }
}

internal fun recommendationApiSynopsis(json:String,id:String):String? {
    val subject=JsonData.parse(json).obj() ?: return null
    if(subject.scalar("id")!=id) return null
    return subject.scalar("intro").trim().take(6000).takeIf { it.isNotBlank() }
}
