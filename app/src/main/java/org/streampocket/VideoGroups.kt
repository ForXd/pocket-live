// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import java.text.Normalizer
import java.util.Locale

/** Conservative presentation identity. Never equate missing seasons, aliases or editions. */
object VideoGroups {
    data class Identity(val base:String,val season:Int?,val year:String,val kind:MediaCategory) {
        val family:String get()=if(season==null) "$kind:$base:$year" else "$kind:series:$base"
        val work:String get()="$family:${season ?: 0}:$year"
        val label:String get()=season?.let { "第 $it 季" } ?: "影片"
    }
    private val seasonPattern=Regex("(?:第\\s*([0-9一二三四五六七八九十百零〇两]+)\\s*季|\\bseason\\s*(\\d{1,3})\\b|\\bs(\\d{1,3})\\b)",RegexOption.IGNORE_CASE)
    private fun normalized(s:String)=Normalizer.normalize(s,Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    private fun compact(s:String)=normalized(s).filterNot { it.isWhitespace() || Character.isSpaceChar(it) }
    private fun number(s:String):Int? {
        s.toIntOrNull()?.let { return it.takeIf { n -> n in 1..999 } }
        val digits=mapOf('零' to 0,'〇' to 0,'一' to 1,'二' to 2,'两' to 2,'三' to 3,'四' to 4,'五' to 5,'六' to 6,'七' to 7,'八' to 8,'九' to 9)
        var total=0;var digit=0
        for(c in s) when(c) { '十','百' -> { total+=(if(digit==0) 1 else digit)*(if(c=='十') 10 else 100);digit=0 };else -> digit=digits[c] ?: return null }
        return (total+digit).takeIf { it in 1..999 }
    }
    fun identity(video:Video):Identity {
        val title=normalized(video.title).trim()
        val matches=seasonPattern.findAll(title).toList()
        val match=matches.singleOrNull()
        val season=match?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.let(::number)
        val base=if(season!=null) title.removeRange(match!!.range).trim() else title
        val year=Regex("^(?:19|20)\\d{2}(?=\\s*(?:·|$))").find(video.subtitle.trim())?.value.orEmpty()
        val category=SearchRules.category(video)
        return Identity(compact(base),season,year,if(season!=null && category==MediaCategory.OTHER) MediaCategory.SERIES else category)
    }
    fun workKey(video:Video):String = if(video.source.kind==SourceKind.LIVE || video.title.isBlank()) "source:${video.source.key}:${video.id}" else identity(video).work
    fun familyKey(video:Video):String = if(video.source.kind==SourceKind.LIVE || video.title.isBlank()) workKey(video) else identity(video).family
    fun sameWork(a:Video,b:Video)=workKey(a)==workKey(b)
    /** Candidate matching may tolerate legacy missing metadata, but never contradictory metadata. */
    fun playbackMatch(a:Video,b:Video):Boolean {
        if(a.source.kind!=b.source.kind) return false
        val x=identity(a);val y=identity(b)
        return x.base==y.base && x.season==y.season &&
            (x.year.isEmpty() || y.year.isEmpty() || x.year==y.year) &&
            (x.kind==MediaCategory.OTHER || y.kind==MediaCategory.OTHER || x.kind==y.kind)
    }
    fun familyTitle(video:Video):String {
        if(identity(video).season==null) return video.title
        return seasonPattern.replace(Normalizer.normalize(video.title,Normalizer.Form.NFKC),"").trim()
    }
    fun group(videos:List<Video>):List<List<Video>> = videos.distinctBy { it.probeKey() }.groupBy(::familyKey).values.toList()
    fun seasons(videos:List<Video>):List<List<Video>> = videos.groupBy(::workKey).values.sortedWith(compareBy({ identity(it.first()).season ?: 0 },{ identity(it.first()).year }))
    fun options(current:Video,videos:List<Video>)=(listOf(current)+videos).distinctBy { it.probeKey() }.filter { familyKey(it)==familyKey(current) }
}
