// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import okhttp3.HttpUrl.Companion.toHttpUrl

data class RecommendationPage(val videos: List<Video>, val more: Boolean)
object Recommendations {
    const val PAGE_SIZE = 20
    const val MAX_PAGES = 10
    val source = Source("豆瓣推荐", "https://movie.douban.com/j/search_subjects")
    private val movieTags = listOf("热门", "最新", "经典", "豆瓣高分", "冷门佳片", "华语", "欧美", "韩国", "日本", "动画")
    private val tvTags = listOf("热门", "美剧", "英剧", "韩剧", "日剧", "国产剧", "港剧", "日本动画", "综艺", "纪录片")
    fun tags(type: String) = if(type == "tv") tvTags else movieTags
    fun url(type: String, tag: String, page: Int): String {
        require(type in listOf("movie", "tv") && tag in tags(type) && page in 1..MAX_PAGES)
        return source.url.toHttpUrl().newBuilder().addQueryParameter("type", type)
            .addQueryParameter("tag", tag).addQueryParameter("sort", "recommend")
            .addQueryParameter("page_limit", "$PAGE_SIZE").addQueryParameter("page_start", "${(page-1)*PAGE_SIZE}").build().toString()
    }
    fun parse(text: String, page: Int): RecommendationPage {
        val root = JsonData.parse(text).obj() ?: throw UserError("推荐数据格式错误")
        val raw = root.get("subjects")?.takeIf { it.isJsonArray }?.asJsonArray ?: throw UserError("推荐来源暂不可用，请稍后重试")
        val videos = raw.take(PAGE_SIZE).mapNotNull { element ->
            val item = element.obj() ?: return@mapNotNull null
            val id = item.scalar("id").take(80)
            val title = item.scalar("title").trim().take(100)
            if(id.isBlank() || title.isBlank()) return@mapNotNull null
            val rating = item.scalar("rate").toDoubleOrNull()?.takeIf { it > 0 && it <= 10 }
            val subtitle = listOfNotNull(rating?.let { "豆瓣 $it" }, item.scalar("episodes_info").take(80).takeIf { it.isNotBlank() }).joinToString(" · ")
            Video(source, id, title, subtitle, poster=validUrl(item.scalar("cover")))
        }.distinctBy { it.id }
        return RecommendationPage(videos, raw.size() >= PAGE_SIZE && page < MAX_PAGES && videos.isNotEmpty())
    }
}
