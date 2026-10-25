package org.streampocket
import org.junit.Assert.*
import org.junit.Test

class AutoHlsTest {
    private val base="https://media.example/movie/list.m3u8"
    private fun group(seconds:Int,path:String)="#EXTINF:$seconds,\n$path\n"
    private fun playlist(body:String)="#EXTM3U\n#EXT-X-TARGETDURATION:300\n#EXT-X-MEDIA-SEQUENCE:10\n$body#EXT-X-ENDLIST\n"
    @Test fun removesMarkedMiddleAndMapsBothTimelines() {
        val input=playlist(group(100,"a.ts")+"#EXT-X-DISCONTINUITY\n"+group(15,"/ads/ad.ts?token=secret")+"#EXT-X-DISCONTINUITY\n"+group(100,"b.ts?token=preserved"))
        val edit=AutoHls.filter(input,base,emptySet())
        assertEquals(1,edit.removed);assertFalse(edit.text.contains("/ads/"));assertTrue(edit.text.contains("b.ts?token=preserved"))
        assertEquals(90_000L,edit.originalPosition(90_000));assertEquals(115_000L,edit.originalPosition(100_000))
        assertEquals(100_000L,edit.filteredPosition(110_000));assertEquals(105_000L,edit.filteredPosition(120_000))
    }
    @Test fun recognizesLeadAndShortInterstitial() {
        val input=playlist("#EXT-X-DISCONTINUITY\n"+group(15,"lead.ts")+"#EXT-X-DISCONTINUITY\n"+group(180,"a.ts")+"#EXT-X-DISCONTINUITY\n"+group(20,"insert.ts")+"#EXT-X-DISCONTINUITY\n"+group(200,"b.ts"))
        val edit=AutoHls.filter(input,base,emptySet())
        // Similar small group sizes deliberately suppress the lead heuristic.
        assertEquals(1,edit.removed);assertFalse(edit.text.contains("insert.ts"))
        val lead=AutoHls.filter(playlist("#EXT-X-DISCONTINUITY\n"+group(15,"lead.ts")+"#EXT-X-DISCONTINUITY\n"+group(200,"main.ts")),base,emptySet())
        assertEquals(1,lead.removed);assertTrue(lead.text.contains("#EXT-X-MEDIA-SEQUENCE:11"));assertEquals(15_000L,lead.originalPosition(0))
    }
    @Test fun leavesComplexLiveAndOrdinaryGroupingUntouched() {
        val ordinary=playlist((1..5).joinToString("#EXT-X-DISCONTINUITY\n") { group(20,"main$it.ts") })
        assertEquals(ordinary,AutoHls.filter(ordinary,base,emptySet()).text)
        val input=playlist(group(180,"a.ts")+"#EXT-X-DISCONTINUITY\n"+group(15,"/ads/ad.ts")+"#EXT-X-DISCONTINUITY\n"+group(180,"b.ts"))
        listOf(input.replace("#EXT-X-ENDLIST", ""),input.replace("#EXTM3U","#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\""), input.replace("#EXTM3U","#EXTM3U\n#EXT-X-MAP:URI=\"init\"")).forEach {
            assertEquals(it,AutoHls.filter(it,base,emptySet()).text)
        }
    }
    @Test fun noFalseMatchFromQueryAndNoEmptyOutput() {
        val input=playlist(group(100,"main.ts?ads=/ads/")+"#EXT-X-DISCONTINUITY\n"+group(100,"other.ts"))
        assertEquals(input,AutoHls.filter(input,base,emptySet()).text)
        val ads=playlist(group(10,"/ads/a.ts")+"#EXT-X-DISCONTINUITY\n"+group(10,"/ads/b.ts"))
        assertEquals(ads,AutoHls.filter(ads,base,emptySet()).text)
    }
    @Test fun multipleCutsMapToOriginalCoordinates() {
        val edit=HlsEdit("",3,35_000,listOf(HlsCut(0,10_000),HlsCut(100_000,15_000),HlsCut(200_000,10_000)))
        assertEquals(10_000L,edit.originalPosition(0));assertEquals(115_000L,edit.originalPosition(90_000));assertEquals(210_000L,edit.originalPosition(175_000))
        listOf(10_000L,50_000L,115_000L,150_000L,210_000L,300_000L).forEach { assertEquals(it,edit.originalPosition(edit.filteredPosition(it))) }
    }
}
