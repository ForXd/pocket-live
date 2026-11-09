package org.streampocket
import org.junit.Test
import org.junit.Assert.*
class LivePlaylistTest {
    @Test fun groupsLogosAndHttpChannelsRemainBounded() {
        val source=Source("直播","https://example.com/list.m3u",SourceKind.LIVE)
        val text="#EXTM3U\n"+(1..600).joinToString("\n") { "#EXTINF:-1 group-title=\"新闻\" tvg-logo=\"https://example.com/logo.png\",频道$it\nhttps://example.com/$it.m3u8" }
        val channels=CmsParser.playlist(text,source)
        assertEquals(500,channels.size);assertEquals("新闻",channels.first().typeName)
        assertEquals("https://example.com/logo.png",channels.first().poster)
        assertEquals(SourceKind.LIVE,channels.first().source.kind)
    }
}
