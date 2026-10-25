// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

/** Presentation only: the bounded episode history remains the sole persisted data. */
data class HistoryGroupKey(val sourceKey:String,val videoId:String) {
    val stableKey:String get()="${sourceKey.length}:$sourceKey${videoId.length}:$videoId"
}
data class HistoryGroup(val key:HistoryGroupKey,val entries:List<History>) {
    val latest:History get()=entries.first()
}
object HistoryGroups {
    fun key(video:Video)=HistoryGroupKey(video.source.key,video.id)
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
