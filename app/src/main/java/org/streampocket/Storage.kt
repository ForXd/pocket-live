// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import android.content.Context
import android.util.AtomicFile
import com.google.gson.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Explicit schema: no reflective serialization, no URL logging, no backup export.
class Storage(context: Context, private val purgeCache: () -> Unit = {}, private val available: () -> Long = { context.filesDir.usableSpace }) {
    private val file = AtomicFile(File(context.filesDir, "state.json"))
    private val historyFile=AtomicFile(File(context.filesDir,"history.json"))
    private var lastConfig: String?=null
    private var lastHistory: String?=null
    suspend fun usage(): Long = withContext(Dispatchers.IO) {
        listOf(file,historyFile).flatMap { listOf(it.baseFile,File(it.baseFile.path+".new"),File(it.baseFile.path+".bak")) }.sumOf { if(it.exists()) it.length() else 0 }
    }
    suspend fun read(): Saved = withContext(Dispatchers.IO) {
        // No writer exists before initialization. Incomplete .new is never committed user data.
        val stale = File(file.baseFile.path+".new")
        if(stale.exists() && !stale.delete()) throw UserError("无法清理未完成的临时文件")
        if(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withContext Saved()
        if(file.baseFile.length() > 4 * 1024 * 1024) throw UserError("本地存储异常，请在系统设置清除数据")
        try {
            val text = file.openRead().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while(true) {
                    val count = input.read(chunk); if(count < 0) break
                    if(out.size() + count > 4 * 1024 * 1024) throw UserError("本地存储超限")
                    out.write(chunk, 0, count)
                }
                val bytes = out.toByteArray()
                if(bytes.size > 4 * 1024 * 1024) throw UserError("本地存储超限")
                Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            }
            val r = JsonData.parse(text, 4 * 1024 * 1024).asJsonObject
            if(historyFile.baseFile.exists() || File(historyFile.baseFile.path+".bak").exists()) {
                val data=historyFile.openRead().use { input ->
                    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true) { val n=input.read(buffer);if(n<0) break; if(out.size()+n>StorageBudget.HISTORY) throw UserError("历史记录超限");out.write(buffer,0,n) };out.toByteArray() }
                if(data.size>StorageBudget.HISTORY) throw UserError("历史记录超限")
                r.add("history",JsonData.parse(Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(data)).toString(),StorageBudget.HISTORY.toInt()))
            }
            if(r.scalar("version") != "1") throw UserError("本地数据版本不支持")
            val saved = Saved(r.arr("subscriptions").take(20).map { raw ->
                val s = raw.asJsonObject
                Subscription(s.scalar("id"), s.scalar("name"), s.scalar("url"), s.scalar("updated").toLong(), s.arr("sources").map { source(it.asJsonObject) })
            }, r.arr("disabled").map { it.asString }.toSet(), r.arr("history").take(100).map {
                val h = it.asJsonObject; val v = h.getAsJsonObject("video"); val ep = h.getAsJsonObject("episode")
                History(Playback(Video(source(v.getAsJsonObject("source")),v.scalar("id"),v.scalar("title"),poster=validUrl(v.scalar("poster")),quality=QualityRules.read(v.get("quality")?.obj())),Episode(ep.scalar("name"),ep.scalar("url"),QualityRules.read(ep.get("quality")?.obj())),h.scalar("position").toLong().coerceAtLeast(0)),h.scalar("updated").toLong())
            },r.bool("allowHttp") == true, r.bool("adFilter") == true, r.get("adRules")?.obj()?.entrySet()?.take(300)?.associate { (key,value) -> key to value.asJsonArray.take(32).map { it.asString }.filter { it.length <= 512 && it.startsWith("/") } }.orEmpty())
            require(r.arr("subscriptions").size <= 20 && r.arr("history").size <= 100)
            require(saved.subscriptions.sumOf { it.sources.size } <= 300)
            require(saved.subscriptions.map { it.id }.distinct().size == saved.subscriptions.size)
            require(saved.subscriptions.all { it.id.isNotBlank() && validUrl(it.url) != null && it.name.length <= 120 })
            require(saved.history.all { validUrl(it.playback.episode.url) != null && it.playback.video.title.length <= 200 })
            require(r.getAsJsonArray("subscriptions").toString().toByteArray().size <= StorageBudget.CONFIG)
            require(r.getAsJsonArray("history").toString().toByteArray().size <= StorageBudget.HISTORY)
            saved.copy(disabled = saved.disabled.intersect(saved.subscriptions.flatMap { it.sources }.map { it.key }.toSet()))
        } catch(e: CancellationException) { throw e } catch(e: Exception) { throw UserError("本地数据损坏；未覆盖，请在系统设置清除应用数据后重试") }
    }
    suspend fun write(s: Saved) = withContext(Dispatchers.IO) {
        val root = JsonObject().apply {
            addProperty("version",1); addProperty("allowHttp",s.allowHttp); addProperty("adFilter",s.adFilter)
            add("adRules",JsonObject().apply { s.adRules.entries.take(300).forEach { (key,paths) -> add(key,JsonArray().apply { paths.take(32).forEach { add(it) } }) } })
            add("subscriptions", JsonArray().apply { s.subscriptions.forEach { sub -> add(JsonObject().apply {
                addProperty("id",sub.id); addProperty("name",sub.name); addProperty("url",sub.url); addProperty("updated",sub.updated)
                add("sources", JsonArray().apply { sub.sources.forEach { add(source(it)) } })
            }) } })
            add("disabled", JsonArray().apply { s.disabled.forEach { add(it) } })
            add("history", JsonArray().apply { s.history.take(100).forEach { h -> add(JsonObject().apply {
                addProperty("updated",h.updated); addProperty("position",h.playback.position)
                add("video",JsonObject().apply { addProperty("id",h.playback.video.id); addProperty("title",h.playback.video.title);validUrl(h.playback.video.poster.orEmpty())?.let { addProperty("poster",it) };add("quality",QualityRules.json(h.playback.video.quality)); add("source",source(h.playback.video.source)) })
                add("episode",JsonObject().apply { addProperty("name",h.playback.episode.name); addProperty("url",h.playback.episode.url);add("quality",QualityRules.json(h.playback.episode.quality)) })
            }) } })
        }
        if(root.getAsJsonArray("subscriptions").toString().toByteArray().size > StorageBudget.CONFIG) throw UserError("订阅配置超过 2 MiB，请减少源或过长字段")
        if(root.getAsJsonArray("history").toString().toByteArray().size > StorageBudget.HISTORY) throw UserError("历史记录超过 1 MiB")
        val history=root.remove("history").toString()
        root.add("history",JsonArray()) // Legacy reader compatibility; history file has precedence.
        val config=root.toString()
        // History first preserves legacy embedded history if its first split write fails.
        if(history!=lastHistory) {
            commit(historyFile,history.toByteArray())
            lastHistory=history
        }
        if(config!=lastConfig) {
            commit(file,config.toByteArray())
            lastConfig=config
        }
    }
    suspend fun writeHistory(saved: Saved) = withContext(Dispatchers.IO) {
        if(!historyFile.baseFile.exists()) { write(saved);return@withContext }
        val history=JsonArray().apply { saved.history.take(100).forEach { h -> add(JsonObject().apply {
            addProperty("updated",h.updated);addProperty("position",h.playback.position)
            add("video",JsonObject().apply { addProperty("id",h.playback.video.id);addProperty("title",h.playback.video.title);validUrl(h.playback.video.poster.orEmpty())?.let { addProperty("poster",it) };add("quality",QualityRules.json(h.playback.video.quality));add("source",source(h.playback.video.source)) })
            add("episode",JsonObject().apply { addProperty("name",h.playback.episode.name);addProperty("url",h.playback.episode.url);add("quality",QualityRules.json(h.playback.episode.quality)) })
        }) } }.toString()
        if(history.toByteArray().size>StorageBudget.HISTORY) throw UserError("历史记录超过 1 MiB")
        if(history!=lastHistory) { commit(historyFile,history.toByteArray());lastHistory=history }
    }
    private fun commit(target: AtomicFile, bytes: ByteArray) {
        val other=if(target===file) historyFile else file
        if(bytes.size+other.baseFile.length()>StorageBudget.STATE) throw UserError("本地存储超过 4 MiB 上限，请减少订阅")
        if(available() < StorageBudget.FREE_RESERVE + bytes.size) purgeCache()
        if(available() < StorageBudget.FREE_RESERVE + bytes.size) throw UserError("空间不足：保留至少 16 MiB 空闲后再保存；原配置未替换")
        val stream=target.startWrite()
        try { stream.write(bytes);target.finishWrite(stream) } catch(e: Exception) { target.failWrite(stream);throw e }
    }
    private fun source(s: JsonObject): Source {
        require(validUrl(s.str("url")) != null && s.scalar("name").length <= 120)
        return Source(s.scalar("name"),s.scalar("url"),SourceKind.valueOf(s.scalar("kind")),s.str("detail"),s.bool("adult") == true,validUrl(s.str("epg")))
    }
    private fun source(s: Source) = JsonObject().apply {
        addProperty("name",s.name); addProperty("url",s.url); addProperty("kind",s.kind.name); addProperty("detail",s.detail); addProperty("adult",s.adult); addProperty("epg",s.epg)
    }
}
