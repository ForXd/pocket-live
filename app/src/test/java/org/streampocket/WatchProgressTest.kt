package org.streampocket
import org.junit.Assert.*
import org.junit.Test
class WatchProgressTest {
    private val p=Playback(Video(Source("source","https://example.org/api"),"1","Title"),Episode("ep1","https://example.org/1"),position=30_000,duration=120_000)
    @Test fun measuredProgressClampsAndCompletedResumeRestarts() {
        assertEquals(.25f,watchFraction(p)!!,0f)
        assertEquals(1f,watchFraction(p.copy(position=Long.MAX_VALUE))!!,0f)
        assertEquals(0f,watchFraction(p.copy(position=-1))!!,0f)
        assertNull(watchFraction(p.copy(duration=0)))
        assertEquals(0L,HistoryGroups.resume(History(p.copy(position=120_000),0)).position)
        assertEquals(30_000L,HistoryGroups.resume(History(p,0)).position)
    }
    @Test fun perEpisodeProgressDoesNotLeakBetweenSources() {
        val other=p.copy(video=p.video.copy(source=Source("other","https://other.org/api")))
        val history=listOf(History(p,1),History(p.copy(position=60_000),2),History(other,3))
        assertEquals(60_000L,episodeProgress(p.video,history).getValue(p.episode.url).position)
        assertNull(watchFraction(p.copy(video=p.video.copy(source=p.video.source.copy(kind=SourceKind.LIVE)))))
    }
}
