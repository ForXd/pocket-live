// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Subscription order determines which duplicate source wins across all browsing paths. */
internal fun Saved.enabledSources(kind: SourceKind? = null): List<Source> =
    subscriptions.asSequence()
        .flatMap { it.sources.asSequence() }
        .filter { it.key !in disabled && (kind == null || it.kind == kind) }
        .distinctBy { it.key }
        .toList()

internal class SearchRepository(private val network: Network) {
    suspend fun search(source: Source, query: String): List<Video> = withContext(Dispatchers.Default) {
        when (source.kind) {
            SourceKind.LIVE -> CmsParser.playlist(network.text(source.url), source)
                .filter { SearchRules.relevance(it, query) > 0 }
            SourceKind.VOD -> CmsParser.search(
                network.text(network.api(source, "wd", query), cacheSearch = true), source
            )
        }
    }
}

/** One instance per query. Rebuild the measured-quality index only when history changes. */
internal class SearchResultMerger {
    private var indexedHistory: List<History>? = null
    private var measured = emptyMap<String, VideoQuality>()

    fun append(current: List<Video>, incoming: List<Video>, history: List<History>,
        rank: ((List<Video>) -> List<Video>)? = null): List<Video> {
        if ((rank == null && current.size >= QualityRules.RESULT_LIMIT) || incoming.isEmpty()) return current
        if (history !== indexedHistory) {
            measured = history.asReversed().asSequence().map { it.playback.video }
                .filter { it.quality.evidence == QualityEvidence.MEASURED }
                .associate { (it.source.key + ":" + it.id) to it.quality }
            indexedHistory = history
        }
        val enriched = if (measured.isEmpty()) incoming else incoming.map { video ->
            measured[video.source.key + ":" + video.id]?.let { video.copy(quality = it) } ?: video
        }
        return if (rank == null) QualityRules.merge(current, enriched)
        else rank((current + enriched).distinctBy { it.source.key + ":" + it.id }).let { if(it.size<=QualityRules.RESULT_LIMIT) it else it.take(QualityRules.RESULT_LIMIT) }
    }
}

/** Serial, query-local merge. Existing rows precede new ones on equal scores. */
internal class QueryRanking(private val query:String) {
    private val scores=java.util.IdentityHashMap<Video,Int>()
    private var previousOrder:SearchOrder?=null
    private var previous:List<Video>?=null

    fun merge(rows:List<Video>, current:List<Video>, order:SearchOrder, pinned:Boolean):List<Video> {
        val quality=compareByDescending<Video> { it.quality.pixels }.thenByDescending { it.quality.evidence.ordinal }
        val comparator=if(order==SearchOrder.QUALITY) quality else
            compareByDescending<Video> { video -> scores.getOrPut(video) { SearchRules.relevance(video,query) } }.then(quality)
        val result=when {
            pinned -> rows.take(QualityRules.RESULT_LIMIT)
            previous !== current || previousOrder!=order -> rows.sortedWith(comparator).take(QualityRules.RESULT_LIMIT)
            else -> {
                val added=rows.drop(current.size).sortedWith(comparator)
                buildList {
                    var old=0
                    var new=0
                    while(size<QualityRules.RESULT_LIMIT && (old<current.size || new<added.size)) {
                        if(new==added.size || (old<current.size && comparator.compare(current[old],added[new])<=0)) add(current[old++])
                        else add(added[new++])
                    }
                }
            }
        }
        val retained=java.util.IdentityHashMap<Video,Boolean>()
        result.forEach { retained[it]=true }
        scores.keys.retainAll(retained.keys)
        previous=result
        previousOrder=if(pinned) null else order
        return result
    }
}
