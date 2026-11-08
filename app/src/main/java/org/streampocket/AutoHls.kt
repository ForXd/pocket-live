// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import java.net.URI

/** Explicit path evidence only, restricted to complete, unencrypted TS VOD.
 * All classification uses original groups; no cascading decisions. */
object AutoHls {
    private val adPath = Regex("(?:^|/)(?:ads?|advert|advertisement|adjump)(?:/|$)", RegexOption.IGNORE_CASE)
    private val allowed = setOf("#EXTM3U", "#EXT-X-VERSION", "#EXT-X-TARGETDURATION", "#EXT-X-MEDIA-SEQUENCE",
        "#EXT-X-DISCONTINUITY-SEQUENCE", "#EXT-X-DISCONTINUITY", "#EXTINF", "#EXT-X-ENDLIST", "#EXT-X-PLAYLIST-TYPE")
    private data class Segment(val ext: String, val url: String, val duration: Long, val marked: Boolean,val explicit:Boolean)
    private data class Group(val segments: MutableList<Segment> = mutableListOf()) {
        val duration get() = segments.sumOf { it.duration }
    }
    fun filter(text: String, base: String, paths: Set<String>): HlsEdit {
        val unchanged = HlsEdit(text)
        if(text.length > JsonData.LIMIT) return unchanged
        return try {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if(lines.firstOrNull() != "#EXTM3U" || lines.lastOrNull() != "#EXT-X-ENDLIST" || lines.count { it == "#EXT-X-ENDLIST" } != 1) return unchanged
            if(lines.any { it.startsWith("#") && it.substringBefore(':') !in allowed }) return unchanged
            if(lines.any { it.startsWith("#EXT-X-PLAYLIST-TYPE:") && it != "#EXT-X-PLAYLIST-TYPE:VOD" }) return unchanged
            val baseUri = URI(base)
            val groups = mutableListOf(Group())
            val headers = mutableListOf<String>()
            var pending: String? = null
            var started = false
            var leadingDisc = false
            for(line in lines) {
                when {
                    line == "#EXT-X-DISCONTINUITY" -> {
                        if(pending != null) return unchanged
                        if(!started) { if(leadingDisc) return unchanged; leadingDisc = true; started = true }
                        else { if(groups.last().segments.isEmpty()) return unchanged; groups.add(Group()) }
                    }
                    line.startsWith("#EXTINF:") -> {
                        if(pending != null) return unchanged
                        pending = line; started = true
                    }
                    !line.startsWith("#") -> {
                        val ext = pending ?: return unchanged
                        val seconds = ext.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: return unchanged
                        if(!seconds.isFinite() || seconds <= 0 || seconds > 3600) return unchanged
                        val uri = baseUri.resolve(line)
                        if(uri.scheme !in listOf("http", "https")) return unchanged
                        val exact = uri.scheme == baseUri.scheme && uri.host == baseUri.host && uri.port == baseUri.port && uri.rawPath in paths
                        if(!exact && !uri.path.orEmpty().endsWith(".ts",ignoreCase=true)) return unchanged
                        groups.last().segments.add(Segment(ext, line, (seconds*1000).toLong(), exact || adPath.containsMatchIn(uri.path.orEmpty()),exact))
                        pending = null
                    }
                    line == "#EXT-X-ENDLIST" -> if(pending != null) return unchanged
                    else -> { if(started) return unchanged; headers.add(line) }
                }
            }
            if(pending != null || groups.any { it.segments.isEmpty() } || groups.size < 2) return unchanged
            val drop = BooleanArray(groups.size)
            groups.forEachIndexed { i, group ->
                val urlMarked = group.segments.all { it.marked }
                // Discontinuities and short duration occur in normal scenes and credits.
                // Never infer ads from timing or position alone; bound automatic cuts.
                drop[i] = group.segments.all { it.explicit } || (urlMarked && group.duration<=90_000)

            }
            if(drop.all { it } || drop.none { it }) return unchanged
            if(groups.indices.filter { drop[it] && !groups[it].segments.all { segment -> segment.explicit } }.sumOf { groups[it].duration } > groups.sumOf { it.duration } / 3) return unchanged
            fun number(tag: String): Long? {
                val values = headers.filter { it.startsWith("$tag:") }
                if(values.size > 1) return null
                return if(values.isEmpty()) 0L else values.single().substringAfter(':').toLongOrNull()?.takeIf { it >= 0 }
            }
            val seq = number("#EXT-X-MEDIA-SEQUENCE") ?: return unchanged
            val disc = number("#EXT-X-DISCONTINUITY-SEQUENCE") ?: return unchanged
            val first = drop.indexOfFirst { !it }
            val prefixCount = groups.take(first).sumOf { it.segments.size }
            val discOffset = first + if(leadingDisc) 1 else 0
            if(seq > Long.MAX_VALUE-prefixCount || disc > Long.MAX_VALUE-discOffset) return unchanged
            val output = headers.filterNot { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") || it.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:") }.toMutableList()
            output.add("#EXT-X-MEDIA-SEQUENCE:${seq+prefixCount}")
            output.add("#EXT-X-DISCONTINUITY-SEQUENCE:${disc+discOffset}")
            val cuts = mutableListOf<HlsCut>()
            var elapsed = 0L
            var count = 0
            var kept = false
            groups.forEachIndexed { i, group ->
                if(drop[i]) { cuts.add(HlsCut(elapsed,group.duration));count += group.segments.size }
                else {
                    if(kept) output.add("#EXT-X-DISCONTINUITY")
                    group.segments.forEach { output.add(it.ext);output.add(it.url) }
                    kept = true
                }
                elapsed += group.duration
            }
            output.add("#EXT-X-ENDLIST")
            HlsEdit(output.joinToString("\n",postfix="\n"),count,cuts.sumOf { it.duration },cuts)
        } catch(_: Exception) { unchanged }
    }
}
