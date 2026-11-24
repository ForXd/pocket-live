// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import org.junit.Assert.assertEquals
import org.junit.Test

class FullscreenTitleTest {
    @Test fun episodeLabelsPrecedeTheTitle() {
        for (number in listOf("1", "01", " 001 ")) {
            assertEquals("第1集 · 山海", fullscreenPlaybackTitle("山海", number, false))
        }
        for (label in listOf("第1集", "EP01", "特别篇")) {
            assertEquals("$label · 山海", fullscreenPlaybackTitle("山海", label, false))
        }
        assertEquals("山海", fullscreenPlaybackTitle("山海", "山海", false))
        assertEquals("山海", fullscreenPlaybackTitle("山海", "", false))
        assertEquals("山海", fullscreenPlaybackTitle("山海", "1", true))
    }
}
