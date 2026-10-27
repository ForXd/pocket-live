// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

/** Presentation only: the bounded episode history remains the sole persisted data. */
data class HistoryGroupKey(val sourceKey:String,val videoId:String) {
    val stableKey:String get()="${sourceKey.length}:$sourceKey${videoId.length}:$videoId"
}
data class HistoryGroup(val key:HistoryGroupKey,val entries:List<History>) {
    val latest:History get()=entries.first()
    val episodes:List<HistoryEpisodeGroup> get()=entries.groupBy(HistoryGroups::episodeKey).map { (key,items) -> HistoryEpisodeGroup(key,items) }
}
data class HistoryEpisodeGroup(val key:String,val entries:List<History>) {
    val latest:History get()=entries.first()
}
object HistoryGroups {
    fun key(video:Video):HistoryGroupKey {
        val title=java.text.Normalizer.normalize(video.title,java.text.Normalizer.Form.NFKC)
            .filterNot { it.isWhitespace() || Character.isSpaceChar(it) }.lowercase(java.util.Locale.ROOT)
        // Keep season/year/version text. IDs are local to each source, never cross-source identity.
        return if(video.source.kind==SourceKind.VOD && title.isNotEmpty()) HistoryGroupKey("vod-title",title)
            else HistoryGroupKey(video.source.key,video.id)
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
        position=if(history.playback.video.source.kind==SourceKind.LIVE) 0 else history.playback.position.coerceAtLeast(0),
        autoPlay=true,sessionId=java.util.UUID.randomUUID().toString())
    fun progress(history:History):String {
        if(history.playback.video.source.kind==SourceKind.LIVE) return "直播 · 打开当前直播"
        val seconds=history.playback.position.coerceAtLeast(0)/1000
        return "已看到 ${seconds/3600}小时${seconds%3600/60}分${seconds%60}秒"
    }
}
