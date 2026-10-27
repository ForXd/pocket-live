package org.streampocket

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StorageDeviceTest {
    @Test fun splitHistoryBoundsRollbackAndReload()=runBlocking {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(base.cacheDir,"storage-device-test").apply { deleteRecursively();mkdirs() }
        val context=object:ContextWrapper(base) { override fun getFilesDir()=dir }
        var free=Long.MAX_VALUE;var purges=0
        val store=Storage(context,{purges++},{free})
        val source=Source("fixture","https://example.org/api")
        var saved=Saved(listOf(Subscription("s","fixture","https://example.org/sub",1,listOf(source))))
        try {
            store.write(saved)
            val config=File(dir,"state.json");val baseline=config.readBytes();val stamp=config.lastModified()
            repeat(101) { i ->
                saved=StateRules.checkpoint(saved,Playback(Video(source,"$i","movie",poster=if(i%2==0) "https://example.org/poster/$i.jpg" else null),Episode("ep","https://example.org/$i.mp4"),position=i*1000L),i.toLong())
                store.writeHistory(saved)
            }
            assertArrayEquals(baseline,config.readBytes());assertEquals(stamp,config.lastModified())
            assertTrue(File(dir,"history.json").length()<=StorageBudget.HISTORY)
            assertTrue(store.usage()<=StorageBudget.STATE)
            assertEquals(100,Storage(context).read().history.size)
            val restored=Storage(context).read().history
            assertEquals("https://example.org/poster/100.jpg",restored.first { it.playback.video.id=="100" }.playback.video.poster)
            assertNull(restored.first { it.playback.video.id=="99" }.playback.video.poster)
            val before=File(dir,"history.json").readBytes();free=0
            try { store.writeHistory(saved.copy(history=emptyList()));fail("expected low-space rejection") } catch(_:UserError) {}
            assertTrue(purges>0);assertArrayEquals(before,File(dir,"history.json").readBytes())
            assertEquals(100,Storage(context).read().history.size)
            free=Long.MAX_VALUE;store.write(saved.copy(history=emptyList()))
            assertTrue(Storage(context).read().history.isEmpty())
            val stable=config.readBytes()
            val huge=List(300) { i -> source.copy(url="https://example.org/api?id=$i&token="+"x".repeat(3900),detail="y".repeat(4096)) }
            try { store.write(saved.copy(subscriptions=listOf(saved.subscriptions.single().copy(sources=huge))));fail("expected configuration byte limit") } catch(_:UserError) {}
            assertArrayEquals(stable,config.readBytes())
            File(dir,"history.json").writeBytes(byteArrayOf(0xc3.toByte(),0x28))
            try { Storage(context).read();fail("expected malformed UTF-8 rejection") } catch(_:UserError) {}
            assertEquals(2L,File(dir,"history.json").length())
        } finally { dir.deleteRecursively() }
    }
}
