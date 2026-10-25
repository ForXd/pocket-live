package org.streampocket

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import com.google.gson.JsonParser

class HistoryGroupsDeviceTest {
    @Test fun legacyHistoryResumeDeleteAndReload()=runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation();val base=instrument.targetContext
        val dir=File(base.cacheDir,"code7-history-test").apply { deleteRecursively();mkdirs() }
        val app=object:Application() {
            init { attachBaseContext(base) }
            override fun getFilesDir()=File(dir,"files").apply { mkdirs() }
            override fun getCacheDir()=File(dir,"cache").apply { mkdirs() }
            override fun getApplicationContext():Context=this
        }
        val owner=object:ViewModelStoreOwner { override val viewModelStore=ViewModelStore() }
        val source=Source("源一","https://one.example/api")
        fun entry(id:String,episode:Int,sourceValue:Source=source)=History(Playback(Video(sourceValue,id,"同名剧"),Episode("第${episode}集","https://media.example/$id/$episode.mp4"),episode*10000L),episode.toLong())
        val a=entry("a",1);val a2=entry("a",2);val b=entry("b",3);val other=entry("a",4,source.copy(url="https://two.example/api"))
        val initial=Saved(history=listOf(other,b,a2,a))
        val storage=Storage(app);storage.write(initial)
        // Recreate the supported legacy embedded-history format, with no split file.
        val state=File(app.filesDir,"state.json");val hist=File(app.filesDir,"history.json")
        val json=JsonParser.parseString(state.readText()).asJsonObject
        json.add("history",JsonParser.parseString(hist.readText()));state.writeText(json.toString());assertTrue(hist.delete())
        assertEquals(4,Storage(app).read().history.size)
        lateinit var model:AppModel
        fun main(block:()->Unit)=instrument.runOnMainSync(block)
        fun await(test:()->Boolean) {
            val end=android.os.SystemClock.elapsedRealtime()+90000
            while(!test()) { if(android.os.SystemClock.elapsedRealtime()>end) fail("history state timeout");Thread.sleep(50) }
        }
        try {
            main { model=ViewModelProvider(owner,object:ViewModelProvider.Factory {
                override fun <T:ViewModel> create(modelClass:Class<T>):T { @Suppress("UNCHECKED_CAST") return AppModel(app) as T }
            })[AppModel::class.java] }
            await { !model.state.value.loading };assertFalse(model.state.value.loadFailed)
            assertEquals(3,HistoryGroups.group(model.state.value.saved.history).size)
            lateinit var old:Playback
            main { model.resume(a2);old=model.state.value.playback!!;model.checkpoint(old,25000) }
            await { model.state.value.saved.history.any { it.playback.key==a2.playback.key && it.playback.position==25000L } }
            main { model.resume(b);assertEquals(30000L,model.state.value.playback!!.position);model.checkpoint(model.state.value.playback!!,35000) }
            await { model.state.value.saved.history.any { it.playback.key==b.playback.key && it.playback.position==35000L } }
            main { model.resume(model.state.value.saved.history.first { it.playback.key==a2.playback.key });assertEquals(25000L,model.state.value.playback!!.position);old=model.state.value.playback!!;model.removeHistoryGroup(HistoryGroups.key(a.playback.video));model.checkpoint(old,99999) }
            await { model.state.value.saved.history.none { HistoryGroups.key(it.playback.video)==HistoryGroups.key(a.playback.video) } }
            assertEquals(2,Storage(app).read().history.size)
            assertTrue(model.state.value.saved.history.any { it.playback.video.source.key==other.playback.video.source.key })
            main { model.clearHistory();model.checkpoint(old,88888) }
            await { model.state.value.saved.history.isEmpty() };assertTrue(Storage(app).read().history.isEmpty())
            val live=entry("channel",1,source.copy(kind=SourceKind.LIVE))
            main { model.resume(live);assertEquals(0L,model.state.value.playback!!.position);model.stopPlayback() }
            assertTrue(hist.length()<=StorageBudget.HISTORY)
            instrument.sendStatus(0,android.os.Bundle().apply { putString("history_result","legacy read;two-series independent resume;source identity;delete/clear late callback;live zero;reload passed") })
        } finally { main { owner.viewModelStore.clear() };dir.deleteRecursively() }
    }
}
