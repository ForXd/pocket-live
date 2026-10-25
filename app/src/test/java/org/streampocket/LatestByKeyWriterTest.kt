package org.streampocket

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatestByKeyWriterTest {
    @Test fun slowWriterIsBoundedAndCommitsLatestValue()=runTest {
        val gate=CompletableDeferred<Unit>();val writes=mutableListOf<Int>()
        val writer=LatestByKeyWriter<Int>(backgroundScope,3,{ n -> gate.await();writes+=n },{throw it})
        writer.submit("active",-1);runCurrent()
        repeat(10000) { writer.submit("same",it) }
        assertEquals(1,writer.pendingCount)
        gate.complete(Unit);runCurrent()
        assertEquals(listOf(-1,9999),writes)
    }
    @Test fun distinctKeysBoundedAndClearDropsPending()=runTest {
        val gate=CompletableDeferred<Unit>();val writes=mutableListOf<Int>()
        val writer=LatestByKeyWriter<Int>(backgroundScope,3,{ n -> gate.await();writes+=n },{throw it})
        writer.submit("active",-1);runCurrent()
        repeat(1000) { writer.submit("$it",it);assertTrue(writer.pendingCount<=3) }
        writer.clear();gate.complete(Unit);runCurrent()
        assertEquals(listOf(-1),writes)
    }
    @Test fun ownerCancellationFlushesAcceptedFinalCheckpoint()=runTest {
        val owner=SupervisorJob();val scope=CoroutineScope(owner+StandardTestDispatcher(testScheduler))
        val writes=mutableListOf<Int>();val gate=CompletableDeferred<Unit>()
        val writer=LatestByKeyWriter<Int>(scope,3,{ n -> gate.await();writes+=n },{throw it})
        writer.submit("first",1);runCurrent()
        writer.submit("last",2);owner.cancel();gate.complete(Unit);runCurrent()
        assertEquals(listOf(1,2),writes);assertTrue(owner.isCompleted)
    }

    @Test fun removingSelectedPendingKeysPreservesOtherSeries()=runTest {
        val gate=CompletableDeferred<Unit>();val writes=mutableListOf<Int>()
        val writer=LatestByKeyWriter<Int>(backgroundScope,3,{ n -> gate.await();writes+=n },{throw it})
        writer.submit("active",0);runCurrent()
        writer.submit("deleted",1);writer.submit("other",2)
        writer.remove(setOf("deleted"));gate.complete(Unit);runCurrent()
        assertEquals(listOf(0,2),writes)
    }

}
