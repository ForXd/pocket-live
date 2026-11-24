package org.streampocket
import org.junit.Assert.*
import org.junit.Test
class PlaybackAlternativesTest {
    private val source=Source("fixture","https://example.org/api")
    private val video=Video(source,"1","剧集 第二季")
    private val current=Playback(video,Episode("第02集","https://example.org/current.mp4"),position=40_000)
    @Test fun matchesEpisodeButNeverUsesListIndexOrWrongSeason() {
        val lines=listOf(Line("new",listOf(Episode("预告","https://example.org/trailer"),Episode("01","https://example.org/1"),Episode("02","https://example.org/2"))))
        assertEquals("02",PlaybackAlternatives.from(Detail(video,"",lines),current).single().episode.name)
        assertTrue(PlaybackAlternatives.from(Detail(video.copy(title="剧集 第一季"),"",lines),current).isEmpty())
        assertFalse(PlaybackAlternatives.matches(current.episode,Episode("第12集","https://example.org/12")))
    }
    @Test fun verifiesEveryDistinctLineForTheCurrentEpisode() {
        val expected=(1..100).map { "https://example.org/$it" }
        val alternatives=expected.mapIndexed { index,url -> Line("线路$index",listOf(Episode("第02集",url))) }
        val lines=listOf(Line("当前",listOf(current.episode)))+alternatives+alternatives.last().copy(name="同址别名")
        val options=PlaybackAlternatives.from(Detail(video,"",lines),current)
        assertEquals(expected,options.map { it.episode.url })
        assertEquals(options,PlaybackAlternatives.merge(options,options))
    }
    @Test fun labelsMustBeUnambiguous() {
        assertTrue(PlaybackAlternatives.matches(Episode("EP 002","x"),Episode("第2集","y")))
        assertFalse(PlaybackAlternatives.matches(Episode("第2集预告","x"),Episode("第2集","y")))
        assertFalse(PlaybackAlternatives.matches(Episode("正片","x"),Episode("第1集","y")))
    }
    @Test fun retriesAreBoundedAndPermanentFailuresAreImmediate() {
        assertNull(PlaybackPolicy.retryDelay(404,1));assertNull(PlaybackPolicy.retryDelay(403,1))
        assertEquals(750L,PlaybackPolicy.retryDelay(503,1));assertEquals(1500L,PlaybackPolicy.retryDelay(null,2))
        assertNull(PlaybackPolicy.retryDelay(503,3));assertNull(PlaybackPolicy.retryDelay(429,3))
    }
}
