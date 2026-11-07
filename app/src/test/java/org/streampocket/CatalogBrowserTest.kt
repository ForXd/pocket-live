package org.streampocket

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class CatalogBrowserTest {
    private val source=Source("片库","https://example.org/api")
    @Test fun readsActualClassEndpointAndMergesMultipleGenreIdsWithoutGuessing()=runBlocking {
        val requests=mutableListOf<String>()
        val browser=CatalogBrowser { url ->
            requests+=url
            val u=url.toHttpUrl()
            when {
                u.queryParameter("ac")=="list" -> """{"class":[{"type_id":17,"type_name":"爱情片"},{"type_id":32,"type_name":"爱情剧"}],"list":[]}"""
                else -> """{"list":[{"vod_id":"${u.queryParameter("t")}","vod_name":"影片"}]}"""
            }
        }
        val result=browser.load(source,null,Genre.ROMANCE,"星河",emptyList())
        assertEquals(listOf("17","32"),result.videos.map { it.id })
        assertTrue(result.videos.all { Genres.matches(it,Genre.ROMANCE) })
        assertEquals("list",requests.first().toHttpUrl().queryParameter("ac"))
        assertTrue(requests.drop(1).all { it.toHttpUrl().queryParameter("wd")=="星河" })
        assertNull(result.next)
    }
    @Test fun sparseGenresContinueAcrossBoundedBatchesWithoutSkippingOrReplayingPages()=runBlocking {
        val pages=mutableListOf<Int>()
        val browser=CatalogBrowser { url ->
            val page=url.toHttpUrl().queryParameter("pg")!!.toInt();pages+=page
            val genre=if(page==6) "悬疑" else "剧情"
            """{"pagecount":6,"list":[{"vod_id":"$page","vod_name":"影片","vod_class":"$genre"}]}"""
        }
        val known=listOf(Category("2","电视剧"))
        val first=browser.load(source,null,Genre.MYSTERY,"",known)
        assertTrue(first.videos.isEmpty());assertNotNull(first.next)
        assertEquals(4,first.pages)
        val second=browser.load(source,null,Genre.MYSTERY,"",known,first.next)
        assertEquals(listOf("6"),second.videos.map { it.id });assertNull(second.next)
        assertEquals((1..6).toList(),pages)
    }
    @Test fun unknownTagsNeverMatchByTitleAndExplicitDifferentTypeIsNotOverwritten()=runBlocking {
        val browser=CatalogBrowser { """{"list":[{"vod_id":"1","vod_name":"爱情故事","type_id":"2","type_name":"电视剧"}]}""" }
        val result=browser.load(source,null,Genre.ROMANCE,"",listOf(Category("7","爱情片"),Category("2","电视剧")))
        assertTrue(result.videos.isEmpty())
    }
    @Test fun allAndRawCategoryUseOnlyOneContentPage()=runBlocking {
        var calls=0
        val browser=CatalogBrowser { calls++; """{"pagecount":20,"list":[{"vod_id":"1","vod_name":"影片"}]}""" }
        val result=browser.load(source,"2",Genre.ALL,"",listOf(Category("2","电视剧")))
        assertEquals(1,calls);assertEquals(2,result.next!!.page)
    }
    @Test fun genreIncludesTaggedSeriesOutsideDedicatedMovieCategory()=runBlocking {
        val browser=CatalogBrowser { url ->
            if(url.toHttpUrl().queryParameter("t")=="7") """{"list":[{"vod_id":"film","vod_name":"影片","type_id":"7"}]}"""
            else """{"list":[{"vod_id":"series","vod_name":"剧集","type_id":"2","vod_class":"爱情"}]}"""
        }
        val result=browser.load(source,null,Genre.ROMANCE,"",listOf(Category("7","爱情片"),Category("2","电视剧")))
        assertEquals(listOf("film","series"),result.videos.map { it.id })
    }

}
