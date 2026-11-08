// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

/** A bounded batch can be resumed without discarding pages already searched. */
internal data class CatalogRequest(val category:String?,val page:Int=1)
internal data class CatalogCursor(val pending:List<CatalogRequest>)
internal data class CatalogBatch(val videos:List<Video>,val categories:List<Category>,val next:CatalogCursor?,val pages:Int)
internal class CatalogBrowser(private val fetch:suspend (String)->String) {
    suspend fun load(source:Source,category:String?,genres:Set<Genre>,query:String,known:List<Category>,cursor:CatalogCursor?=null):CatalogBatch {
        var categories=known
        var current=cursor
        var pages=0
        val videos=linkedMapOf<String,Video>()
        suspend fun page(id:String?,number:Int):CatalogPage {
            val result=Catalog.parse(fetch(Catalog.url(source,id,query,number)),source,number)
            if(result.categories.isNotEmpty()) categories=(categories+result.categories).distinctBy { it.id }
            pages++
            return result
        }
        // Discover this source's actual IDs before planning a genre query.
        var first:CatalogPage?=null
        if(current==null) {
            if(categories.isEmpty()) {
                categories=Catalog.parse(fetch(Catalog.categoriesUrl(source)),source,1).categories
                // Some compatible APIs include their classes only in the detailed response.
                if(categories.isEmpty()) first=page(null,1)
            }
            val ids=if(category!=null) listOf(category) else genres.flatMap { Genres.categoryIds(categories,it) }.distinct()
            // Dedicated movie categories do not cover tagged series in generic TV categories.
            // Search them first, then continue through the general catalog without losing either.
            current=CatalogCursor((if(category==null && ids.isNotEmpty()) ids+listOf(null) else ids.ifEmpty { listOf(null) }).map { CatalogRequest(it) })
        }
        val branchesInBatch=minOf(BATCH_PAGES,current.pending.size)
        var requests=0
        while(current!=null && pages< BATCH_PAGES) {
            val c=current.pending.first()
            val id=c.category
            val result=if(id==null && c.page==1 && first!=null) first else page(id,c.page)
            requests++
            first=null
            val names=categories.associate { it.id to it.name }
            result.videos.forEach { v ->
                val type=v.typeId.ifBlank { id.orEmpty() }
                val enriched=v.copy(typeId=type,typeName=names[type] ?: v.typeName)
                if(Genres.matchesAny(enriched,genres)) videos.putIfAbsent(enriched.id,enriched)
            }
            // Rotate unfinished categories so one large genre cannot starve the other choices.
            val pending=current.pending.drop(1) + if(result.hasMore) listOf(c.copy(page=c.page+1)) else emptyList()
            current=pending.takeIf { it.isNotEmpty() }?.let { CatalogCursor(it) }
            if(genres.isEmpty() || (videos.size>=20 && requests>=branchesInBatch)) break
        }
        return CatalogBatch(videos.values.toList(),categories,current,pages)
    }
    suspend fun random(source:Source,category:String?,genres:Set<Genre>,query:String,known:List<Category>):CatalogBatch {
        val classes=if(known.isEmpty()) Catalog.parse(fetch(Catalog.categoriesUrl(source)),source,1).categories else known
        val ids=genres.flatMap { Genres.categoryIds(classes,it) }.distinct()
        val id=category ?: ids.randomOrNull()
        val first=Catalog.parse(fetch(Catalog.url(source,id,query,1)),source,1)
        val total=first.totalPages ?: 1
        val chosen=if(total>1) kotlin.random.Random.nextInt(2,total+1) else 1
        val page=if(chosen==1) first else Catalog.parse(fetch(Catalog.url(source,id,query,chosen)),source,chosen)
        val names=(classes+page.categories).associate { it.id to it.name }
        val rows=page.videos.map { it.copy(typeName=names[it.typeId.ifBlank { id.orEmpty() }] ?: it.typeName) }.filter { Genres.matchesAny(it,genres) }
        return CatalogBatch(rows.shuffled(),classes,if(page.hasMore) CatalogCursor(listOf(CatalogRequest(id,chosen+1))) else null,if(chosen==1) 1 else 2)
    }
    companion object { const val BATCH_PAGES=4 }
}
