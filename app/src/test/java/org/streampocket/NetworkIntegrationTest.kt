package org.streampocket

import kotlinx.coroutines.*
import okhttp3.Dns
import okhttp3.mockwebserver.*
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class NetworkIntegrationTest {
    private fun fixture(server:MockWebServer)=server.url("/").newBuilder().host("fixture.test").build().toString()
    private fun network()=Network({true},object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1")) })
    @Test fun fetchParseAndQueryEncoding()=runBlocking {
        val server=MockWebServer(); val net=network()
        try {
            server.enqueue(MockResponse().setBody("""{"sources":[{"url":"https://example.org/api"}]}"""))
            assertEquals(1,SubscriptionParser.parse(net.text(fixture(server))).sources.size)
            val url=net.api(Source("x","https://example.org/api?token=secret"),"wd","a & b")
            assertTrue(url.contains("token=secret")); assertTrue(url.contains("wd=a%20%26%20b"))
        } finally { net.close(); server.shutdown() }
    }
    @Test fun redirectLoopAndSizeLimit()=runBlocking {
        val server=MockWebServer(); val net=network()
        try {
            repeat(4){server.enqueue(MockResponse().setResponseCode(302).addHeader("Location","/"))}
            try { net.text(fixture(server)); fail() } catch(_:Exception) {}
            server.enqueue(MockResponse().setBody("x".repeat(JsonData.LIMIT+1)))
            try { net.text(fixture(server)); fail() } catch(e:UserError) { assertTrue(e.message!!.contains("2 MiB")) }
        } finally { net.close(); server.shutdown() }
    }
    @Test fun cancellingSlowResponseClosesCall()=runBlocking {
        val server=MockWebServer(); val net=network()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job=launch { net.text(fixture(server)) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2,TimeUnit.SECONDS)) }
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            withTimeout(2000) { while(net.client.dispatcher.runningCallsCount() != 0) delay(10) }
        } finally { net.close(); server.shutdown() }
    }
    @Test fun invalidUtf8AndGzipExpansionAreRejected()=runBlocking {
        val server=MockWebServer(); val net=network()
        try {
            server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(0xc3.toByte(),0x28))))
            try { net.text(fixture(server)); fail() } catch(_:java.nio.charset.CharacterCodingException) {}
            val compressed=java.io.ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(compressed).use { it.write(ByteArray(JsonData.LIMIT+1) { 65 }) }
            server.enqueue(MockResponse().addHeader("Content-Encoding","gzip").setBody(okio.Buffer().write(compressed.toByteArray())))
            try { net.text(fixture(server)); fail() } catch(e:UserError) { assertTrue(e.message!!.contains("2 MiB")) }
        } finally { net.close(); server.shutdown() }
    }
    @Test fun publicHostRedirectToLoopbackIsBlockedBeforeSecondRequest()=runBlocking {
        val server=MockWebServer(); val net=network()
        try {
            server.start()
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location","http://127.0.0.1:${server.port}/private"))
            try { net.text(fixture(server)); fail() } catch(_:java.io.IOException) {}
            assertEquals(1,server.requestCount)
        } finally { net.close(); server.shutdown() }
    }
    @Test fun searchCacheHonorsNoStoreAndOtherRequestsBypass()=runBlocking {
        val server=MockWebServer();val root=java.nio.file.Files.createTempDirectory("network-cache").toFile()
        val cache=ResponseCache(root,available={Long.MAX_VALUE})
        val net=Network({true},object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1")) },cache)
        try {
            server.enqueue(MockResponse().setBody("{\"list\":[]}").addHeader("Cache-Control","no-store"))
            net.text(fixture(server),cacheSearch=true);assertEquals(0,cache.usage().entries)
            server.enqueue(MockResponse().setBody("{\"list\":[]}"))
            net.text(fixture(server),cacheSearch=true)
            net.text(fixture(server),cacheSearch=true);assertEquals(2,server.requestCount)
            server.enqueue(MockResponse().setBody("{}"))
            net.text(fixture(server));assertEquals(3,server.requestCount)
        } finally { net.close();server.shutdown();root.deleteRecursively() }
    }
    @Test fun localAddressesBlocked() {
        listOf("127.0.0.1","10.0.0.1","192.168.0.1","169.254.169.254","100.64.0.1","::1","fc00::1","fe80::1").forEach { assertFalse(PublicNetwork.publicAddress(InetAddress.getByName(it))) }
        assertTrue(PublicNetwork.publicAddress(InetAddress.getByName("8.8.8.8")))
    }
    @Test fun clearDuringActualDownloadPreventsRefill()=runBlocking {
        val server=MockWebServer();val root=java.nio.file.Files.createTempDirectory("inflight-cache").toFile()
        val cache=ResponseCache(root,available={Long.MAX_VALUE})
        val net=Network({true},object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) },cache)
        try {
            server.enqueue(MockResponse().setBody("{\"list\":[]}").setBodyDelay(300,TimeUnit.MILLISECONDS))
            val response=async { net.text(fixture(server),cacheSearch=true) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2,TimeUnit.SECONDS)) }
            cache.clear()
            assertEquals("{\"list\":[]}",response.await())
            assertEquals(0,cache.usage().entries)
        } finally { net.close();server.shutdown();root.deleteRecursively() }
    }
    @Test fun noCacheAndExpiredAgeDoNotPersist()=runBlocking {
        val server=MockWebServer();val root=java.nio.file.Files.createTempDirectory("headers-cache").toFile()
        val cache=ResponseCache(root,available={Long.MAX_VALUE})
        val net=Network({true},object:Dns { override fun lookup(hostname:String)=listOf(InetAddress.getByName("127.0.0.1")) },cache)
        try {
            for(header in listOf("no-cache","max-age=1")) {
                server.enqueue(MockResponse().setBody("{\"list\":[]}").addHeader("Cache-Control",header).addHeader("Age","2"))
                net.text(fixture(server),cacheSearch=true);assertEquals(0,cache.usage().entries)
            }
        } finally { net.close();server.shutdown();root.deleteRecursively() }
    }

}
