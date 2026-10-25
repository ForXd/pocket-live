// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import com.google.gson.JsonObject

/** Metadata is a source claim; only decoder videoSize can produce MEASURED. */
enum class QualityEvidence { UNKNOWN, SOURCE_LABEL, API_DIMENSIONS, MEASURED }
data class VideoQuality(val width: Int = 0, val height: Int = 0,
    val evidence: QualityEvidence = QualityEvidence.UNKNOWN, val sourceLabel: String = "") {
    val pixels: Int get() = minOf(width,height).takeIf { it>0 } ?: height
    val label: String get() = when(evidence) {
        QualityEvidence.MEASURED -> "${width}×${height} · 已测"
        QualityEvidence.API_DIMENSIONS -> "${width}×${height} · 接口自报"
        QualityEvidence.SOURCE_LABEL -> "${if(height>0) "${height}p" else sourceLabel} · 源站自报"
        QualityEvidence.UNKNOWN -> "清晰度未知"
    }
}
object QualityRules {
    private val dimensions=Regex("(?i)(?<![0-9])([0-9]{2,5})\\s*[x×]\\s*([0-9]{2,5})(?![0-9])")
    private val progressive=Regex("(?i)(?<![a-z0-9])(2160|1440|1080|720|576|480|360|240)p(?![a-z0-9])")
    fun measured(width:Int,height:Int):VideoQuality =
        if(width in 1..16384 && height in 1..16384) VideoQuality(width,height,QualityEvidence.MEASURED) else VideoQuality()
    fun sourceLabel(value:String):VideoQuality {
        val text=value.take(200)
        dimensions.find(text)?.let {
            val w=it.groupValues[1].toInt();val h=it.groupValues[2].toInt()
            if(w in 1..16384 && h in 1..16384) return VideoQuality(w,h,QualityEvidence.SOURCE_LABEL)
        }
        progressive.find(text)?.let { return VideoQuality(height=it.groupValues[1].toInt(),evidence=QualityEvidence.SOURCE_LABEL) }
        if(Regex("(?i)(?<![a-z0-9])4k(?![a-z0-9])").containsMatchIn(text)) return VideoQuality(height=2160,evidence=QualityEvidence.SOURCE_LABEL)
        return listOf("蓝光","超清","高清","FHD","HD").firstOrNull { text.contains(it,true) }
            ?.let { VideoQuality(evidence=QualityEvidence.SOURCE_LABEL,sourceLabel=it) } ?: VideoQuality()
    }
    fun api(item:JsonObject):VideoQuality {
        val w=item.scalar("vod_width").toIntOrNull() ?: 0
        val h=item.scalar("vod_height").toIntOrNull() ?: 0
        if(w in 1..16384 && h in 1..16384) return VideoQuality(w,h,QualityEvidence.API_DIMENSIONS)
        // Never parse vod_name: a title is not a technical quality declaration.
        return listOf("vod_resolution","vod_quality","vod_remarks").map { sourceLabel(item.scalar(it)) }
            .maxWithOrNull(compareBy<VideoQuality> { it.pixels }.thenBy { it.evidence.ordinal }) ?: VideoQuality()
    }
    fun json(q:VideoQuality)=JsonObject().apply {
        addProperty("width",q.width);addProperty("height",q.height);addProperty("evidence",q.evidence.name);addProperty("label",q.sourceLabel.take(12))
    }
    fun read(q:JsonObject?):VideoQuality {
        if(q==null) return VideoQuality()
        val w=q.scalar("width").toIntOrNull() ?: 0;val h=q.scalar("height").toIntOrNull() ?: 0
        val e=QualityEvidence.entries.firstOrNull { it.name==q.scalar("evidence") } ?: return VideoQuality()
        if(w !in 0..16384 || h !in 0..16384 || (e==QualityEvidence.MEASURED && (w==0 || h==0))) return VideoQuality()
        return VideoQuality(w,h,e,q.scalar("label").take(12))
    }
    fun merge(current:List<Video>,incoming:List<Video>):List<Video> =
        (current+sorted(incoming)).distinctBy { it.source.key+":"+it.id }.take(1000)
    fun sorted(videos:List<Video>):List<Video> = videos.sortedWith(
        compareByDescending<Video> { it.quality.pixels }.thenByDescending { it.quality.evidence.ordinal })
}

/** Constant-size event buffer; repeated decoder sizes create no new UI/write work. */
class MeasuredQualityBuffer {
    private val values=LinkedHashMap<String,VideoQuality>()
    val size:Int get()=values.size
    operator fun get(sessionId:String)=values[sessionId]
    fun offer(sessionId:String,quality:VideoQuality):Boolean {
        if(quality.evidence!=QualityEvidence.MEASURED || values[sessionId]==quality) return false
        values.remove(sessionId);values[sessionId]=quality
        while(values.size>4) values.remove(values.keys.first())
        return true
    }
}
