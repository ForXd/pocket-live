package org.streampocket
import org.junit.Assert.*
import org.junit.Test
class RecommendationSynopsisTest {
    @Test fun parsesSummaryAndPrefersCompleteTextWithoutExecutingMarkup() {
        assertEquals("Hello & world\n\nNext",recommendationSynopsis("<span property='v:summary'>Hello &amp; world<br/>Next</span>"))
        assertEquals("Complete story",recommendationSynopsis("<span property='v:summary'>Short</span><span class='all hidden'>Complete <b>story</b></span>"))
        assertNull(recommendationSynopsis("<html>Sign in</html>"))
    }
}
