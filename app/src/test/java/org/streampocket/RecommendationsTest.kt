package org.streampocket
import org.junit.Assert.*
import org.junit.Test

class RecommendationsTest {
    @Test fun urlEncodesTypeTagAndOffset() {
        val url=Recommendations.url("movie","豆瓣高分",2)
        assertTrue(url.contains("type=movie"));assertTrue(url.contains("page_start=20"));assertFalse(url.contains("豆瓣"))
    }
    @Test fun parsesRatingsCoversWithoutInventingData() {
        val page=Recommendations.parse("""{"subjects":[{"id":"1","title":"剧集","cover":"https://img2.doubanio.com/test.jpg","rate":"8.5"},{"id":"2","title":"新剧","rate":"0","cover":"javascript:bad"},{"id":"1","title":"重复"}]}""",1)
        assertEquals(2,page.videos.size);assertEquals("豆瓣 8.5",page.videos[0].subtitle)
        assertEquals("",page.videos[1].subtitle);assertNull(page.videos[1].poster);assertFalse(page.more)
    }
    @Test(expected=UserError::class) fun rejectsErrorResponses() { Recommendations.parse("""{"error":"denied"}""",1) }
    @Test fun separatesMovieAndTvCategories() {
        assertTrue("国产剧" in Recommendations.tags("tv"));assertFalse("最新" in Recommendations.tags("tv"))
        assertTrue(Recommendations.url("tv","国产剧",1).contains("type=tv"))
    }
    @Test fun capsPagination() {
        val items=(1..20).joinToString(",") { """{"id":"$it","title":"T$it"}""" }
        assertTrue(Recommendations.parse("""{"subjects":[$items]}""",1).more)
        assertFalse(Recommendations.parse("""{"subjects":[$items]}""",10).more)
    }
}
