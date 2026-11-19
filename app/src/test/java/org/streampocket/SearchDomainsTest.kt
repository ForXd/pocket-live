package org.streampocket
import org.junit.Test
import org.junit.Assert.*
class SearchDomainsTest {
    @Test fun observedRedirectOnlyMergesAfterValidCmsResponse()=kotlinx.coroutines.runBlocking {
        val server=okhttp3.mockwebserver.MockWebServer();server.start()
        val network=Network({true},object:okhttp3.Dns { override fun lookup(hostname:String)=listOf(java.net.InetAddress.getByName("127.0.0.1")) })
        val first=Source("a","http://alias.test:${server.port}/api")
        val canonical=Source("b","http://actual.test:${server.port}/api")
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(302).setHeader("Location",canonical.url+"?ac=videolist&wd=a"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("<html>login</html>"))
            runCatching { network.text(network.api(first,"wd","a"),true) }
            assertEquals(2,distinctSearchEndpoints(listOf(first,canonical),network::actualSearchEndpoint).size)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(302).setHeader("Location",canonical.url+"?ac=videolist&wd=a"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{\"list\":[]}"))
            network.text(network.api(first,"wd","a"),true)
            assertEquals(1,distinctSearchEndpoints(listOf(first,canonical),network::actualSearchEndpoint).size)
        } finally { network.close();server.shutdown() }
    }

    @Test fun independentInterfacesOnOneHostAreSearched() {
        val a=Source("A","https://example.org/api?key=1")
        val sources=listOf(a,a.copy(name="copy"),a.copy(url="https://example.org/other?key=1"),
            a.copy(url="https://example.org/api?key=2"),a.copy(url="https://example.org:8443/api?key=1"))
        assertEquals(listOf(a)+sources.drop(2),distinctSearchEndpoints(sources))
    }
}
