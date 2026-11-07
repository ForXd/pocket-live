package org.streampocket

import org.junit.Assert.*
import org.junit.Test

class SearchRulesTest {
    private val source = Source("Test", "https://test.example/api")
    private fun video(title: String, type: String = "") = Video(source, title, title, typeName = type)

    @Test fun titleRelevancePrecedesResolutionAndKeepsExplicitQualitySort() {
        val exact = video("星河")
        val unrelated = video("星球").copy(quality = QualityRules.measured(3840, 2160))
        val sequel = video("星河第二季")
        val items = listOf(unrelated, sequel, exact)
        assertEquals(listOf(exact, sequel, unrelated), SearchRules.sorted(items, "星河", SearchOrder.RELEVANCE))
        assertEquals(unrelated, SearchRules.sorted(items, "星河", SearchOrder.QUALITY).first())
    }

    @Test fun punctuationWidthCaseAliasesAndAllTermsAreMatched() {
        assertEquals(4, SearchRules.relevance(video("ＡＢＣ：星 河"), "abc星河"))
        assertEquals(1, SearchRules.relevance(video("星河第三季 特别篇"), "星河 特别"))
        assertEquals(0, SearchRules.relevance(video("星河"), "星河 特别"))
        assertEquals(1, SearchRules.relevance(video("星河").copy(alias="Galaxy"), "galaxy"))
        assertEquals(0, SearchRules.relevance(video("星河"), "!!!"))
    }

    @Test fun metadataKeepsEpisodeInformationWithoutDuplicatingQuality() {
        val item=video("星河", "科幻片").copy(subtitle="2026 · 1080p", quality=QualityRules.sourceLabel("1080p"))
        assertEquals("科幻片 · 2026", SearchRules.metadata(item))
        assertEquals("科幻片 · 更新至12集 1080p", SearchRules.metadata(item.copy(subtitle="更新至12集 1080p")))
    }

    @Test fun categoriesUseSourceNamesNotSourceSpecificIds() {
        assertEquals(MediaCategory.MOVIE, SearchRules.category(video("A", "动作片")))
        assertEquals(MediaCategory.SERIES, SearchRules.category(video("A", "国产剧")))
        assertEquals(MediaCategory.SERIES, SearchRules.category(video("A", "韩国剧")))
        assertEquals(MediaCategory.OTHER, SearchRules.category(video("A", "Drama")))
        assertEquals(MediaCategory.ANIMATION, SearchRules.category(video("A", "动画电影")))
        assertEquals(MediaCategory.VARIETY, SearchRules.category(video("A", "大陆综艺")))
        assertEquals(MediaCategory.OTHER, SearchRules.category(video("A").copy(typeId="1")))
        assertEquals(MediaCategory.LIVE, SearchRules.category(video("A").copy(source=source.copy(kind=SourceKind.LIVE))))
    }

    @Test fun parserRetainsCategoriesAndAliasesAndCatalogCanResolveNames() {
        val json = """{"class":[{"type_id":7,"type_name":"电影"}],"list":[{"vod_id":1,"vod_name":"星河","type_id":7,"vod_sub":"Galaxy"}]}"""
        val parsed = CmsParser.search(json, source).single()
        assertEquals("7", parsed.typeId)
        assertEquals("Galaxy", parsed.alias)
        assertEquals(MediaCategory.MOVIE, SearchRules.category(Catalog.parse(json, source, 1).videos.single()))
    }

    @Test fun lateExactMatchCanReplaceUnrelatedRowsAtResultLimit() {
        val current = List(QualityRules.RESULT_LIMIT) { video("无关$it") }
        val exact = video("星河")
        val result = SearchResultMerger().append(current, listOf(exact), emptyList()) {
            SearchRules.sorted(it, "星河", SearchOrder.RELEVANCE)
        }
        assertEquals(QualityRules.RESULT_LIMIT, result.size)
        assertEquals(exact, result.first())
    }
}
