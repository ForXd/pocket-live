package org.streampocket

import org.junit.Assert.*
import org.junit.Test

class HistoryGroupsTest {
    private val source=Source("源一","https://one.example/api")
    private fun h(id:String,episode:Int,time:Long,src:Source=source)=History(Playback(Video(src,id,"同名剧"),Episode("第${episode}集","https://media.example/$id/$episode.mp4"),episode*1000L),time)
    @Test fun groupsUseSourceAndIdNotTitleAndRemainRecentFirst() {
        val a1=h("a",1,10);val a2=h("a",2,40);val b=h("b",1,30);val other=h("a",1,20,source.copy(url="https://two.example/api"))
        val groups=HistoryGroups.group(listOf(a1,b,other,a2))
        assertEquals(3,groups.size);assertEquals(listOf(a2,a1),groups[0].entries)
        assertEquals(b,groups[1].latest);assertEquals(other,groups[2].latest)
        assertNotEquals(groups[0].key,groups[2].key)
    }
    @Test fun resumeKeepsEachEpisodeBreakpointAndCreatesNewOwnership() {
        val a=h("a",2,20);val b=h("b",4,10)
        val groups=HistoryGroups.group(listOf(a,b))
        val first=HistoryGroups.resume(groups[0].latest);val second=HistoryGroups.resume(groups[1].latest)
        assertEquals(a.playback.video,first.video);assertEquals(a.playback.episode,first.episode);assertEquals(2000L,first.position)
        assertEquals(4000L,second.position);assertNotEquals(a.playback.sessionId,first.sessionId);assertTrue(first.autoPlay)
        assertEquals(1000L,HistoryGroups.resume(h("a",1,0)).position)
    }
    @Test fun liveResumeAndLabelNeverPromiseVodProgress() {
        val live=h("channel",1,1,source.copy(kind=SourceKind.LIVE))
        assertEquals(0L,HistoryGroups.resume(live).position)
        assertEquals("直播 · 打开当前直播",HistoryGroups.progress(live))
    }
    @Test fun removingOneGroupPreservesOtherSourceAndSeries() {
        val a=h("a",1,1);val a2=h("a",2,2);val b=h("b",1,3);val other=h("a",1,4,source.copy(url="https://two.example/api"))
        val saved=Saved(history=listOf(a,a2,b,other))
        assertEquals(listOf(b,other),HistoryGroups.remove(saved,HistoryGroups.key(a.playback.video)).history)
        assertEquals(4,saved.history.size)
    }
    @Test fun evictionStillCountsEpisodesAndKeepsBoundedSingleHistory() {
        var saved=Saved()
        repeat(105) { i -> saved=StateRules.checkpoint(saved,h("series-${i%2}",i,i.toLong()).playback,i.toLong()) }
        assertEquals(100,saved.history.size);assertEquals(104L,saved.history.first().updated)
        val groups=HistoryGroups.group(saved.history)
        assertEquals(2,groups.size);assertEquals(100,groups.sumOf { it.entries.size })
        assertTrue(saved.history.sumOf { StorageBudget.recordBytes(it.playback) }<=StorageBudget.HISTORY)
        assertEquals(5L,saved.history.last().updated)
    }
    @Test fun equalTimestampsAreStableAndIdentityEncodingIsUnambiguous() {
        val a=h("a",1,7);val b=h("b",1,7)
        assertEquals(listOf(a,b),HistoryGroups.group(listOf(a,b)).map { it.latest })
        assertNotEquals(HistoryGroupKey("a","bc").stableKey,HistoryGroupKey("ab","c").stableKey)
    }
}
