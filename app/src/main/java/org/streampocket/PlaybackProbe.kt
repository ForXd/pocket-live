// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okio.BufferedSource
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ProbeStatus(val label:String) {
    UNCHECKED("排队中"), CHECKING("试播中"), READY("可播放"), FAILED("不可播放")
}
data class ProbeResult(val status:ProbeStatus,val resolution:String?=null,val episode:Episode?=null,val mimeType:String?=null,val lineName:String?=null)
internal fun Video.probeKey()=source.key+":"+id

internal object ProbePolicy {
    const val CONCURRENCY=3
    const val MEDIA_TIMEOUT_MS=12_000L
}

internal class PlaybackProbe(private val network:Network) {
    /** Format detection only; native HLS still reads the original URI and handles redirects. */
    suspend fun mediaType(url:String):String? = suspendCancellableCoroutine { continuation ->
        val normalized=validUrl(url)
        if(normalized==null) {
            continuation.resumeWithException(IOException("Invalid media URL"))
            return@suspendCancellableCoroutine
        }
        val request=Request.Builder().url(normalized).header("Accept-Encoding","identity").build()
        val call=network.client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException) {
                if(continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call:Call,response:Response) {
                try {
                    val mime=response.use {
                        if(!continuation.isActive) return
                        if(!it.isSuccessful) throw IOException("Media HTTP ${it.code}")
                        val source=it.body?.source() ?: throw IOException("Empty media")
                        if(isHls(source)) "application/x-mpegURL" else null
                    }
                    if(continuation.isActive) continuation.resume(mime)
                } catch(e:Exception) {
                    if(continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    private fun isHls(source:BufferedSource):Boolean {
        if(source.rangeEquals(0,UTF8_BOM)) source.skip(UTF8_BOM.size.toLong())
        repeat(4096) {
            if(!source.request(1)) return false
            when(source.readByte().toInt()) {
                9,10,13,32 -> Unit
                '#'.code -> return source.rangeEquals(0,HLS_MARKER)
                else -> return false
            }
        }
        return false
    }

    private companion object {
        val UTF8_BOM="efbbbf".decodeHex()
        val HLS_MARKER="EXTM3U".encodeUtf8()
    }
}
