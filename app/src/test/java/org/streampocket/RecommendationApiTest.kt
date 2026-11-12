package org.streampocket
import org.junit.Assert.*
import org.junit.Test
class RecommendationApiTest {
    @Test fun apiIntroIsAcceptedButWrongSubjectAndEmptyIntroAreRejected() {
        assertEquals("A real synopsis",recommendationApiSynopsis("""{"id":"1292052","intro":"A real synopsis"}""","1292052"))
        assertNull(recommendationApiSynopsis("""{"id":"other","intro":"Wrong movie"}""","1292052"))
        assertNull(recommendationApiSynopsis("""{"id":"1292052","intro":""}""","1292052"))
    }
}
