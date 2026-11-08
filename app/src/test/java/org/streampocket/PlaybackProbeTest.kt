package org.streampocket
import org.junit.Test
import org.junit.Assert.*
class PlaybackProbeTest {
    @Test fun resolutionRequiresExplicitBoundedVariantDeclaration() {
        assertEquals("1920×1080",probeResolution("#EXT-X-STREAM-INF:BANDWIDTH=123,RESOLUTION=1920x1080,CODECS=avc1"))
        assertNull(probeResolution("#EXT-X-STREAM-INF:BANDWIDTH=1080"))
        assertNull(probeResolution("#EXT-X-STREAM-INF:RESOLUTION=99999x1080"))
    }

    @Test fun rejectsHttpErrorPagesAndChecksContainerSignature() {
        assertFalse(validMediaPrefix("<html>Login required</html>".toByteArray()))
        assertFalse(validMediaPrefix(ByteArray(400)))
        val ts=ByteArray(400);ts[0]=0x47;ts[188]=0x47;ts[376]=0x47
        assertTrue(validMediaPrefix(ts))
        assertTrue(validMediaPrefix(byteArrayOf(0,0,0,24)+"ftypisom".toByteArray()))
        assertFalse(validMediaPrefix("ftyp".toByteArray()))
    }
    @Test fun encryptedMediaStaysUnknownAndIgnoredRangeRemainsBounded()=kotlinx.coroutines.runBlocking {
        val server=okhttp3.mockwebserver.MockWebServer();server.start()
        val base="http://probe.test:${server.port}"
        val network=Network({true},object:okhttp3.Dns { override fun lookup(hostname:String)=listOf(java.net.InetAddress.getByName("127.0.0.1")) })
        val source=Source("test","$base/api")
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"list":[{"vod_play_from":"line","vod_play_url":"first${'$'}$base/encrypted.m3u8"}]}"""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:3,\nsegment.ts\n"))
            assertEquals(ProbeStatus.UNKNOWN,PlaybackProbe(network).check(Video(source,"1","test")))
            assertEquals(2,server.requestCount)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"list":[{"vod_play_from":"line","vod_play_url":"first${'$'}$base/movie.mp4"}]}"""))
            val bytes=ByteArray(200_000);"ftyp".toByteArray().copyInto(bytes,4)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(bytes)))
            assertEquals(ProbeStatus.READY,PlaybackProbe(network).check(Video(source,"2","test")))
        } finally { network.close();server.shutdown() }
    }

}
