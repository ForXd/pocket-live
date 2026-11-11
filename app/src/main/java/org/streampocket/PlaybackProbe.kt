// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import kotlinx.coroutines.*
import okhttp3.*
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ProbeStatus(val label:String) { CHECKING("探测中"), READY("媒体可用"), FAILED("探测失败"), UNKNOWN("待确认") }
data class ProbeResult(val status:ProbeStatus,val resolution:String?=null)
internal fun probeResolution(line:String):String? {
    val match=Regex("(?:^|,)RESOLUTION=(\\d{2,5})x(\\d{2,5})(?:,|$)").find(line.substringAfter(':')) ?: return null
    val width=match.groupValues[1].toInt();val height=match.groupValues[2].toInt()
    return if(width in 16..16384 && height in 16..16384) "${width}×${height}" else null
}
internal fun Video.probeKey()=source.key+":"+id

internal object ProbePolicy {
    const val CONCURRENCY=3
    const val READY_LIMIT=2
    const val MEDIA_TIMEOUT_MS=5_000L
    const val SESSION_TIMEOUT_MS=18_000L
}

/** Bounded media validation, not a decoder or a background player. Every response is closed. */
internal class PlaybackProbe(private val network:Network) {
    suspend fun check(video:Video):ProbeStatus = inspect(video).status
    suspend fun inspect(video:Video):ProbeResult = withContext(Dispatchers.Default) {
        val detail=CmsParser.detail(network.text(network.api(video.source,"ids",video.id)),video)
        var unknown=false
        // One candidate per line, using the same bounded media inspection as quick switch.
        // A dead or web-only first line must not hide a working alternative line.
        for(episode in detail.lines.mapNotNull { it.episodes.firstOrNull() }.distinctBy { it.url }) {
            val checked=try { withTimeout(ProbePolicy.MEDIA_TIMEOUT_MS) { inspect(episode) } }
                catch(e:TimeoutCancellationException) { ensureActive();ProbeResult(ProbeStatus.FAILED) }
                catch(e:CancellationException) { throw e }
                catch(_:Exception) { ProbeResult(ProbeStatus.FAILED) }
            if(checked.status==ProbeStatus.READY) return@withContext checked
            if(checked.status==ProbeStatus.UNKNOWN) unknown=true
        }
        ProbeResult(if(unknown) ProbeStatus.UNKNOWN else ProbeStatus.FAILED)
    }
    suspend fun inspect(episode:Episode):ProbeResult = withContext(Dispatchers.Default) { media(episode.url,0) }
    private suspend fun media(url:String,depth:Int):ProbeResult {
        if(depth>2) return ProbeResult(ProbeStatus.UNKNOWN)
        val sample=sample(url)
        val bytes=sample.second
        val text=bytes.toString(Charsets.UTF_8).trimStart('\uFEFF',' ','\n','\r')
        if(text.startsWith("#EXTM3U")) {
            if(text.lineSequence().any { it.startsWith("#EXT-X-KEY:") && !it.contains("METHOD=NONE") }) return ProbeResult(ProbeStatus.UNKNOWN)
            val lines=text.lineSequence().map { it.trim() }.toList()
            val master=lines.any { it.startsWith("#EXT-X-STREAM-INF:") }
            if(!master && lines.none { it.startsWith("#EXTINF:") }) return ProbeResult(ProbeStatus.UNKNOWN)
            val target=lines.firstOrNull { it.isNotBlank() && !it.startsWith('#') } ?: return ProbeResult(ProbeStatus.UNKNOWN)
            val resolved=sample.first.resolve(target)?.toString() ?: return ProbeResult(ProbeStatus.UNKNOWN)
            val checked=media(resolved,depth+1)
            val declaration=if(master) lines.takeWhile { it!=target }.lastOrNull { it.startsWith("#EXT-X-STREAM-INF:") }?.let(::probeResolution) else null
            return checked.copy(resolution=if(checked.status==ProbeStatus.READY) checked.resolution ?: declaration else null)
        }
        if(text.startsWith("<?xml") || text.startsWith("<MPD")) return ProbeResult(ProbeStatus.UNKNOWN)
        return ProbeResult(if(validMediaPrefix(bytes)) ProbeStatus.READY else ProbeStatus.FAILED)
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
                            while(out.size()<65536) { val count=input.read(chunk,0,minOf(chunk.size,65536-out.size()));if(count<0) break;out.write(chunk,0,count);if(validMediaPrefix(out.toByteArray())) break }
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
