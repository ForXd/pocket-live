package org.streampocket

import org.junit.Assert.*
import org.junit.Test

class SubscriptionJsonTest {
    private val json="""{"sources":[{"name":"测试","url":"https://example.org/api"}]}"""
    @Test fun streamUsesExistingParserForEverySupportedFormat() {
        val formats=listOf(json,"""[{"baseUrl":"https://example.org/api"}]""",
            """{"sites":[{"api":"https://example.org/api","type":1}]}""",
            """{"liveSources":[{"url":"https://example.org/live.m3u"}]}""")
        formats.forEach { text ->
            assertEquals(SubscriptionParser.parse(text),SubscriptionParser.parse(readSubscriptionJson(("\uFEFF"+text).byteInputStream())))
        }
    }
    @Test fun boundedReadRejectsEmptyOversizeAndInvalidUtf8() {
        listOf(byteArrayOf(),ByteArray(JsonData.LIMIT+1){32},byteArrayOf(0xc3.toByte(),0x28)).forEach { bytes ->
            try { readSubscriptionJson(bytes.inputStream());fail("must reject") } catch(_:UserError) {}
        }
        assertEquals(JsonData.LIMIT,readSubscriptionJson(ByteArray(JsonData.LIMIT){32}.inputStream()).length)
    }
    @Test fun localReplacementPreservesIdentityHistoryAndDisabledSources() {
        val parsed=SubscriptionParser.parse(json)
        val sub=localSubscription(parsed,json,"sources.json",null)
        assertTrue(sub.isLocalJson);assertEquals("sources.json",sub.name)
        assertNull(validUrl(sub.url))
        val playback=Playback(Video(parsed.sources.single(),"id","title"),Episode("ep","https://example.org/video"))
        val saved=Saved(listOf(sub),setOf(parsed.sources.single().key),listOf(History(playback,10)))
        val replacement=localSubscription(parsed,json+" ","new.json",sub)
        val updated=StateRules.importSubscription(saved,replacement,true)
        assertEquals(sub.id,replacement.id);assertEquals(sub.name,replacement.name)
        assertEquals(saved.disabled,updated.disabled);assertEquals(saved.history,updated.history)
        try { StateRules.importSubscription(saved,localSubscription(parsed,json,"copy.json",null),false);fail("duplicate") } catch(_:UserError) {}
        try { StateRules.importSubscription(Saved(),replacement,true);fail("deleted") } catch(_:UserError) {}
        try { localSubscription(parsed,json,"file",sub.copy(url="https://example.org/sub"));fail("remote") } catch(_:UserError) {}
    }
}
