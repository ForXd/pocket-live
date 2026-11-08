// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class QueryKind { SEARCH, CATALOG }
internal data class SourceHealthEntry(val key:String,val kind:QueryKind,val success:Boolean,val at:Long)

/** Only hashed identities and recent outcomes; never retain source objects, URLs, or Activity. */
internal class SourceHealth(private val file:File,private val scope:CoroutineScope,private val now:()->Long=System::currentTimeMillis) {
    private val entries=linkedMapOf<String,SourceHealthEntry>()
    private val writeMutex=Mutex()
    private var writer:Job?=null
    private var generation=0L
    private fun id(key:String,kind:QueryKind)="${kind.name}:$key"
    private fun fresh(e:SourceHealthEntry,time:Long)=time-e.at in 0..if(e.success) SUCCESS_TTL else FAILURE_TTL
    @Synchronized private fun prune() {
        val time=now()
        entries.entries.removeAll { !fresh(it.value,time) }
        while(entries.size>MAX_ENTRIES) entries.remove(entries.keys.first())
    }
    @Synchronized fun ordered(sources:List<Source>,kind:QueryKind):List<Source> {
        prune()
        return sources.sortedBy { source -> entries[id(source.key,kind)]?.let { if(it.success) 0 else 2 } ?: 1 }
    }
    @Synchronized fun snapshot():Map<String,Boolean> {
        prune()
        return entries.values.filter { it.kind==QueryKind.SEARCH }.associate { it.key to it.success }
    }
    @Synchronized fun record(sourceKey:String,kind:QueryKind,success:Boolean) {
        val id=id(sourceKey,kind)
        entries.remove(id);entries[id]=SourceHealthEntry(sourceKey,kind,success,now());prune()
        generation++
        if(writer?.isActive!=true) writer=scope.launch {
            while(isActive) {
                delay(500)
                val version=synchronized(this@SourceHealth) { generation }
                flush()
                val done=synchronized(this@SourceHealth) { if(version==generation) { writer=null;true } else false }
                if(done) break
            }
        }
    }
    @Synchronized fun retain(sourceKeys:Set<String>) {
        entries.entries.removeAll { it.value.key !in sourceKeys };prune()
    }
    suspend fun load()=withContext(Dispatchers.IO) {
        try {
            if(!file.exists() || file.length()>MAX_BYTES) return@withContext
            val parsed=file.useLines { lines -> lines.take(MAX_ENTRIES).mapNotNull { line ->
                val parts=line.split('|')
                if(parts.size!=4 || !parts[0].matches(KEY_PATTERN)) null else runCatching {
                    SourceHealthEntry(parts[0],QueryKind.valueOf(parts[1]),parts[2]=="1",parts[3].toLong())
                }.getOrNull()
            }.toList() }
            synchronized(this@SourceHealth) { parsed.forEach { entries[id(it.key,it.kind)]=it };prune() }
        } catch(_:java.io.IOException) { /* A disposable cache cannot prevent app startup. */ }
    }
    suspend fun flush()=withContext(Dispatchers.IO) { writeMutex.withLock {
        val text=synchronized(this@SourceHealth) { prune();entries.values.joinToString("\n") { "${it.key}|${it.kind.name}|${if(it.success) 1 else 0}|${it.at}" } }
        val tmp=File(file.path+".tmp")
        try {
            file.parentFile?.mkdirs();tmp.writeText(text)
            ensureActive()
            Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
        } catch(e:CancellationException) { throw e }
        catch(_:java.io.IOException) { /* Memory ordering still works when disk storage is unavailable. */ }
        finally { tmp.delete() }
    } }
    @Synchronized fun size():Int { prune();return entries.size }
    companion object {
        private val KEY_PATTERN=Regex("[0-9a-f]{64}")
        const val MAX_ENTRIES=600 // 300 configured sources, separate search and catalog outcomes.
        const val MAX_BYTES=128*1024L
        const val FAILURE_TTL=30*60*1000L
        const val SUCCESS_TTL=7*24*60*60*1000L
    }
}
