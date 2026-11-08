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
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(302).setHeader("Location",canonical.url))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("<html>login</html>"))
            runCatching { network.text(network.api(first,"wd","a"),true) }
            assertEquals(2,distinctSearchDomains(listOf(first,canonical),network::actualDomain).size)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(302).setHeader("Location",canonical.url))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{\"list\":[]}"))
            network.text(network.api(first,"wd","a"),true)
            assertEquals(1,distinctSearchDomains(listOf(first,canonical),network::actualDomain).size)
        } finally { network.close();server.shutdown() }
    }

    @Test fun sameHostIgnoresPathSchemePortCaseAndTrailingDotButNotSubdomains() {
        val best=Source("best","https://EXAMPLE.org:443/api")
        val rows=listOf(best,Source("copy","http://example.org.:8080/other"),Source("other","https://other.example.org/api"),Source("suffix","https://different.co.uk/api"),Source("suffix2","https://another.co.uk/api"))
        assertEquals(listOf(best,rows[2],rows[3],rows[4]),distinctSearchDomains(rows))
        assertEquals(1,distinctSearchDomains(listOf(Source("idn","https://例子.测试/api"),Source("ascii","https://xn--fsqu00a.xn--0zwm56d/other"))).size)
    }
}
