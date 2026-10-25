package org.streampocket

import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class RendererReleaseGroupTest {
    private class Fixture(val name:String,var state:Int,val events:MutableList<String>,val disableFailure:RuntimeException?=null,val releaseFailure:RuntimeException?=null) {
        private val capabilities=Proxy.newProxyInstance(RendererCapabilities::class.java.classLoader,arrayOf(RendererCapabilities::class.java)) { _,method,_ ->
            if(method.name=="clearListener") { events+="$name.clear";null } else null
        } as RendererCapabilities
        val renderer=Proxy.newProxyInstance(Renderer::class.java.classLoader,arrayOf(Renderer::class.java)) { _,method,_ ->
            when(method.name) {
                "getCapabilities" -> capabilities
                "getState" -> state
                "stop" -> { events+="$name.stop";state=Renderer.STATE_ENABLED;null }
                "disable" -> { events+="$name.disable";state=Renderer.STATE_DISABLED;disableFailure?.let { throw it };null }
                "reset" -> { check(state==Renderer.STATE_DISABLED);events+="$name.reset";null }
                "release" -> { check(state==Renderer.STATE_DISABLED);events+="$name.release";releaseFailure?.let { throw it };null }
                else -> null
            }
        } as Renderer
    }
    @Test fun disableFailureDoesNotSkipAudioOrLaterRendererAndIsRethrown() {
        val events=mutableListOf<String>();val original=IllegalStateException("injected codec flush")
        val video=Fixture("video",Renderer.STATE_ENABLED,events,disableFailure=original)
        val audio=Fixture("audio",Renderer.STATE_STARTED,events)
        val text=Fixture("text",Renderer.STATE_DISABLED,events)
        val group=RendererReleaseGroup(arrayOf(video.renderer,audio.renderer,text.renderer))
        try { group.wrapped[0].release();fail("failure must be reported") } catch(e:IllegalStateException) { assertSame(original,e) }
        assertEquals(listOf("video.clear","video.disable","video.reset","video.release","audio.clear","audio.stop","audio.disable","audio.reset","audio.release","text.clear","text.release"),events)
        val count=events.size;group.wrapped.forEach { it.release() };assertEquals(count,events.size)
    }
    @Test fun independentReleaseFailuresArePreservedAfterAllCleanupAttempts() {
        val events=mutableListOf<String>();val first=IllegalStateException("first");val second=IllegalArgumentException("second")
        val group=RendererReleaseGroup(arrayOf(Fixture("video",0,events,releaseFailure=first).renderer,Fixture("audio",0,events,releaseFailure=second).renderer,Fixture("text",0,events).renderer))
        try { group.wrapped[0].release();fail() } catch(e:IllegalStateException) { assertSame(first,e);assertArrayEquals(arrayOf(second),e.suppressed) }
        assertTrue(events.contains("text.release"))
    }
    @Test fun reportingFailureAllowsCallerToContinueFinalNonRendererCleanup() {
        val events=mutableListOf<String>();val original=IllegalStateException("injected release")
        var reported:RuntimeException?=null
        val group=RendererReleaseGroup(arrayOf(Fixture("video",0,events,releaseFailure=original).renderer,Fixture("audio",0,events).renderer),reportFailure={ reported=it })
        group.wrapped[0].release()
        events+="caller.finalCleanup"
        assertSame(original,reported)
        assertTrue(events.indexOf("audio.release")<events.indexOf("caller.finalCleanup"))
    }

}
