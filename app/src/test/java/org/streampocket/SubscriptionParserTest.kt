package org.streampocket

import org.junit.Assert.*
import org.junit.Test

class SubscriptionParserTest {
    @Test fun sourceListPreservesFieldsAndDoesNotRequireVersion() {
        val r=SubscriptionParser.parse("""{"sources":[{"url":"https://example.org/api/??","detail":"https://example.org/detail/","isAdult":true}],"liveSources":[{"url":"https://example.org/live/","epg":"javascript:bad"}]}""")
        assertEquals("https://example.org/api",r.sources[0].url)
        assertEquals("example.org",r.sources[0].name)
        assertTrue(r.sources[0].adult)
        assertEquals("https://example.org/detail/",r.sources[0].detail)
        assertEquals("https://example.org/live/",r.sources[1].url)
        assertNull(r.sources[1].epg)
    }
    @Test fun bareVodAndPureLive() {
        assertEquals(SourceKind.VOD,SubscriptionParser.parse("""[{"url":"https://example.org"}]""").sources.single().kind)
        assertEquals(SourceKind.LIVE,SubscriptionParser.parse("""{"liveSources":[{"url":"https://example.org/m3u"}]}""").sources.single().kind)
    }
    @Test fun catalogDisabledAndMixedErrors() {
        val r=SubscriptionParser.parse("""[{"baseUrl":"https://example.org/a","enabled":false},{"baseUrl":"https://example.org/b","enabled":"false"}]""")
        assertEquals(1,r.sources.size); assertEquals(1,r.skipped)
        fails("""[{"baseUrl":"https://example.org/a"},{"url":"https://example.org/b"}]""")
    }
    @Test fun controlsCommentsBomTrailingCommasLeaveUrlsIntact() {
        val r=SubscriptionParser.parse("\uFEFF/*x*/{\"sources\":[{\"name\":\"first\nsecond\",\"url\":\"https://example.org/a?x=//value\",},],//x\n}")
        assertEquals("https://example.org/a?x=//value",r.sources.single().url)
        assertEquals("first\nsecond",r.sources.single().name)
    }
    @Test fun tvboxTypesScriptsSearchableAndM3u() {
        val r=SubscriptionParser.parse("""{"sites":[
          {"api":"https://example.org/one","type":"1"},
          {"api":"https://example.org/api.php/provide/vod","type":4},
          {"api":"https://example.org/api.php/provide/vod/at/xml","type":0},
          {"api":"https://example.org/a.js","type":1},
          {"api":"https://example.org/blocked","type":1,"searchable":"0"},
          {"api":"csp_x","type":3}],"lives":[{"url":"https://example.org/live"},{"url":"https://example.org/live.txt"}]}""")
        assertEquals(3,r.sources.size); assertEquals(5,r.skipped)
    }
    @Test fun tvboxHasPrecedenceOverSources() {
        val r=SubscriptionParser.parse("""{"sites":[{"type":1,"api":"https://example.org/a"}],"sources":[{"url":"https://example.org/b"}]}""")
        assertEquals("https://example.org/a",r.sources.single().url)
    }
    @Test fun dedupBeforeLimitAndRealFragmentsPreserved() {
        val repeated=List(110){"""{"url":"https://example.org/a/"}"""}
        val unique=List(101){"""{"url":"https://example.org/$it"}"""}
        val r=SubscriptionParser.parse((repeated+unique).joinToString(",","[","]"))
        assertEquals(100,r.sources.size); assertEquals(2,r.truncated)
        assertEquals("https://example.org/a?token=1#keep",validUrl("https://example.org/a?token=1#keep",true))
    }
    @Test fun rejectsUnknownMalformedExecutableDeepAndOversize() {
        listOf("{}","[]","{\"urls\":[]}","#EXTM3U", "eyJzb3VyY2VzIjpbfQ==", "{\"sources\":[{\"url\":\"javascript:alert(1)\"}]}","[".repeat(30)+"]".repeat(30),"{bad}","{}{}","/* missing", " ".repeat(JsonData.LIMIT+1)).forEach { fails(it) }
    }
    @Test fun carriageReturnCommentAndTypedTvboxFields() {
        val json="{\"sites\":[// comment\r{\"type\":\"0x1\",\"api\":\"https://example.org/a\",\"isAdult\":true},{\"type\":1,\"api\":\"https://example.org/b\",\"searchable\":0.0}]}"
        val r=SubscriptionParser.parse(json)
        assertEquals(1,r.sources.size)
        assertFalse(r.sources.single().adult)
        assertEquals(1,r.skippedByReason["不支持搜索"] ?: 0)
    }
    @Test fun tvboxNumberConversionDoesNotAcceptJavaSuffixes() {
        val r=SubscriptionParser.parse("""{"sites":[{"type":"1f","api":"https://example.org/custom"},{"type":"0x1","api":"https://example.org/direct"}]}""")
        assertEquals("https://example.org/direct",r.sources.single().url)
        assertEquals(1,r.skipped)
    }
    @Test fun urlSchemesCredentialsControlsAndStructureBudget() {
        assertNull(validUrl("https:example.org"))
        assertNull(validUrl("https://user:secret@example.org"))
        assertNull(validUrl("https://example.org/a\nb"))
        fails(List(100_001){"0"}.joinToString(",","[","]"))
    }
    private fun fails(text:String) { try { SubscriptionParser.parse(text); fail("expected rejection") } catch(_:UserError) {} }
}
