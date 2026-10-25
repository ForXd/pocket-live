// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.ForwardingRenderer

/** One player-owned renderer set. Called only by Media3 on its playback thread.
 * Media3 1.6.1 can abandon disable/release iteration after one renderer throws.
 * Preserve the first failure, attempt remaining public lifecycle cleanup, then report it.
 * The application reporter lets Media3 continue its non-renderer final cleanup.
 * No system callbacks, private fields, sleeps or global renderer registry are involved.
 */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
internal class RendererReleaseGroup(renderers:Array<Renderer>,private val afterDisable:((Renderer)->Unit)?=null,
    private val reportFailure:(RuntimeException)->Unit={ throw it }) {
    private var owned:List<Renderer> = renderers.toList()
    private var released=false
    val wrapped:Array<Renderer> = renderers.map { renderer ->
        object:ForwardingRenderer(renderer) {
            override fun disable() { renderer.disable();afterDisable?.invoke(renderer) }
            override fun release() = releaseAll()
        }
    }.toTypedArray()

    private fun releaseAll() {
        if(released) return
        released=true
        var failure:RuntimeException?=null
        fun attempt(block:()->Unit) {
            try { block() } catch(e:RuntimeException) {
                val first=failure
                if(first==null) failure=e else if(first!==e) first.addSuppressed(e)
            }
        }
        try {
            owned.forEach { renderer ->
                attempt { renderer.capabilities.clearListener() }
                // A prior renderer's disable failure may have left this renderer started/enabled.
                var recovered=false
                attempt { if(renderer.state==Renderer.STATE_STARTED) { recovered=true;renderer.stop() } }
                attempt { if(renderer.state==Renderer.STATE_ENABLED) { recovered=true;renderer.disable() } }
                if(recovered) attempt { renderer.reset() }
                attempt { renderer.release() }
            }
        } finally { owned=emptyList() }
        failure?.let(reportFailure)
    }
}

/** Inert unless debug instrumentation explicitly arms one failure; retains no app objects. */
internal object ReleaseFaultInjection {
    @Volatile var videoState=-1
    @Volatile var audioState=-1
    @Volatile var useOriginalRelease=false
    val pending=java.util.concurrent.atomic.AtomicBoolean(false)
    val triggered=java.util.concurrent.atomic.AtomicInteger(0)
}
internal class InjectedVideoDisableFailure:IllegalStateException("Injected video disable failure")
