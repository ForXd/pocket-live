package org.streampocket

import android.net.Uri
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import org.junit.Test
import org.junit.Assert.*

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class AdFilterDeviceTest {
    @Test fun parserIntegrationKeepsPlayableSegmentsAndTimestamps() {
        val url="https://media.example/list.m3u8"
        val input="#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:180\n#EXTINF:180,\nmain-a.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:15,\n/ads/ad.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:180,\nmain-b.ts\n#EXT-X-ENDLIST\n"
        var edited=HlsEdit("")
        val parser=SafeHlsParserFactory(url,emptySet(),true) { edited=it }.createPlaylistParser()
        val playlist=parser.parse(Uri.parse(url),input.byteInputStream()) as HlsMediaPlaylist
        assertEquals(2,playlist.segments.size);assertEquals(360_000_000L,playlist.durationUs)
        assertEquals("main-b.ts",playlist.segments[1].url)
        assertEquals(1,playlist.segments[1].relativeDiscontinuitySequence)
        assertEquals(195_000L,edited.originalPosition(180_000))
        val plain=SafeHlsParserFactory(url,emptySet(),false) {}.createPlaylistParser().parse(Uri.parse(url),input.byteInputStream()) as HlsMediaPlaylist
        assertEquals(3,plain.segments.size)
    }
    @Test fun singleVariantFiltersButAdaptiveVariantsPassThrough() {
        val url="https://media.example/master.m3u8"
        val media="#EXTM3U\n#EXT-X-TARGETDURATION:180\n#EXTINF:180,\na.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:10,\n/ads/x.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:180,\nb.ts\n#EXT-X-ENDLIST\n"
        val master="#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\nvideo.m3u8\n"
        val factory=SafeHlsParserFactory(url,emptySet(),true) {}
        val one=factory.createPlaylistParser().parse(Uri.parse(url),master.byteInputStream()) as androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
        val parsed=factory.createPlaylistParser(one,null).parse(Uri.parse("https://media.example/video.m3u8"),media.byteInputStream()) as HlsMediaPlaylist
        assertEquals(2,parsed.segments.size)
        val two=factory.createPlaylistParser().parse(Uri.parse(url),(master+"#EXT-X-STREAM-INF:BANDWIDTH=2000000\nother.m3u8\n").byteInputStream()) as androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
        val untouched=factory.createPlaylistParser(two,null).parse(Uri.parse("https://media.example/video.m3u8"),media.byteInputStream()) as HlsMediaPlaylist
        assertEquals(3,untouched.segments.size)
    }
}
