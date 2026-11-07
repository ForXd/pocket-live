package org.streampocket

import org.junit.Assert.*
import org.junit.Test

class GenresTest {
    private val source=Source("test","https://example.org/api")
    private fun video(tags:String="",type:String="",title:String="影片")=Video(source,"1",title,typeName=type,genreText=tags)
    @Test fun genresCrossMovieAndSeriesFormatsAndAllowMultipleTags() {
        assertEquals(setOf(Genre.ROMANCE,Genre.MYSTERY),Genres.of(video("爱情,悬疑","国产剧")))
        assertEquals(setOf(Genre.ROMANCE),Genres.of(video(type="爱情片")))
        assertTrue(Genres.matches(video("悬疑/犯罪"),Genre.CRIME))
        assertFalse(Genres.matches(video("爱情"),Genre.MYSTERY))
    }
    @Test fun missingGenresAreExplicitAndTitlesNeverClassify() {
        assertEquals(setOf(Genre.UNKNOWN),Genres.of(video(type="电视剧",title="爱情悬疑故事")))
        assertTrue(Genres.matches(video(),Genre.ALL))
        assertFalse(Genres.matches(video(),Genre.ROMANCE))
        assertFalse(Genre.WAR.matchesLabel("award"))
        assertTrue(Genre.ROMANCE.matchesLabel("Romance, Comedy"))
    }
    @Test fun parserReadsActualGenreField() {
        val parsed=CmsParser.search("""{"list":[{"vod_id":1,"vod_name":"影片","type_name":"国产剧","vod_class":"爱情,悬疑"}]}""",source).single()
        assertEquals(setOf(Genre.ROMANCE,Genre.MYSTERY),Genres.of(parsed))
    }
    @Test fun sourceGenreIdsAreNotGuessedAndAllMatchingIdsAreKept() {
        val categories=listOf(Category("99","爱情片"),Category("2","电视剧"))
        assertEquals(listOf("99"),Genres.categoryIds(categories,Genre.ROMANCE))
        assertTrue(Genres.categoryIds(categories,Genre.MYSTERY).isEmpty())
        assertEquals(listOf("99","100"),Genres.categoryIds(categories+Category("100","爱情剧"),Genre.ROMANCE))
    }
}
