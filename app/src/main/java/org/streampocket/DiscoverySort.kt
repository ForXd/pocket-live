// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import java.text.Collator
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class DiscoverySort(val label:String) {
    DEFAULT("默认顺序"), RATING("评分最高"), UPDATED("最近更新"), TITLE("名称排序");
    fun apply(videos:List<Video>):List<Video> = when(this) {
        DEFAULT -> videos
        RATING -> videos.sortedWith(compareByDescending<Video> { it.rating ?: -1.0 })
        UPDATED -> videos.sortedWith(compareByDescending<Video> { it.updatedAt ?: Long.MIN_VALUE })
        TITLE -> {
            val collator=Collator.getInstance(Locale.SIMPLIFIED_CHINESE)
            videos.sortedWith { a,b -> collator.compare(a.title,b.title) }
        }
    }
}
internal fun sourceRating(value:String):Double?=value.toDoubleOrNull()?.takeIf { it.isFinite() && it>0 && it<=10 }

/** CMS local timestamps have no zone; use a fixed zone rather than the device zone. */
internal fun sourceUpdatedAt(value:String):Long? {
    val text=value.trim()
    if(text.isEmpty()) return null
    val time=runCatching {
        when {
            text.matches(Regex("[0-9]{10}")) -> text.toLong()*1000
            text.matches(Regex("[0-9]{13}")) -> text.toLong()
            else -> runCatching { Instant.parse(text).toEpochMilli() }.getOrElse {
                val local=runCatching { LocalDateTime.parse(text.replace(' ','T'),DateTimeFormatter.ISO_LOCAL_DATE_TIME) }
                    .getOrElse { LocalDate.parse(text,DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay() }
                local.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
            }
        }
    }.getOrNull()
    return time?.takeIf { it>0 && it<253402214400000L }
}
internal fun updatedDate(value:Long):String=Instant.ofEpochMilli(value).atZone(ZoneId.of("Asia/Shanghai"))
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
