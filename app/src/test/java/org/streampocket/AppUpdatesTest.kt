package org.streampocket

import org.junit.Test
import org.junit.Assert.*

class AppUpdatesTest {
    private fun release(version:String="0.6.0")="""{"draft":false,"prerelease":false,"tag_name":"v$version","html_url":"https://github.com/ForXd/pocket-live/releases/tag/v$version","body":"Changes","assets":[{"name":"pocket-live-$version-release.apk","state":"uploaded","size":2450000,"browser_download_url":"https://github.com/ForXd/pocket-live/releases/download/v$version/pocket-live-$version-release.apk"}]}"""
    @Test fun comparesNumbersNotLexicalTagsAndRejectsPrereleases() {
        assertTrue(AppUpdates.newer("v0.10.0","0.9.9"));assertTrue(AppUpdates.newer("1.0.0","0.99.99"))
        for(tag in listOf("v0.5.0","v0.4.9","v0.6.0-beta","v999999999.0.0","latest")) assertFalse(AppUpdates.newer(tag,"0.5.0"))
        assertNull(AppUpdates.parse(release().replace("\"prerelease\":false","\"prerelease\":true"),"0.5.0"))
        assertNull(AppUpdates.parse(release("0.5.0"),"0.5.0"))
    }
    @Test fun selectsOnlyUploadedOfficialVersionedApk() {
        val result=AppUpdates.parse(release(),"0.5.0")!!
        assertEquals("0.6.0",result.version);assertTrue(result.url.endsWith("pocket-live-0.6.0-release.apk"))
        for(bad in listOf(release().replace("github.com/ForXd","evil.example/ForXd"),release().replace("download/v0.6.0/","download/v0.4.0/"),release().replace("2450000","999999999"),release().replace("\"uploaded\"","\"new\""))) {
            try { AppUpdates.parse(bad,"0.5.0");fail("Untrusted or incomplete release accepted") } catch(_:UserError) { }
        }
    }
}
