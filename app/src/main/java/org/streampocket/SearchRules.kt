// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import java.text.Normalizer
import java.util.Locale

enum class SearchOrder(val label: String) {
    RELEVANCE("相关度"), QUALITY("高清优先")
}

enum class MediaCategory(val label: String) {
    ALL("全部"), MOVIE("电影"), SERIES("剧集"), ANIMATION("动漫"), VARIETY("综艺"), LIVE("直播"), OTHER("其他")
}

/** Ranking uses titles and declared aliases. Quality only breaks relevance ties. */
internal object SearchRules {
    val qualityOnly = Regex("(?i)^(?:2160p|1440p|1080p|720p|576p|480p|360p|240p|4k|fhd|hd|蓝光|超清|高清)$")
    fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun relevance(video: Video, query: String): Int {
        val phrase = normalized(query)
        if (phrase.isEmpty()) return 0
        val title = normalized(video.title)
        if (title == phrase) return 4
        if (title.startsWith(phrase)) return 3
        if (title.contains(phrase)) return 2
        val terms = query.trim().split(Regex("\\s+")).map(::normalized).filter { it.isNotEmpty() }
        if (terms.size > 1 && terms.all { title.contains(it) }) return 1
        if (video.alias.isNotBlank() && normalized(video.alias).contains(phrase)) return 1
        return 0
    }

    fun sorted(videos: List<Video>, query: String, order: SearchOrder): List<Video> {
        if (order == SearchOrder.QUALITY) return QualityRules.sorted(videos)
        // Compute normalization once per row, not repeatedly inside the comparator.
        return videos.map { it to relevance(it, query) }.sortedWith(
            compareByDescending<Pair<Video, Int>> { it.second }
                .thenByDescending { it.first.quality.pixels }
                .thenByDescending { it.first.quality.evidence.ordinal }
        ).map { it.first }
    }

    fun metadata(video: Video): String {
        // Drop only a standalone quality declaration; retain episode/update wording.
        val parts = video.subtitle.split(" · ").filterNot { video.quality.evidence != QualityEvidence.UNKNOWN && qualityOnly.matches(it.trim()) }
        return (listOf(video.typeName, video.genreText).distinct() + parts).filter { it.isNotBlank() }.joinToString(" · ")
    }

    fun category(video: Video): MediaCategory {
        if (video.source.kind == SourceKind.LIVE) return MediaCategory.LIVE
        val name = normalized(video.typeName)
        return when {
            listOf("动漫", "动画", "animation", "anime").any(name::contains) -> MediaCategory.ANIMATION
            listOf("综艺", "真人秀", "variety").any(name::contains) -> MediaCategory.VARIETY
            listOf("电视剧", "剧集", "国产剧", "港剧", "香港剧", "台剧", "台湾剧", "港台剧", "欧美剧", "美剧", "英剧", "日剧", "日本剧", "韩剧", "韩国剧", "日韩剧", "泰剧", "泰国剧", "海外剧", "短剧", "series", "tvdrama").any(name::contains) -> MediaCategory.SERIES
            name.contains("电影") || name.endsWith("片") || name.contains("movie") || name.contains("film") -> MediaCategory.MOVIE
            else -> MediaCategory.OTHER
        }
    }
}
