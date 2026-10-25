// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import java.io.*

object StorageBudget {
    const val MIB = 1024 * 1024L
    const val MEMORY_RESPONSES = 2 * MIB
    const val DISK_RESPONSES = 8 * MIB
    const val CONFIG = 2 * MIB
    const val HISTORY = MIB
    const val STATE = 4 * MIB
    const val FREE_RESERVE = 16 * MIB
    const val RESPONSE_TTL = 60_000L
    const val POSTERS = 20_000_000L
    const val TOTAL_DISK = 50_000_000L // Includes atomic staging; no persistent media.
    const val POSTER_MEMORY = 6_000_000
    const val VIDEO_DISK = 0L // Streaming only; no SimpleCache/download service.
    fun recordBytes(p: Playback): Long {
        // Conservative UTF-8 JSON upper bound: every UTF-16 char may need a 6-byte escape.
        val v = p.video; val s = v.source
        return 1536L + 6L * (v.id.length + v.title.length + v.subtitle.length + p.episode.name.length + p.episode.url.length +
            s.name.length + s.url.length + (s.detail?.length ?: 0) + (s.epg?.length ?: 0))
    }
}
data class CacheUsage(val memoryBytes: Long, val diskBytes: Long, val entries: Int)

/** App-private cache. Filenames are SHA256 only; no URL/headers stored in cache metadata.
 * All entrypoints run on IO (or tests), serialized by one monitor. Disk cache is expendable.
 * No stale-on-error. TTL and LRU are both enforced, including after restart. */
class ResponseCache(private val directory: File, private val now: () -> Long = System::currentTimeMillis,
    private val diskLimit: Long = StorageBudget.DISK_RESPONSES,
    private val memoryLimit: Long = StorageBudget.MEMORY_RESPONSES,
    private val available: () -> Long = { directory.usableSpace }) {
    private data class Entry(val value: String, val created: Long, val expires: Long) {
        val weight: Long get() = value.length * 2L + 256 // logical UTF-16 payload plus accounting allowance
    }
    private val memory = LinkedHashMap<String, Entry>(16,0.75f,true)
    private val generation = java.util.concurrent.atomic.AtomicLong()
    fun generation(): Long = generation.get()
    fun invalidate() { generation.incrementAndGet() }
    private fun bytes() = memory.values.sumOf { it.weight }
    private fun files() = directory.listFiles()?.filter { it.isFile }.orEmpty()
    private fun delete(file: File) { if(file.exists() && !file.delete()) throw IOException("Cache cleanup failed") }
    private fun live(file: File): Boolean = try {
        if(!file.name.matches(Regex("[a-f0-9]{64}\\.cache")) || file.length() !in 16..(JsonData.LIMIT+16L)) false
        else DataInputStream(file.inputStream()).use { input ->
            val created=input.readLong(); val expiry=input.readLong(); val time=now()
            created <= time && time < expiry && expiry-created in 1..StorageBudget.RESPONSE_TTL
        }
    } catch(_:IOException) { false }
    @Synchronized fun maintain() {
        directory.mkdirs()
        files().filterNot(::live).forEach(::delete) // also removes crash-left temporary files
        val time=now(); memory.entries.removeAll { time < it.value.created || time >= it.value.expires }
        trimDisk(0,0)
    }
    private fun trimDisk(incoming: Long, incomingEntries: Int) {
        val entries=files().sortedBy { it.lastModified() }.toMutableList()
        var total=entries.sumOf { it.length() }
        while(total+incoming > diskLimit || entries.size+incomingEntries > 64) {
            val oldest=entries.removeFirstOrNull() ?: break
            total-=oldest.length(); delete(oldest)
        }
    }
    @Synchronized fun get(key: String): String? {
        require(key.matches(Regex("[a-f0-9]{64}")))
        memory[key]?.let {
            val time=now()
            if(time>=it.created && time<it.expires) { File(directory,"$key.cache").setLastModified(time); return it.value }
            memory.remove(key)
        }
        maintain()
        val file=File(directory,"$key.cache")
        if(!file.exists()) return null
        return try {
            DataInputStream(file.inputStream()).use { input ->
                val created=input.readLong(); val expires=input.readLong()
                val data=ByteArray((file.length()-16).toInt()); input.readFully(data)
                val value=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(data)).toString()
                putMemory(key,Entry(value,created,expires)); file.setLastModified(now()); value
            }
        } catch(_:IOException) { delete(file); null }
    }
    private fun putMemory(key: String, value: Entry) {
        memory.remove(key)
        if(value.weight > 256*1024L || value.weight > memoryLimit) return
        memory[key]=value
        while(bytes() > memoryLimit || memory.size > 16) memory.remove(memory.keys.first())
    }
    @Synchronized fun put(key: String, value: String, owner: Long, ttl: Long = StorageBudget.RESPONSE_TTL) {
        require(key.matches(Regex("[a-f0-9]{64}")))
        if(owner != generation.get() || ttl <= 0) return // Clear pressed while this network request was in flight.
        maintain()
        val data=value.toByteArray(Charsets.UTF_8)
        if(data.size > JsonData.LIMIT || data.size+16L > diskLimit) return
        val time=now(); val entry=Entry(value,time,time+ttl.coerceAtMost(StorageBudget.RESPONSE_TTL))
        putMemory(key,entry)
        if(available() < StorageBudget.FREE_RESERVE + data.size+16L) return // cache is optional
        val final=File(directory,"$key.cache"); val temp=File(directory,"$key.tmp")
        delete(final); trimDisk(data.size+16L,1) // budget includes in-progress file; no unbounded staging
        try {
            FileOutputStream(temp).use { stream ->
                val output=DataOutputStream(stream)
                output.writeLong(entry.created); output.writeLong(entry.expires); output.write(data); output.flush()
                stream.fd.sync()
            }
            if(!temp.renameTo(final)) throw IOException("Cache commit failed")
            final.setLastModified(time)
        } finally { if(temp.exists()) delete(temp) }
    }
    @Synchronized fun clear() {
        invalidate(); memory.clear(); files().forEach(::delete)
    }
    @Synchronized fun trimMemory() { memory.clear() }
    @Synchronized fun usage(): CacheUsage { maintain(); return CacheUsage(bytes(),files().sumOf { it.length() },files().size) }
}
