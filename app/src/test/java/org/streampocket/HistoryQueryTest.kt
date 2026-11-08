package org.streampocket
import org.junit.Test
import org.junit.Assert.*
class HistoryQueryTest {
    @Test fun dimensionsComposeAndKeepMatchingEpisode() {
        val now=40L*86400000
        val source=Source("星河源","https://a.example")
        val one=History(Playback(Video(source,"1","星河漫游"),Episode("第12集","https://a.example/12"),60000),now)
        val old=one.copy(playback=one.playback.copy(episode=Episode("第1集","https://a.example/1")),updated=1)
        val live=one.copy(playback=one.playback.copy(video=one.playback.video.copy(source=source.copy(kind=SourceKind.LIVE))))
        val data=listOf(one,old,live)
        assertEquals(listOf(one),queryHistory(data,"星河 12",source.key,HistoryPeriod.WEEK,HistoryKind.VOD,now))
        assertEquals(listOf(live),queryHistory(data,"",null,HistoryPeriod.ALL,HistoryKind.LIVE,now))
        assertEquals(data,queryHistory(data,"",null,HistoryPeriod.ALL,HistoryKind.ALL,now))
        assertTrue(queryHistory(data,"不存在",null,HistoryPeriod.ALL,HistoryKind.ALL,now).isEmpty())
    }
}
