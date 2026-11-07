// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

/** Genres are independent of movie/series format and never inferred from the title. */
enum class Genre(val label: String, vararg val names: String) {
    ALL("全部题材"), ROMANCE("爱情", "爱情", "爱恋", "romance"),
    MYSTERY("悬疑", "悬疑", "推理", "mystery"), COMEDY("喜剧", "喜剧", "comedy"),
    ACTION("动作", "动作", "action"), SCIFI("科幻", "科幻", "sciencefiction", "scifi"),
    THRILLER("惊悚", "惊悚", "thriller"), HORROR("恐怖", "恐怖", "horror"),
    CRIME("犯罪", "犯罪", "crime"), DRAMA("剧情", "剧情", "drama"),
    ADVENTURE("冒险", "冒险", "adventure"), FANTASY("奇幻", "奇幻", "fantasy"),
    WAR("战争", "战争", "war"), HISTORY("历史", "历史", "history", "historical"),
    FAMILY("家庭", "家庭", "family"), UNKNOWN("未标注题材");

    fun matchesLabel(value: String): Boolean {
        val normalized = SearchRules.normalized(value)
        return names.any { name ->
            if(name.all { it.code < 128 }) value.lowercase().split(Regex("[^a-z]+"))
                .any { it == name } || normalized == name
            else normalized.contains(name)
        }
    }
}

internal object Genres {
    fun of(video: Video): Set<Genre> = Genre.entries.filter {
        it != Genre.ALL && it != Genre.UNKNOWN && (it.matchesLabel(video.genreText) || it.matchesLabel(video.typeName))
    }.toSet().ifEmpty { setOf(Genre.UNKNOWN) }

    fun matches(video: Video, genre: Genre) = genre == Genre.ALL || genre in of(video)

    fun matchesAny(video:Video, genres:Set<Genre>) = genres.isEmpty() || Genre.ALL in genres || of(video).any { it in genres }

    // Numeric category IDs belong to this source. Do not map a genre to an arbitrary movie/TV ID.
    fun categoryIds(categories: List<Category>, genre: Genre): List<String> =
        if(genre == Genre.ALL || genre == Genre.UNKNOWN) emptyList()
        else categories.filter { genre.matchesLabel(it.name) }.map { it.id }.distinct()
}
