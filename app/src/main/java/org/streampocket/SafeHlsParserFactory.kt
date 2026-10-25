// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import android.net.Uri
import androidx.media3.exoplayer.hls.playlist.*
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.InputStream
import java.io.SequenceInputStream
import java.io.ByteArrayInputStream

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class SafeHlsParserFactory(private val selected:String,private val paths:Set<String>,private val automatic:Boolean=false,private val edited:(HlsEdit)->Unit):HlsPlaylistParserFactory {
    private val delegate=DefaultHlsPlaylistParserFactory()
    override fun createPlaylistParser():ParsingLoadable.Parser<HlsPlaylist> = wrap(delegate.createPlaylistParser(),selected)
    override fun createPlaylistParser(multivariantPlaylist:HlsMultivariantPlaylist,previousMediaPlaylist:HlsMediaPlaylist?):ParsingLoadable.Parser<HlsPlaylist> =
        wrap(delegate.createPlaylistParser(multivariantPlaylist,previousMediaPlaylist),
            if(automatic && multivariantPlaylist.variants.size==1 && multivariantPlaylist.audios.isEmpty() && multivariantPlaylist.subtitles.isEmpty())
                multivariantPlaylist.variants.single().url.toString() else null)
    private fun wrap(parser:ParsingLoadable.Parser<HlsPlaylist>,target:String?)=object:ParsingLoadable.Parser<HlsPlaylist> {
        override fun parse(uri:Uri,inputStream:InputStream):HlsPlaylist {
            if(target==null || uri.toString()!=target) return parser.parse(uri,inputStream)
            val buffer=java.io.ByteArrayOutputStream();val chunk=ByteArray(8192)
            while(buffer.size()<=JsonData.LIMIT) { val n=inputStream.read(chunk,0,minOf(chunk.size,JsonData.LIMIT+1-buffer.size()));if(n<0) break;buffer.write(chunk,0,n) }
            val prefix=buffer.toByteArray()
            if(prefix.size>JsonData.LIMIT) return parser.parse(uri,SequenceInputStream(ByteArrayInputStream(prefix),inputStream))
            val text=try { Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(prefix)).toString() }
                catch(_:Exception) { return parser.parse(uri,ByteArrayInputStream(prefix)) }
            val edit=if(automatic) AutoHls.filter(text,uri.toString(),paths) else SafeHls.filter(text,uri.toString(),paths)
            if(edit.removed==0) return parser.parse(uri,ByteArrayInputStream(prefix))
            return try { parser.parse(uri,ByteArrayInputStream(edit.text.toByteArray())).also { if(it is HlsMediaPlaylist) edited(edit) } }
                catch(_:Exception) { parser.parse(uri,ByteArrayInputStream(prefix)) }
        }
    }
}
