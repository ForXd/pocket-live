package org.streampocket

import org.junit.Assert.*
import org.junit.Test

class VideoGroupsTest {
    private val source=Source("A","https://a.example/api")
    private fun video(title:String,year:String="")=Video(source,title,title,subtitle=year)
    @Test fun sourceIdsMergeButSeasonAndYearDoNot() {
        val one=video("风骚律师 第一季","2015")
        val same=one.copy(source=source.copy(url="https://b.example/api"),id="2",title="风骚律师 第1季")
        val two=video("风骚律师 第二季","2016")
        assertTrue(VideoGroups.sameWork(one,same))
        assertEquals(1,VideoGroups.group(listOf(one,same,two)).size)
        assertEquals(listOf(2,1),VideoGroups.seasons(listOf(one,same,two)).map { it.size })
        assertFalse(VideoGroups.sameWork(one,two))
        assertFalse(VideoGroups.sameWork(one,one.copy(subtitle="2026")))
        assertFalse(VideoGroups.sameWork(video("沙丘","1984"),video("沙丘","2021")))
    }
    @Test fun missingSeasonAndEditionRemainDistinctAndRomanNumeralsAreNotGuessed() {
        val one=video("风骚律师 第一季")
        for(other in listOf("风骚律师","风骚律师 第一季 国语","风骚律师 特别篇","风骚律师 I","风骚律师 第一季第二季")) {
            assertNotEquals(VideoGroups.familyKey(one),VideoGroups.familyKey(video(other)))
        }
        assertNotEquals(VideoGroups.workKey(one),VideoGroups.workKey(one.copy(source=source.copy(kind=SourceKind.LIVE))))
    }
    @Test fun englishChineseAndWideNumbersAndNumericSorting() {
        val values=listOf("Show Season 10","Show S02","Show 第一季","Show 第十二季","Show 第１００季")
        assertEquals(listOf(1,2,10,12,100),VideoGroups.seasons(values.map { video(it) }).map { VideoGroups.identity(it.first()).season })
        assertTrue(VideoGroups.sameWork(video("Show Season 1"),video("Show S01")))
        assertEquals("Show",VideoGroups.familyTitle(video("Show Season 1")))
    }
    @Test fun playbackCandidatesTolerateLegacyMetadataButRejectConflicts() {
        val legacy=video("Show 第一季")
        val enriched=legacy.copy(title="Show Season 1",subtitle="2015",typeName="欧美剧")
        assertTrue(VideoGroups.playbackMatch(legacy,enriched))
        assertFalse(VideoGroups.sameWork(legacy,enriched))
        assertFalse(VideoGroups.playbackMatch(enriched,enriched.copy(subtitle="2025")))
        assertFalse(VideoGroups.playbackMatch(enriched,enriched.copy(title="Show Season 2")))
    }
    @Test fun historiesAcrossSeasonsShareCardButNeverShareEpisodeProgress() {
        val a=History(Playback(video("风骚律师 第1季"),Episode("第1集","https://a/1"),1000,duration=10000),1)
        val b=History(Playback(video("风骚律师 第2季"),Episode("第1集","https://a/2"),9000,duration=10000),2)
        val group=HistoryGroups.group(listOf(a,b)).single()
        assertEquals(2,group.episodes.size)
        assertEquals(listOf(9000L,1000L),group.episodes.map { it.latest.playback.position })
    }
}
