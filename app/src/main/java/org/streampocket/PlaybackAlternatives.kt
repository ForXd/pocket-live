// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

data class PlaybackAlternative(val video:Video,val line:String,val episode:Episode)
object PlaybackAlternatives {
    const val MAX_OPTIONS=40
    const val MAX_SOURCES=12
    fun title(value:String)=value.trim().lowercase().replace(Regex("\\s+"),"")
    private fun number(value:String):Int? {
        val label=value.trim().lowercase()
        return Regex("^(?:第\\s*|ep(?:isode)?\\s*)?0*(\\d+)\\s*(?:集|话|期)?$").matchEntire(label)?.groupValues?.get(1)?.toIntOrNull()
    }
    fun matches(current:Episode,candidate:Episode):Boolean {
        if(title(current.name)==title(candidate.name)) return true
        val n=number(current.name) ?: return false
        return n==number(candidate.name)
    }
    fun from(detail:Detail,current:Playback):List<PlaybackAlternative> {
        if(title(detail.video.title)!=title(current.video.title)) return emptyList()
        return detail.lines.mapNotNull { line ->
            line.episodes.firstOrNull { matches(current.episode,it) && it.url!=current.episode.url }?.let { PlaybackAlternative(detail.video,line.name,it) }
        }.distinctBy { it.episode.url }.take(MAX_OPTIONS)
    }
    fun merge(old:List<PlaybackAlternative>,new:List<PlaybackAlternative>)=(old+new).distinctBy { it.episode.url }.take(MAX_OPTIONS)
}
