package org.streampocket

import org.junit.Assert.*
import org.junit.Test

class DiscoverySortTest {
    private val source=Source("测试","https://test.invalid/api")
    private fun video(id:String,rating:Double?=null,time:Long?=null)=Video(source,id,id,rating=rating,updatedAt=time)
    @Test fun scoresAreNumericStableAndUnknownLast() {
        val items=listOf(video("a",9.0),video("b"),video("c",10.0),video("d",9.0))
        assertEquals(listOf("c","a","d","b"),DiscoverySort.RATING.apply(items).map { it.id })
        assertSame(items,DiscoverySort.DEFAULT.apply(items))
    }
    @Test fun appendedPagesParticipateAndNewestComesFirst() {
        val first=listOf(video("a",time=100),video("b"))
        assertEquals(listOf("c","a","d","b"),DiscoverySort.UPDATED.apply(first+listOf(video("c",time=200),video("d",time=100))).map { it.id })
    }
    @Test fun parsesCmsMetadataWithoutUsingYearOrRemarksAsTime() {
        val parsed=CmsParser.search("""{"list":[{"vod_id":"1","vod_name":"A","vod_score":"9.2","vod_time":"2026-11-01 12:30:00"},{"vod_id":"2","vod_name":"B","vod_year":"2026","vod_remarks":"更新至12集","vod_score":"NaN","vod_time":"bad"}]}""",source)
        assertEquals(9.2,parsed[0].rating!!,0.0);assertNotNull(parsed[0].updatedAt)
        assertNull(parsed[1].rating);assertNull(parsed[1].updatedAt)
    }
    @Test fun acceptsSupportedDatesAndRejectsInvalidValues() {
        val t=sourceUpdatedAt("2026-11-01 12:30:00")!!
        assertEquals(t,sourceUpdatedAt("2026-11-01T04:30:00Z"))
        assertEquals(t,sourceUpdatedAt(t.toString()))
        assertEquals(t,sourceUpdatedAt((t/1000).toString()))
        assertNotNull(sourceUpdatedAt("2026-11-01"))
        for(s in listOf("","2026-02-30","0","-1","2026","更新至3集")) assertNull(sourceUpdatedAt(s))
        for(s in listOf("NaN","Infinity","0","-1","11","")) assertNull(sourceRating(s))
    }
    @Test fun titleSortAndRecommendationMetadata() {
        assertEquals(listOf("A","B"),DiscoverySort.TITLE.apply(listOf(video("B"),video("A"))).map { it.id })
        val v=Recommendations.parse("""{"subjects":[{"id":"1","title":"A","rate":"8.5"}]}""",1).videos.single()
        assertEquals(8.5,v.rating!!,0.0);assertNull(v.updatedAt)
    }
}
