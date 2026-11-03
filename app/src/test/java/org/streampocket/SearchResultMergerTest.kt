package org.streampocket

import org.junit.Assert.*
import org.junit.Test

class SearchResultMergerTest {
    @Test fun measuredQualityFollowsChangedHistoryAndFullListIsReused() {
        val source = Source("Test", "https://test.example/api")
        val item = Video(source, "1", "Test")
        val merger = SearchResultMerger()
        assertEquals(item, merger.append(emptyList(), listOf(item), emptyList()).single())
        val measured = QualityRules.measured(1920, 1080)
        val history = History(Playback(item.copy(quality = measured), Episode("1", "https://test.example/1"), 0), 1)
        assertEquals(measured, merger.append(emptyList(), listOf(item), listOf(history)).single().quality)
        val full = List(QualityRules.RESULT_LIMIT) { item.copy(id = it.toString()) }
        assertSame(full, merger.append(full, listOf(item.copy(id = "new")), emptyList()))
    }
}
