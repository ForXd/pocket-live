package org.streampocket

import org.junit.Test
import org.junit.Assert.assertEquals

class SearchReplayTest {
    @Test fun incrementalRankingMatchesStableFullSort() {
        val query="星河"
        val ranking=QueryRanking(query)
        val source=Source("fixture","https://example.org/api")
        var current=emptyList<Video>()
        for(batch in 0..29) {
            val rows=current+(0 until 50).map { n ->
                val i=batch*50+n
                Video(source,i.toString(),if(i%3==0) query else "影片$i",quality=VideoQuality(height=480+i%7))
            }
            val order=if(batch in 10..14) SearchOrder.QUALITY else SearchOrder.RELEVANCE
            val pinned=batch in 18..20
            val expected=(if(pinned) rows else SearchRules.sorted(rows,query,order)).take(QualityRules.RESULT_LIMIT)
            current=ranking.merge(rows,current,order,pinned)
            assertEquals(expected,current)
        }
    }
    @Test fun replay() {
        for(size in listOf(100,500,1000)) {
            val rows=(0 until size).map { i -> Video(Source("source","https://example.org/api"),i.toString(),"星河"+(i%40),subtitle=(1980+i%45).toString()) }
            val times=mutableListOf<Long>()
            repeat(7) {
                val start=System.nanoTime()
                val groups=VideoGroups.searchGroups(rows)
                times+=(System.nanoTime()-start)/1000
                assertEquals(size,groups.sumOf { it.size })
            }
            println("REPLAY "+size+" "+times.sorted()[3]+" us")
        }
    }
}
