package org.streampocket
import org.junit.Test
import org.junit.Assert.*

class QualityRulesTest {
    private val source=Source("source","https://example.com/api")
    @Test fun titleCannotClaimResolution() {
        val v=CmsParser.search("""{"list":[{"vod_id":1,"vod_name":"4K 1080p movie","vod_remarks":"已完结"}]}""",source).single()
        assertEquals(QualityEvidence.UNKNOWN,v.quality.evidence)
        assertEquals("清晰度未知",v.quality.label)
    }
    @Test fun distinguishSourceClaimsFromDecodedDimensions() {
        val v=CmsParser.search("""{"list":[{"vod_id":1,"vod_name":"Movie","vod_width":1920,"vod_height":1080}]}""",source).single()
        assertEquals(QualityEvidence.API_DIMENSIONS,v.quality.evidence)
        assertEquals(1080,v.quality.pixels)
        assertEquals(QualityEvidence.SOURCE_LABEL,QualityRules.sourceLabel("2160p").evidence)
        assertEquals(0,QualityRules.sourceLabel("蓝光高清").pixels)
        assertEquals(QualityEvidence.MEASURED,QualityRules.measured(3840,2160).evidence)
        assertEquals(QualityEvidence.UNKNOWN,QualityRules.measured(-1,2160).evidence)
    }
    @Test fun stableSortAndIncrementalMergeAreBounded() {
        fun v(id:String,h:Int)=Video(source,id,id,quality=VideoQuality(height=h,evidence=QualityEvidence.SOURCE_LABEL))
        val current=listOf(v("first",720),v("second",720))
        val merged=QualityRules.merge(current,listOf(v("high",2160),v("first",720)))
        assertEquals(listOf("first","second","high"),merged.map { it.id })
        assertEquals(listOf("high","first","second"),QualityRules.sorted(merged).map { it.id })
        assertEquals(1000,QualityRules.merge(emptyList(),List(1500) { v("$it",720) }).size)
    }
    @Test fun resolutionOutranksEvidenceAcrossResources() {
        val measured=Video(source,"measured","Measured",quality=QualityRules.measured(1280,720))
        val claimed=Video(source,"claimed","Claimed",quality=QualityRules.sourceLabel("4K"))
        assertEquals("claimed",QualityRules.sorted(listOf(claimed,measured)).first().id)
        val equal=claimed.copy(quality=QualityRules.sourceLabel("720p"))
        assertEquals("measured",QualityRules.sorted(listOf(equal,measured)).first().id)
    }
    @Test fun qualityRoundTripAndOldStorageCompatibility() {
        val q=QualityRules.measured(1920,1080)
        assertEquals(q,QualityRules.read(QualityRules.json(q)))
        assertEquals(VideoQuality(),QualityRules.read(null))
        assertTrue(StorageBudget.recordBytes(Playback(Video(source,"1","Movie",quality=q),Episode("one","https://example.com/a",q)))>1000)
    }
    @Test fun repeatedSizesAreDistinctAndSessionBufferIsBounded() {
        val buffer=MeasuredQualityBuffer();val q=QualityRules.measured(1920,1080)
        assertEquals(1,(1..10000).count { buffer.offer("session",q) })
        assertEquals(1,buffer.size)
        assertTrue(buffer.offer("session",QualityRules.measured(1280,720)))
        assertEquals(720,buffer["session"]!!.pixels)
        repeat(100) { buffer.offer("other$it",q) }
        assertEquals(4,buffer.size);assertNull(buffer["session"])
        assertFalse(buffer.offer("invalid",VideoQuality()))
    }
}
