package org.streampocket

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Test
import org.junit.Assert.*

/** Explicit online smoke test; not part of the offline fixture suite. */
class LiveUpdateDeviceTest {
    @Test fun officialLatestReleaseOffersReachableApk()=runBlocking {
        val network=Network({false})
        try {
            val release=AppUpdates.parse(network.text(AppUpdates.API),"0.0.0")
            assertNotNull("The official repository must publish a stable APK",release)
            network.client.newCall(Request.Builder().url(release!!.url).head().build()).execute().use {
                assertTrue("Official APK endpoint must be reachable",it.isSuccessful)
                assertEquals(release.bytes,it.header("Content-Length")!!.toLong())
            }
        } finally { network.close() }
    }
}
