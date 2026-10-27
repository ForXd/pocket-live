package org.streampocket
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
class UpdateNetworkTest {
    @Test fun onlyOfficialHttpsHostsAreAllowedIncludingEveryRedirect() {
        for(host in listOf("github.com","api.github.com","release-assets.githubusercontent.com","objects.githubusercontent.com")) UpdateNetwork.requireOfficial("https://$host/asset".toHttpUrl())
        for(url in listOf("http://github.com/a","https://github.com:444/a","https://github.com.evil.test/a","https://127.0.0.1/a","https://user:pass@github.com/a","https://example.org/a")) {
            try { UpdateNetwork.requireOfficial(url.toHttpUrl());fail(url) } catch(_:IOException) {}
        }
        val network=UpdateNetwork()
        try { assertSame(okhttp3.Dns.SYSTEM,network.client.dns);assertNotSame(PublicNetwork.dns,network.client.dns) } finally { network.close() }
    }
    @Test fun rateLimitUsesReleaseManifestButCancellationNeverFallsBack()=runBlocking {
        val calls=mutableListOf<String>()
        val result=UpdateNetwork.latest { url -> calls+=url;if(url==AppUpdates.API) throw UserError("HTTP 403");"release manifest" }
        assertEquals("release manifest",result);assertEquals(listOf(AppUpdates.API,UpdateNetwork.MANIFEST),calls)
        calls.clear()
        try { UpdateNetwork.latest { url -> calls+=url;throw CancellationException() };fail() } catch(_:CancellationException) {}
        assertEquals(listOf(AppUpdates.API),calls)
    }
}
