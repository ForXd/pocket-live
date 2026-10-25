package org.streampocket

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveResumeTest {
    @Test fun movingLiveWindowNeverPersistsAnOldOffset() {
        listOf(-1L,0L,60000L,Long.MAX_VALUE).forEach { assertEquals(0L,resumePosition(true,it)) }
    }
    @Test fun vodKeepsItsResumeTimestamp() {
        assertEquals(98765L,resumePosition(false,98765L))
        assertEquals(0L,resumePosition(false,-1L))
    }
}
