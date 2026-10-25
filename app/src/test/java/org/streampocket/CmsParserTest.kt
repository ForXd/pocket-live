package org.streampocket
import org.junit.Assert.*
import org.junit.Test
class CmsParserTest {
    private val source=Source("fixture","https://example.org/api")
    @Test fun nullableSearchAndMalformedList() {
        assertTrue(CmsParser.search("{\"list\":null}",source).isEmpty())
        try { CmsParser.search("{\"list\":{}}",source); fail() } catch(_:UserError) {}
    }
    @Test fun numericIdsAndLines() {
        val video=CmsParser.search("""{"list":[{"vod_id":1,"vod_name":"Movie"}]}""",source).single()
        assertEquals("1",video.id)
        val dollar='$'
        val text="""{"list":[{"vod_play_from":"one${dollar}${dollar}${dollar}two","vod_play_url":"ep1${dollar}https://example.org/1.m3u8#ep2${dollar}https://example.org/2.mp4${dollar}${dollar}${dollar}other${dollar}https://example.org/3.m3u8"}]}"""
        val detail=CmsParser.detail(text,video)
        assertEquals(2,detail.lines.size); assertEquals(2,detail.lines[0].episodes.size)
    }
    @Test fun channelListNotHlsMediaManifest() {
        assertEquals("Channel",CmsParser.playlist("#EXTM3U\n#EXTINF:-1,Channel\nhttps://example.org/stream.m3u8",source).single().title)
        try { CmsParser.playlist("#EXTM3U\n#EXT-X-TARGETDURATION:10",source); fail() } catch(_:UserError) {}
    }
    @Test fun descriptionFallbackRemainsDataOnly() {
        val video=Video(source,"1","movie")
        val dollar='$'
        val text="""{"list":[{"vod_content":"<script>ignored</script>${dollar}https://example.org/a.m3u8"}]}"""
        assertEquals("https://example.org/a.m3u8",CmsParser.detail(text,video).lines.single().episodes.single().url)
    }
}
