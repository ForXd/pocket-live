// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

/** Presentation only: the bounded episode history remains the sole persisted data. */
data class HistoryGroupKey(val sourceKey:String,val videoId:String) {
    val stableKey:String get()="${sourceKey.length}:$sourceKey${videoId.length}:$videoId"
}
data class HistoryGroup(val key:HistoryGroupKey,val entries:List<History>) {
    val latest:History get()=entries.first()
    val episodes:List<HistoryEpisodeGroup> get()=entries.groupBy { (if(VideoGroups.identity(it.playback.video).season!=null) VideoGroups.workKey(it.playback.video)+":" else "")+HistoryGroups.episodeKey(it) }.map { (key,items) -> HistoryEpisodeGroup(key,items) }
}
data class HistoryEpisodeGroup(val key:String,val entries:List<History>) {
    val latest:History get()=entries.first()
}
object HistoryGroups {
    fun key(video:Video):HistoryGroupKey {
        return HistoryGroupKey("video-family",VideoGroups.familyKey(video))
    }
    /** Only unambiguous episode labels merge; seasons, specials and multipart suffixes stay distinct. */
    fun episodeKey(history:History):String {
        val p=history.playback
        val label=java.text.Normalizer.normalize(p.episode.name,java.text.Normalizer.Form.NFKC)
            .filterNot { it.isWhitespace() || Character.isSpaceChar(it) }.lowercase(java.util.Locale.ROOT)
        if(label.isEmpty()) return "record:${p.key}"
        if(p.video.source.kind==SourceKind.LIVE) return "live:$label"
        val number=Regex("^(?:第|ep(?:isode)?)?0*([0-9]+)(?:集|话|期)?$").matchEntire(label)?.groupValues?.get(1)?.toIntOrNull()
        return if(number!=null) "number:$number" else "label:$label"
    }
    fun group(history:List<History>):List<HistoryGroup> = history.sortedByDescending { it.updated }
        .groupBy { key(it.playback.video) }.map { (key,entries) -> HistoryGroup(key,entries) }
    fun remove(saved:Saved,groupKey:HistoryGroupKey)=saved.copy(history=saved.history.filterNot { key(it.playback.video)==groupKey })
    fun resume(history:History)=history.playback.copy(
        position=if(history.playback.video.source.kind==SourceKind.LIVE || history.playback.watchedToEnd) 0 else history.playback.position.coerceAtLeast(0),
        autoPlay=true,sessionId=java.util.UUID.randomUUID().toString())
    fun progress(history: History): String {
        val playback = history.playback
        if (playback.video.source.kind == SourceKind.LIVE) return "打开当前直播"
        if (playback.watchedToEnd) return "已看完"
        return breakpointLabel(playback.position)
    }

    fun breakpointLabel(position: Long): String {
        val seconds = position.coerceAtLeast(0) / 1000
        val time = if (seconds >= 3600) {
            String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        } else {
            String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
        }
        return "已看到 $time"
    }
}
