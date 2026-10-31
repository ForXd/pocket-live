package org.streampocket

import org.junit.Test
import org.junit.Assert.*

class DetailPresentationTest {
    private val video=Video(Source("A","https://a.test"),"1","同名剧")
    private val episode=Episode("第2集","https://a.test/2.mp4")
    private val detail=Detail(video,"",listOf(Line("主线",listOf(episode))))
    @Test fun resumeRequiresExactSourceVideoAndAvailableEpisode() {
        val valid=History(Playback(video,episode,72000),1)
        val wrongSource=valid.copy(playback=valid.playback.copy(video=video.copy(source=Source("B","https://b.test"))),updated=4)
        val wrongId=valid.copy(playback=valid.playback.copy(video=video.copy(id="2")),updated=5)
        val removed=valid.copy(playback=valid.playback.copy(episode=episode.copy(url="https://a.test/removed.mp4")),updated=6)
        assertEquals(valid,detailResume(detail,listOf(wrongSource,wrongId,removed,valid)))
        assertNull(detailResume(detail,listOf(wrongSource,wrongId,removed)))
    }
    @Test fun currentEpisodeMetadataReplacesOldLabelWithoutLosingPosition() {
        val history=History(Playback(video,episode.copy(name="旧名称"),42000),1)
        val result=detailResume(detail,listOf(history))!!
        assertEquals(episode,result.playback.episode)
        assertEquals(42000L,result.playback.position)
    }
    @Test fun latestMatchingRecordWinsRegardlessOfInputOrder() {
        val old=History(Playback(video,episode,1000),1)
        val newest=old.copy(playback=old.playback.copy(position=9000),updated=2)
        assertEquals(newest,detailResume(detail,listOf(old,newest)))
        assertNull(detailResume(detail.copy(lines=emptyList()),listOf(newest)))
    }
}
