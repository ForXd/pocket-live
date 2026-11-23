package org.streampocket

internal class SearchPresentation(val rows:List<Video>,query:String,showOthers:Boolean) {
    private val matches=rows.filter { SearchRules.relevance(it,query)>0 }
    val matchCount=matches.size
    private val candidates=if(showOthers) rows else matches
    val groups=VideoGroups.searchGroups(candidates)
    val counts=groups.flatMap { group -> group.flatMap { Genres.of(it) }.distinct() }.groupingBy { it }.eachCount()
    fun groupsFor(category:Genre)=if(category==Genre.ALL) groups
        else VideoGroups.searchGroups(candidates.filter { Genres.matches(it,category) })
}
