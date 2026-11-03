package org.streampocket

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

class IdentityPerformanceTest {
    @Test fun persistedKeysRemainCompatibleAcrossABatchOfSources() {
        val inputs = List(300) { "VOD|https://fixture.test/频道/$it" }
        fun legacy(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
        fun sample(block: (String) -> String): Pair<Long, List<String>> {
            val start = SystemClock.elapsedRealtimeNanos()
            val keys = inputs.map(block)
            return (SystemClock.elapsedRealtimeNanos() - start) / 1000 to keys
        }
        val before = mutableListOf<Long>()
        val after = mutableListOf<Long>()
        repeat(9) { round ->
            val old = if (round % 2 == 0) sample(::legacy) else null
            val new = sample(::hash)
            val baseline = old ?: sample(::legacy)
            assertEquals(baseline.second, new.second)
            if (round >= 2) {
                before.add(baseline.first)
                after.add(new.first)
            }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putLong("legacy_300_keys_us", before.sorted()[3])
            putLong("current_300_keys_us", after.sorted()[3])
        })
    }
}
