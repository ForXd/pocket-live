package org.streampocket

import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class SourceHealthTest {
    @get:Rule val temp=TemporaryFolder()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    @After fun close() { scope.cancel() }
    @Test fun successesFirstFailuresLastEmptyResultsAreSuccessfulAndRecoveryIsImmediate() {
        val a=Source("A","https://a.test/api");val b=Source("B","https://b.test/api");val c=Source("C","https://c.test/api")
        var time=100L
        val health=SourceHealth(temp.newFile(),scope) { time }
        health.record(a.key,QueryKind.SEARCH,false);health.record(c.key,QueryKind.SEARCH,true)
        assertEquals(listOf(c,b,a),health.ordered(listOf(a,b,c),QueryKind.SEARCH))
        assertEquals(listOf(a,b,c),health.ordered(listOf(a,b,c),QueryKind.CATALOG))
        health.record(a.key,QueryKind.SEARCH,true)
        assertEquals(listOf(a,c,b),health.ordered(listOf(a,b,c),QueryKind.SEARCH))
        time+=SourceHealth.SUCCESS_TTL+1
        assertEquals(listOf(a,b,c),health.ordered(listOf(a,b,c),QueryKind.SEARCH))
    }
    @Test fun failureExpiresAndCacheRoundTripsWithoutRawUrls()=runBlocking {
        var time=100L
        val a=Source("A","https://a.test/api?token=secret");val b=Source("B","https://b.test/api")
        val file=temp.newFile()
        val first=SourceHealth(file,scope) { time }
        first.record(a.key,QueryKind.SEARCH,false);first.flush()
        assertFalse(file.readText().contains("secret"));assertFalse(file.readText().contains("https"))
        val restored=SourceHealth(file,scope) { time };restored.load()
        assertEquals(listOf(b,a),restored.ordered(listOf(a,b),QueryKind.SEARCH))
        time+=SourceHealth.FAILURE_TTL+1
        assertEquals(listOf(a,b),restored.ordered(listOf(a,b),QueryKind.SEARCH))
    }
    @Test fun boundedRecordsCoalescedWriterAndCancelledOwnerLeaveNoActiveWork()=runBlocking {
        val file=temp.newFile();val health=SourceHealth(file,scope) { 100L }
        repeat(1200) { health.record(hash("source$it"),QueryKind.SEARCH,it%2==0) }
        assertEquals(SourceHealth.MAX_ENTRIES,health.size())
        assertTrue(scope.coroutineContext[Job]!!.children.count()<=1)
        health.flush();assertTrue(file.length()<=SourceHealth.MAX_BYTES)
        health.retain(setOf(hash("source1199")));assertEquals(1,health.size())
        scope.coroutineContext[Job]!!.cancelAndJoin()
        assertTrue(scope.coroutineContext[Job]!!.children.none())
    }
}
