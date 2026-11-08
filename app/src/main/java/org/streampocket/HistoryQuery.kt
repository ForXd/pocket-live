// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

internal enum class HistoryPeriod(val label:String,val days:Int) { ALL("全部时间",0), WEEK("近 7 天",7), MONTH("近 30 天",30) }
internal enum class HistoryKind(val label:String) { ALL("全部类型"), VOD("点播"), LIVE("直播") }
internal fun queryHistory(history:List<History>,query:String,source:String?,period:HistoryPeriod,kind:HistoryKind,now:Long):List<History> {
    val words=query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val cutoff=if(period.days==0) Long.MIN_VALUE else now-period.days*86_400_000L
    return history.filter { h ->
        val p=h.playback
        h.updated>=cutoff && (source==null || p.video.source.key==source) &&
            (kind==HistoryKind.ALL || p.video.source.kind.name==kind.name) &&
            words.all { word -> listOf(p.video.title,p.video.source.name,p.episode.name,p.video.subtitle,p.video.genreText).any { it.contains(word,true) } }
    }
}
internal fun historyTime(updated:Long):String=java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.getDefault()).format(java.util.Date(updated))
