package org.streampocket
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*

class MediaCallOwnerTest {
    @Test fun completionRacingCloseUsesStableSnapshot() {
        val base=OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(ResponseBody.create(null,"fixture")).build()
        }.build()
        val workers=Executors.newFixedThreadPool(4)
        try {
            repeat(300) {
                val owner=MediaCallOwner(base)
                val calls=List(8) { owner.newCall(Request.Builder().url("https://fixture.test/").build()) }
                val gate=CountDownLatch(1)
                val tasks=calls.map { call -> workers.submit {
                    gate.await()
                    try { call.execute().use { it.body!!.string() } } catch(_:java.io.IOException) { }
                } }
                gate.countDown();owner.close()
                tasks.forEach { it.get(2,TimeUnit.SECONDS) }
                owner.close();assertEquals(0,MediaCallOwner.active.get())
            }
        } finally { workers.shutdownNow();base.connectionPool.evictAll();base.dispatcher.executorService.shutdown() }
    }

    @Test fun cancelBlockedMediaWithoutCancellingSharedAppClient() {
        val server=MockWebServer();server.start()
        val base=OkHttpClient()
        val executor=Executors.newSingleThreadExecutor()
        val owner=MediaCallOwner(base)
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending=executor.submit<Boolean> {
                try { owner.newCall(Request.Builder().url(server.url("/slow")).build()).execute().use {};false }
                catch(_:java.io.IOException) { true }
            }
            assertNotNull(server.takeRequest(2,TimeUnit.SECONDS))
            owner.close()
            assertTrue(pending.get(2,TimeUnit.SECONDS));assertEquals(0,MediaCallOwner.active.get())
            server.enqueue(MockResponse().setBody("still usable"))
            base.newCall(Request.Builder().url(server.url("/metadata")).build()).execute().use { assertEquals("still usable",it.body!!.string()) }
            assertFalse(base.dispatcher.executorService.isShutdown)
        } finally { owner.close();executor.shutdownNow();base.connectionPool.evictAll();base.dispatcher.executorService.shutdown();server.shutdown() }
    }
    @Test fun completedCallsAndRepeatedCloseNeverAccumulate() {
        val server=MockWebServer();server.start();val base=OkHttpClient()
        try {
            repeat(30) {
                val owner=MediaCallOwner(base)
                server.enqueue(MockResponse().setBody("body"))
                owner.newCall(Request.Builder().url(server.url("/")).build()).execute().use { assertEquals("body",it.body!!.string()) }
                assertEquals(0,MediaCallOwner.active.get());owner.close();owner.close()
                assertTrue(owner.newCall(Request.Builder().url(server.url("/")).build()).isCanceled())
                assertEquals(0,MediaCallOwner.active.get())
            }
        } finally { base.connectionPool.evictAll();base.dispatcher.executorService.shutdown();server.shutdown() }
    }
}
