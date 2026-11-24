package org.streampocket

import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class PlaybackProbeTest {
    @Test fun extensionlessPlaylistHintUsesOrdinaryGetAndDoesNotClassifyPlayability() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/actual"))
        server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:4,\ninvalid.ts"))
        server.enqueue(MockResponse().setBody("<html>Not a playlist</html>"))
        server.start()
        val dns = object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
        }
        val network = Network({ true }, dns)
        try {
            val detector = PlaybackProbe(network)
            val url = "http://trial.test:${server.port}/media"
            assertEquals("application/x-mpegURL", detector.mediaType(url))
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertNull(request.getHeader("Range"))
            assertNull(detector.mediaType(url))
        } finally {
            network.close()
            server.shutdown()
        }
    }
}
