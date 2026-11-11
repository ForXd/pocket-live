// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.*
import androidx.media3.exoplayer.hls.playlist.*
import androidx.media3.exoplayer.upstream.ParsingLoadable
import kotlinx.coroutines.*
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Session-local, one-segment look-ahead. No disk, player, timer, or independent executor.
 * Only immutable, unencrypted, whole-segment VOD is eligible. A requested speculative segment is promoted instead of fetched twice. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
internal class HlsLookAhead(private val upstream:DataSource.Factory,private val client:okhttp3.OkHttpClient,private val segmentLimit:Int=MAX_SEGMENT_BYTES):AutoCloseable {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var next=emptyMap<String,String>()
    private var pending:Pending?=null
    private var promoted:Pending?=null
    private var closed=false
    private var disabled=false
    private data class Segment(val bytes:ByteArray,val length:Int)
    private class Pending(val url:String) {
        val result=CompletableFuture<Segment?>()
        var job:Job?=null
        @Volatile var owner:MediaCallOwner?=null
    }
    companion object { const val MAX_SEGMENT_BYTES=2*1024*1024;const val MAX_SEGMENTS=2000 }
    @Synchronized internal fun observe(playlist:HlsPlaylist) {
        if(closed || disabled || playlist !is HlsMediaPlaylist) return
        // Live, keys, byte ranges and initialization segments retain the original loader.
        if(!playlist.hasEndTag || playlist.segments.size>MAX_SEGMENTS || playlist.segments.any {
            it.fullSegmentEncryptionKeyUri!=null || it.drmInitData!=null || it.initializationSegment!=null || it.byteRangeLength!=C.LENGTH_UNSET.toLong()
        }) { disabled=true;cancelPending();next=emptyMap();return }
        val urls=ArrayList<String>(playlist.segments.size)
        var characters=0L
        for(segment in playlist.segments) {
            val url=androidx.media3.common.util.UriUtil.resolve(playlist.baseUri,segment.url)
            characters+=url.length
            if(characters>262_144L) { disabled=true;cancelPending();next=emptyMap();return }
            urls.add(url)
        }
        next=urls.zipWithNext().toMap()
    }
    fun parser(delegate:HlsPlaylistParserFactory):HlsPlaylistParserFactory = object:HlsPlaylistParserFactory {
        private fun wrap(parser:ParsingLoadable.Parser<HlsPlaylist>)=object:ParsingLoadable.Parser<HlsPlaylist> {
            override fun parse(uri:Uri,inputStream:InputStream)=parser.parse(uri,inputStream).also(::observe)
        }
        override fun createPlaylistParser()=wrap(delegate.createPlaylistParser())
        override fun createPlaylistParser(multivariantPlaylist:HlsMultivariantPlaylist,previousMediaPlaylist:HlsMediaPlaylist?):ParsingLoadable.Parser<HlsPlaylist> {
            // Let adaptive/separate-track loading retain its original bandwidth accounting.
            if(multivariantPlaylist.variants.size>1 || multivariantPlaylist.audios.isNotEmpty() || multivariantPlaylist.subtitles.isNotEmpty())
                synchronized(this@HlsLookAhead) { disabled=true;cancelPending();next=emptyMap() }
            return wrap(delegate.createPlaylistParser(multivariantPlaylist,previousMediaPlaylist))
        }
    }
    val factory=DataSource.Factory { object:DataSource {
        private val listeners=mutableListOf<TransferListener>()
        private var delegate:DataSource?=null
        override fun addTransferListener(listener:TransferListener) { listeners.add(listener);delegate?.addTransferListener(listener) }
        override fun open(spec:DataSpec):Long {
            val eligible=spec.httpMethod==DataSpec.HTTP_METHOD_GET && spec.position==0L && spec.length==C.LENGTH_UNSET.toLong() && spec.httpRequestHeaders.isEmpty()
            val bytes=if(eligible) takeAndSchedule(spec.uri.toString()) else null
            val source=if(bytes!=null) ByteArrayDataSource(bytes.bytes) else upstream.createDataSource()
            delegate=source;listeners.forEach(source::addTransferListener)
            return source.open(if(bytes!=null) spec.buildUpon().setLength(bytes.length.toLong()).build() else spec)
        }
        override fun read(buffer:ByteArray,offset:Int,length:Int)=checkNotNull(delegate).read(buffer,offset,length)
        override fun getUri()=delegate?.uri
        override fun getResponseHeaders()=delegate?.responseHeaders ?: emptyMap()
        override fun close() { val source=delegate;delegate=null;source?.close() }
    } }
    private fun takeAndSchedule(url:String):Segment? {
        val item=synchronized(this) {
            if(promoted!=null) return null // a second loader must not grow the speculative budget
            val match=pending?.takeIf { it.url==url }
            if(match!=null) { pending=null;promoted=match } else cancelPending()
            scheduleNext(url)
            match
        } ?: return null
        // Promote the in-flight request to foreground and overlap exactly one successor.
        // The HTTP call has an absolute eight-second deadline, including connect/body reads.
        val bytes=try { item.result.get(9,TimeUnit.SECONDS) } catch(_:Exception) { null }
        synchronized(this) {
            if(promoted===item) promoted=null
            if(bytes==null) { disabled=true;cancelPending() }
        }
        item.job?.cancel();item.owner?.close()
        return bytes
    }
    @Synchronized private fun scheduleNext(url:String) {
        if(closed || disabled) return
        val target=next[url] ?: return
        cancelPending()
        val item=Pending(target);pending=item
        item.job=scope.launch {
            var source:DataSource?=null
            try {
                val owner=MediaCallOwner(client)
                synchronized(this@HlsLookAhead) {
                    if((pending!==item && promoted!==item) || closed) { owner.close();return@launch }
                    item.owner=owner
                }
                val boundedCalls=okhttp3.Call.Factory { request -> owner.newCall(request).apply { timeout().timeout(8,TimeUnit.SECONDS) } }
                source=androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(boundedCalls).createDataSource()
                val size=source.open(DataSpec(Uri.parse(target)))
                if(size>segmentLimit) { synchronized(this@HlsLookAhead) { disabled=true };return@launch }
                // Fixed allocation: no ByteArrayOutputStream growth or full-array copy.
                val data=ByteArray(if(size in 1..segmentLimit.toLong()) size.toInt() else segmentLimit)
                var count=0
                val overflow=ByteArray(1)
                while(isActive) {
                    val n=if(count<data.size) source.read(data,count,data.size-count) else source.read(overflow,0,1)
                    if(n==C.RESULT_END_OF_INPUT) { if(count>0) item.result.complete(Segment(data,count));return@launch }
                    if(count+n>data.size) { synchronized(this@HlsLookAhead) { disabled=true };return@launch }
                    count+=n
                }

            } catch(_:Exception) { synchronized(this@HlsLookAhead) { disabled=true } }
            finally { try { source?.close() } catch(_:Exception) { };item.owner?.close();item.owner=null;item.result.complete(null) }
        }
    }
    // The owner cancels the socket as well as the coroutine: blocking reads must not linger.
    private fun cancelPending() { pending?.let { it.job?.cancel();it.owner?.close();it.result.complete(null) };pending=null }
    @Synchronized override fun close() { closed=true;cancelPending();promoted?.let { it.job?.cancel();it.owner?.close();it.result.complete(null) };promoted=null;next=emptyMap();scope.cancel() }
}
