// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket
import org.junit.Assert.*
import org.junit.Test

class DiscoverySafetyTest {
    private val source=Source("Fixture","https://example.org/api")
    private val manifest="#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:7\n#EXTINF:5,\n/ads/a.ts?signature=unchanged\n#EXT-X-DISCONTINUITY\n#EXTINF:10,\nmain.ts?token=synthetic\n#EXT-X-ENDLIST\n"
    @Test fun prefixRetainsSequenceEndAndUri() {
        val result=SafeHls.filter(manifest,"https://example.org/movie/list.m3u8",setOf("/ads/a.ts"))
        assertEquals(1,result.removed);assertEquals(5000L,result.removedMs)
        assertTrue(result.text.contains("#EXT-X-MEDIA-SEQUENCE:8"))
        assertTrue(result.text.contains("main.ts?token=synthetic"));assertTrue(result.text.endsWith("#EXT-X-ENDLIST\n"))
        assertTrue(result.text.contains("#EXT-X-DISCONTINUITY\n"))
        assertEquals(5000L,result.originalPosition(0));assertEquals(4000L,result.filteredPosition(9000));assertEquals(0L,result.filteredPosition(1000))
    }
    @Test fun unsafeAndUnknownTagsNeverChangeBytes() {
        listOf("#EXT-X-KEY:METHOD=AES-128,URI=\"key\"","#EXT-X-MAP:URI=\"init\"","#EXT-X-BYTERANGE:100@0","#EXT-X-PROGRAM-DATE-TIME:2026-01-01T00:00:00Z","#EXT-X-PART:DURATION=1,URI=\"part\"","#EXT-X-SKIP:SKIPPED-SEGMENTS=1","#EXT-X-PLAYLIST-TYPE:EVENT","#EXT-X-MEDIA:TYPE=AUDIO","#EXT-X-CUE-OUT:5","#EXT-X-UNKNOWN:1").forEach { tag ->
            val input=manifest.replace("#EXT-X-VERSION:3",tag)
            assertEquals(input,SafeHls.filter(input,"https://example.org/list.m3u8",setOf("/ads/a.ts")).text)
        }
    }
    @Test fun liveEmptyUnconfirmedAndCrossOriginArePreserved() {
        val variants=listOf(manifest.replace("#EXT-X-ENDLIST\n",""),manifest.replace("main.ts?token=synthetic","/ads/a.ts"),manifest.replace("/ads/a.ts?signature=unchanged","https://other.example/ads/a.ts"),manifest.replace("#EXT-X-DISCONTINUITY\n",""))
        variants.forEach { assertEquals(it,SafeHls.filter(it,"https://example.org/list.m3u8",setOf("/ads/a.ts")).text) }
        assertEquals(manifest,SafeHls.filter(manifest,"https://example.org/list.m3u8",emptySet()).text)
        assertEquals(manifest,SafeHls.filter(manifest,"https://example.org/list.m3u8",setOf("/ads")).text)
    }
    @Test fun metadataDoesNotInventRatingsAndIdsRemainSourceScoped() {
        val json="""{"pagecount":50,"class":[{"type_id":1,"type_name":"Drama"}],"list":[{"vod_id":"1","vod_name":"Synthetic title","vod_pic":"https://example.org/cover.png"}]}"""
        val page=Catalog.parse(json,source,1)
        assertTrue(page.hasMore);assertEquals("1",page.categories.single().id)
        assertEquals("https://example.org/cover.png",page.videos.single().poster)
        assertEquals("",page.videos.single().subtitle)
        assertFalse(Catalog.parse(json,source,10).hasMore)
        val other=Catalog.parse(json,source.copy(url="https://example.net/api"),1)
        assertNotEquals(page.videos.single().source.key,other.videos.single().source.key)
        assertTrue(Catalog.url(source,"2","a & b",2).contains("wd=a%20%26%20b"))
    }
    @Test fun imagePixelBoundsAndDiskBudgetIncludeStaging() {
        assertNull(PosterLimits.sample(100_000,100_000));assertNull(PosterLimits.sample(0,100))
        val sample=PosterLimits.sample(4000,4000)!!
        assertTrue(((4000+sample-1)/sample).toLong()*((4000+sample-1)/sample)<=PosterLimits.DECODE_PIXELS)
        assertTrue(StorageBudget.DISK_RESPONSES+StorageBudget.POSTERS+StorageBudget.STATE*3<StorageBudget.TOTAL_DISK)
    }
}
