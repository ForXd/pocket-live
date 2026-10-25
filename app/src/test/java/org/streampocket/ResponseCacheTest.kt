package org.streampocket

import java.nio.file.Files
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ResponseCacheTest {
    private fun fixture(test: (File)->Unit) {
        val root=Files.createTempDirectory("pocket-cache-").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }
    @Test fun ttlLruAndRestartStayBounded()=fixture { dir ->
        var time=1000L
        val c=ResponseCache(dir,{time},diskLimit=100,memoryLimit=0,available={Long.MAX_VALUE})
        c.put(hash("a"),"a".repeat(20),c.generation());time++
        c.put(hash("b"),"b".repeat(20),c.generation());time++
        assertNotNull(c.get(hash("a")));time++
        c.put(hash("c"),"c".repeat(20),c.generation())
        assertNull(c.get(hash("b")));assertNotNull(c.get(hash("a")))
        assertTrue(c.usage().diskBytes<=100)
        val restarted=ResponseCache(dir,{time},diskLimit=100,memoryLimit=0,available={Long.MAX_VALUE})
        assertNotNull(restarted.get(hash("a")))
        time+=60_001;assertNull(restarted.get(hash("a")));assertEquals(0L,restarted.usage().diskBytes)
    }
    @Test fun clearRejectsInFlightOwnerAndPreservesUnrelatedConfiguration()=fixture { dir ->
        val config=File(dir.parentFile,dir.name+"-state.json");config.writeText("user config")
        try {
            val c=ResponseCache(dir,available={Long.MAX_VALUE});val old=c.generation()
            c.put(hash("a"),"{}",old);c.clear();c.put(hash("late"),"{}",old)
            assertNull(c.get(hash("late")));assertEquals("user config",config.readText())
            repeat(100) { c.clear() };assertEquals(0,c.usage().entries)
        } finally { config.delete() }
    }
    @Test fun repeatedOperationsMemoryAndDiskCaps()=fixture { dir ->
        var time=1000L
        val c=ResponseCache(dir,{time},diskLimit=4096,memoryLimit=1024,available={Long.MAX_VALUE})
        repeat(300) { i ->
            c.put(hash("$i"),"x".repeat(64),c.generation());time++
            val u=c.usage();assertTrue(u.diskBytes<=4096);assertTrue(u.memoryBytes<=1024);assertTrue(u.entries<=64)
        }
        c.trimMemory();assertEquals(0L,c.usage().memoryBytes)
    }
    @Test fun lowSpaceOversizeCorruptionAndTemporaryCleanup()=fixture { dir ->
        val c=ResponseCache(dir,available={0})
        c.put(hash("x"),"{}",c.generation());assertEquals(0L,c.usage().diskBytes)
        c.put(hash("large"),"x".repeat(JsonData.LIMIT+1),c.generation());assertNull(c.get(hash("large")))
        File(dir,"interrupted.tmp").writeText("temporary")
        File(dir,hash("bad")+".cache").writeText("bad header")
        c.maintain();assertEquals(0,dir.listFiles()!!.size)
    }
    @Test fun serverShorterTtlAndBackwardsClockExpire()=fixture { dir ->
        var time=1000L;val c=ResponseCache(dir,{time},available={Long.MAX_VALUE})
        c.put(hash("short"),"{}",c.generation(),ttl=100);time+=101
        assertNull(c.get(hash("short")))
        c.put(hash("clock"),"{}",c.generation());time=0
        assertNull(c.get(hash("clock")))
    }
    @Test fun productionByteAndEntryBudgets()=fixture { dir ->
        var time=1000L
        val c=ResponseCache(dir,{time},available={Long.MAX_VALUE})
        repeat(70) { c.put(hash("small-$it"),"x".repeat(100),c.generation());time++ }
        assertEquals(64,c.usage().entries)
        assertNull(c.get(hash("small-0")))
        repeat(12) {
            c.put(hash("large-$it"),"x".repeat(JsonData.LIMIT-16),c.generation());time++
            assertTrue(c.usage().diskBytes<=StorageBudget.DISK_RESPONSES)
            assertTrue(c.usage().memoryBytes<=StorageBudget.MEMORY_RESPONSES)
        }
        c.clear();assertEquals(0L,c.usage().diskBytes)
    }

}
