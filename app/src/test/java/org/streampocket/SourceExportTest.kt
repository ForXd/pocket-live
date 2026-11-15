package org.streampocket

import org.junit.Assert.*
import org.junit.Test

class SourceExportTest {
    @Test fun allEnabledSourceFieldsRoundTripPastOrdinarySubscriptionLimits() {
        val sources=(1..120).map { Source("源 $it","https://example.org/$it",detail="https://example.org/detail",adult=true) } +
            Source("直播","https://example.org/list.m3u",SourceKind.LIVE,epg="https://example.org/epg")
        val imported=SubscriptionParser.parse(exportSources(sources + sources.first()))
        assertEquals(sources,imported.sources)
        assertEquals(0,imported.truncated)
    }
    @Test fun onlyCompletePasteReturnsAShareableLink() {
        assertEquals("https://paste.rs/ABC1",pasteLink(201,"https://paste.rs/ABC1\n"))
        for((code,link) in listOf(206 to "https://paste.rs/ABC",429 to "slow down",201 to "https://example.org/ABC")) {
            assertThrows(UserError::class.java) { pasteLink(code,link) }
        }
    }
}
