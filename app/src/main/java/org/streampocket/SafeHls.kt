// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import java.net.URI

data class HlsCut(val start:Long,val duration:Long)
data class HlsEdit(val text:String,val removed:Int=0,val removedMs:Long=0,val cuts:List<HlsCut> = emptyList()) {
    fun originalPosition(filtered:Long):Long {
        if(cuts.isEmpty()) return filtered.coerceAtLeast(0)+removedMs
        var original=filtered.coerceAtLeast(0)
        for(cut in cuts) { if(original>=cut.start) original+=cut.duration else break }
        return original
    }
    fun filteredPosition(original:Long):Long {
        if(cuts.isEmpty()) return (original-removedMs).coerceAtLeast(0)
        return (original-cuts.sumOf { (original-it.start).coerceIn(0,it.duration) }).coerceAtLeast(0)
    }
}
/** Intentionally narrow: only a user-confirmed prefix ending at a discontinuity.
 * Never infer ads from duration, domain, query text or a discontinuity alone.
 * Trimming a prefix lets media sequence numbers retain their original meaning.
 * Mid-rolls, encrypted/fMP4, live, alternate tracks and unknown tags pass through. */
object SafeHls {
    fun filter(text:String,base:String,paths:Set<String>):HlsEdit {
        val original=HlsEdit(text)
        if(paths.isEmpty() || text.length>2_097_152) return original
        return try {
            val lines=text.split('\n')
            if(lines.firstOrNull()?.trim()!="#EXTM3U" || lines.count { it.trim()=="#EXT-X-ENDLIST" }!=1) return original
            val allowed=setOf("#EXTM3U","#EXT-X-VERSION","#EXT-X-TARGETDURATION","#EXT-X-MEDIA-SEQUENCE","#EXT-X-DISCONTINUITY-SEQUENCE","#EXT-X-DISCONTINUITY","#EXTINF","#EXT-X-ENDLIST","#EXT-X-PLAYLIST-TYPE")
            if(lines.any { line -> val t=line.trim();t.startsWith("#") && t.substringBefore(':') !in allowed }) return original
            if(lines.any { it.startsWith("#EXT-X-PLAYLIST-TYPE:") && it.trim()!="#EXT-X-PLAYLIST-TYPE:VOD" }) return original
            val baseUri=URI(base)
            data class Segment(val ext:Int,val uri:Int,val duration:Double,val ad:Boolean)
            val segments=mutableListOf<Segment>();var pending=-1
            for((i,line) in lines.withIndex()) {
                val t=line.trim()
                if(t.startsWith("#EXTINF:")) { if(pending>=0) return original;pending=i }
                else if(t.isNotEmpty() && !t.startsWith("#")) {
                    if(pending<0 || i!=pending+1) return original
                    val duration=lines[pending].substringAfter(':').substringBefore(',').toDoubleOrNull() ?: return original
                    if(!duration.isFinite() || duration<=0 || duration>3600) return original
                    val uri=baseUri.resolve(t)
                    val ad=uri.scheme==baseUri.scheme && uri.host==baseUri.host && uri.port==baseUri.port && uri.rawPath in paths
                    segments+=Segment(pending,i,duration,ad);pending=-1
                }
            }
            if(pending>=0 || segments.size<2 || lines.indexOfFirst { it.trim()=="#EXT-X-ENDLIST" }<segments.last().uri) return original
            val count=segments.takeWhile { it.ad }.size
            if(count==0 || count==segments.size) return original
            val first=segments[count]
            val boundary=first.ext-1
            if(lines.getOrNull(boundary)?.trim()!="#EXT-X-DISCONTINUITY") return original
            val firstExt=segments.first().ext
            // Headers must precede all segments; unknown or interleaved metadata is unsafe.
            if(lines.subList(firstExt,boundary).any { val t=it.trim();t.startsWith("#") && !t.startsWith("#EXTINF:") && t!="#EXT-X-DISCONTINUITY" }) return original
            val mediaLines=lines.filter { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }
            val discLines=lines.filter { it.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:") }
            if(mediaLines.size>1 || discLines.size>1) return original
            val seq=mediaLines.firstOrNull()?.substringAfter(':')?.trim()?.toLongOrNull() ?: if(mediaLines.isEmpty()) 0L else return original
            val disc=discLines.firstOrNull()?.substringAfter(':')?.trim()?.toLongOrNull() ?: if(discLines.isEmpty()) 0L else return original
            if(seq<0 || disc<0 || seq>Long.MAX_VALUE-count) return original
            val removedDiscs=lines.take(boundary).count { it.trim()=="#EXT-X-DISCONTINUITY" }
            if(disc>Long.MAX_VALUE-removedDiscs) return original
            val header=lines.take(firstExt).filterNot { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") || it.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:") || it.trim()=="#EXT-X-DISCONTINUITY" }
            val output=(header+listOf("#EXT-X-MEDIA-SEQUENCE:${seq+count}","#EXT-X-DISCONTINUITY-SEQUENCE:${disc+removedDiscs}")+lines.drop(boundary)).joinToString("\n")
            HlsEdit(output,count,(segments.take(count).sumOf { it.duration }*1000).toLong())
        } catch(_:Exception) { original }
    }
}
