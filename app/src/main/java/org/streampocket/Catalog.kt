// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import okhttp3.HttpUrl.Companion.toHttpUrl

data class Category(val id: String, val name: String)
data class CatalogPage(val videos: List<Video>, val categories: List<Category>, val hasMore: Boolean)
object Catalog {
    const val MAX_PAGES = 10
    fun url(source: Source, category: String?, query: String, page: Int): String {
        require(page in 1..MAX_PAGES)
        return source.url.toHttpUrl().newBuilder().setQueryParameter("ac","videolist")
            .setQueryParameter("pg",page.toString()).apply {
                if(category != null) setQueryParameter("t",category) else removeAllQueryParameters("t")
                if(query.isNotBlank()) setQueryParameter("wd",query.take(100)) else removeAllQueryParameters("wd")
            }.build().toString()
    }
    fun parse(text: String, source: Source, page: Int): CatalogPage {
        val root=JsonData.parse(text).obj() ?: throw UserError("分类接口需要 JSON 对象")
        val videos=CmsParser.search(text,source).distinctBy { it.source.key+":"+it.id }
        val categories=root.arr("class").take(100).mapNotNull { raw ->
            raw.obj()?.let { val id=it.scalar("type_id").take(128); val name=it.scalar("type_name").take(80)
                if(id.isBlank() || name.isBlank()) null else Category(id,name) }
        }.distinctBy { it.id }
        val pages=root.scalar("pagecount").toIntOrNull() ?: page
        return CatalogPage(videos,categories,page < minOf(pages,MAX_PAGES) && videos.isNotEmpty())
    }
}
