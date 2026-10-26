package org.streampocket

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ApkUpdatePolicyTest {
    private val release=AppRelease("9.0.0","https://github.com/ForXd/pocket-live/releases/download/v9.0.0/pocket-live-9.0.0-release.apk","https://github.com/ForXd/pocket-live/releases/tag/v9.0.0","",3,"a".repeat(64))
    private fun rejected(block:()->Unit) { try { block();fail("Unsafe update accepted") } catch(_:UserError) {} }
    @Test fun streamRejectsOversizeTruncationAndLargeAllocation() {
        rejected { copyUpdate(ByteArrayInputStream(byteArrayOf(1,2,3)),ByteArrayOutputStream(),2) }
        rejected { copyUpdate(ByteArrayInputStream(byteArrayOf(1)),ByteArrayOutputStream(),2) }
        rejected { copyUpdate(ByteArrayInputStream(byteArrayOf()),ByteArrayOutputStream(),12_000_001) }
        val out=ByteArrayOutputStream()
        val digest=copyUpdate(ByteArrayInputStream("abc".toByteArray()),out,3)
        assertEquals("abc",out.toString());assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",digest)
    }
    @Test fun acceptsOnlyBoundedOfficialApkWithDigest() {
        AppUpdates.validateDownload(release)
        assertEquals(release.copy(notes=""),AppUpdates.restoreDownload(AppUpdates.storeDownload(release)))
        for(bad in listOf(release.copy(url=release.url.replace("github.com","example.com")),release.copy(bytes=12_000_001),release.copy(sha256=null),release.copy(sha256="bad"),release.copy(version="9.0.0-beta"))) rejected { AppUpdates.validateDownload(bad) }
    }
    @Test fun rejectsOtherAppsSignaturesAndDowngrades() {
        val installed=ApkIdentity("org.streampocket","0.8.0",14,setOf("signer"))
        val next=ApkIdentity("org.streampocket","9.0.0",15,setOf("signer"))
        validateUpdateIdentity(next,installed,release)
        for(bad in listOf(next.copy(packageName="evil.app"),next.copy(code=14),next.copy(version="8.0.0"),next.copy(signers=setOf("other")),next.copy(signers=emptySet()))) rejected { validateUpdateIdentity(bad,installed,release) }
    }
}
