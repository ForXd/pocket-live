package org.streampocket
import org.junit.Assert.*
import org.junit.Test
class PlaybackGestureRulesTest {
    @Test fun boundedSeekHandlesEdgesAndUnknownLive() {
        assertEquals(0L,boundedSeek(3000,-10000,60000,true))
        assertEquals(60000L,boundedSeek(59000,10000,60000,true))
        assertEquals(30000L,boundedSeek(20000,10000,60000,true))
        assertNull(boundedSeek(20000,10000,60000,false))
        assertNull(boundedSeek(20000,10000,-1,true))
        assertEquals(60000L,boundedSeek(20000,Long.MAX_VALUE,60000,true))
        assertEquals(0L,boundedSeek(20000,Long.MIN_VALUE,60000,true))
    }
    @Test fun episodeNavigationCannotCrossLineOrWrongDetail() {
        val video=Video(Source("a","https://example.com/api"),"1","title")
        val episodes=(1..10000).map { Episode("$it","https://example.com/$it.mp4") }
        val detail=Detail(video,"",listOf(Line("main",episodes),Line("other",listOf(Episode("other","https://example.com/other")))))
        assertNull(adjacentEpisode(detail,Playback(video,episodes.first()),-1))
        assertEquals(episodes[1],adjacentEpisode(detail,Playback(video,episodes.first()),1))
        assertNull(adjacentEpisode(detail,Playback(video,episodes.last()),1))
        assertNull(adjacentEpisode(detail,Playback(video.copy(id="other"),episodes[0]),1))
        assertNull(adjacentEpisode(null,Playback(video,episodes[0]),1))
    }
}
