// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import kotlinx.coroutines.*
import okhttp3.*
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ProbeStatus(val label:String) { CHECKING("探测中"), READY("媒体可用"), FAILED("探测失败"), UNKNOWN("待确认") }
internal fun Video.probeKey()=source.key+":"+id

/** Bounded media validation, not a decoder or a background player. Every response is closed. */
internal class PlaybackProbe(private val network:Network) {
    suspend fun check(video:Video):ProbeStatus = withContext(Dispatchers.Default) {
        val detail=CmsParser.detail(network.text(network.api(video.source,"ids",video.id)),video)
        val episode=detail.lines.firstOrNull { it.episodes.isNotEmpty() }?.episodes?.firstOrNull() ?: return@withContext ProbeStatus.UNKNOWN
        media(episode.url,0)
    }
    private suspend fun media(url:String,depth:Int):ProbeStatus {
        if(depth>2) return ProbeStatus.UNKNOWN
        val sample=sample(url)
        val bytes=sample.second
        val text=bytes.toString(Charsets.UTF_8).trimStart('\uFEFF',' ','\n','\r')
        if(text.startsWith("#EXTM3U")) {
            if(text.lineSequence().any { it.startsWith("#EXT-X-KEY:") && !it.contains("METHOD=NONE") }) return ProbeStatus.UNKNOWN
            val lines=text.lineSequence().map { it.trim() }.toList()
            val master=lines.any { it.startsWith("#EXT-X-STREAM-INF:") }
            if(!master && lines.none { it.startsWith("#EXTINF:") }) return ProbeStatus.UNKNOWN
            val target=lines.firstOrNull { it.isNotBlank() && !it.startsWith('#') } ?: return ProbeStatus.UNKNOWN
            val resolved=sample.first.resolve(target)?.toString() ?: return ProbeStatus.UNKNOWN
            return media(resolved,depth+1)
        }
        if(text.startsWith("<?xml") || text.startsWith("<MPD")) return ProbeStatus.UNKNOWN
        return if(validMediaPrefix(bytes)) ProbeStatus.READY else ProbeStatus.FAILED
    }
    private suspend fun sample(url:String):Pair<HttpUrl,ByteArray> = suspendCancellableCoroutine { continuation ->
        val normalized=validUrl(url)
        if(normalized==null) { continuation.resumeWithException(IOException("Invalid media URL"));return@suspendCancellableCoroutine }
        val call=network.client.newCall(Request.Builder().url(normalized).header("Range","bytes=0-65535").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException) { if(continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call:Call,response:Response) {
                try {
                    val result=response.use { r ->
                        if(!r.isSuccessful) throw IOException("Media HTTP ${r.code}")
                        val body=r.body ?: throw IOException("Empty media")
                        r.request.url to body.byteStream().use { input ->
                            val out=java.io.ByteArrayOutputStream();val chunk=ByteArray(4096)
                            while(out.size()<65536) { val count=input.read(chunk,0,minOf(chunk.size,65536-out.size()));if(count<0) break;out.write(chunk,0,count) }
                            out.toByteArray() }
                    }
                    if(continuation.isActive) continuation.resume(result)
                } catch(e:Exception) { if(continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
}
internal fun validMediaPrefix(bytes:ByteArray):Boolean {
    if(bytes.size>=377 && bytes[0]==0x47.toByte() && bytes[188]==0x47.toByte() && bytes[376]==0x47.toByte()) return true
    if(bytes.size>=12) {
        val box=bytes.copyOfRange(4,8).toString(Charsets.US_ASCII)
        if(box in listOf("ftyp","styp","moof")) return true
    }
    return false
}
