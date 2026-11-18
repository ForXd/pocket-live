// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clipToBounds
import androidx.core.graphics.createBitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

internal class CardReturn(private val scope: CoroutineScope) {
    var progress by mutableFloatStateOf(0f)
        private set
    var direction = 1f
        private set
    var width = 0
    private var startX = 0f
    private var settling: Job? = null
    private var committed = false

    fun start(event: BackEventCompat) {
        if (committed) return
        settling?.cancel()
        startX = event.touchX
        direction = if (event.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
        progress = 0f
    }

    fun drag(event: BackEventCompat) {
        if (committed) return
        progress = if (width > 0 && event.touchX.isFinite() && event.touchX != startX)
            (abs(event.touchX-startX)/width).coerceIn(0f,1f) else event.progress.coerceIn(0f,1f)
    }

    fun cancel() {
        if (committed) return
        settling?.cancel()
        settling = scope.launch {
            animate(progress,0f,animationSpec=tween(130)) { value,_ -> progress=value }
            direction = 1f
        }
    }

    fun finish(action: () -> Unit) {
        if (committed) return
        committed = true
        settling?.cancel()
        settling = scope.launch {
            animate(progress,1f,animationSpec=tween((170*(1f-progress)).toInt(),easing=FastOutSlowInEasing)) { value,_ -> progress=value }
            action()
            withFrameNanos { }
            // An action may just dismiss the keyboard or leave the same route selected.
            committed=false
            progress=0f
            direction=1f
        }
    }
}

internal val LocalCardReturn = staticCompositionLocalOf<CardReturn?> { null }
internal fun CardReturn?.commit(action: () -> Unit) {
    if (this == null) action() else finish(action)
}

@Composable internal fun CardBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val motion = LocalCardReturn.current
    val latest by rememberUpdatedState(onBack)
    val dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
    val owner = LocalLifecycleOwner.current
    val callback = remember(motion) {
        object : OnBackPressedCallback(enabled) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) { motion?.start(backEvent) }
            override fun handleOnBackProgressed(backEvent: BackEventCompat) { motion?.drag(backEvent) }
            override fun handleOnBackCancelled() { motion?.cancel() }
            override fun handleOnBackPressed() { motion.commit(latest) }
        }
    }
    SideEffect { callback.isEnabled = enabled }
    DisposableEffect(owner, dispatcher, callback) {
        dispatcher.addCallback(owner, callback)
        onDispose { callback.remove() }
    }
}

private class ReturnPreview(val bounds: Rect) {
    var image by mutableStateOf<ImageBitmap?>(null)
}

private class RoutePreviews {
    private val entries = mutableMapOf<String,ReturnPreview>()
    private val handler = Handler(Looper.getMainLooper())
    var current: String? = null
    var bounds = Rect()

    fun enter(routes: List<String>, window: Window?) {
        val previous=current
        current=routes.last()
        if(previous==current) return
        entries.keys.retainAll(routes.dropLast(1).toSet())
        if(previous==null || previous !in routes || window==null || bounds.isEmpty) return
        // Copy the last presented buffer before Compose draws the destination. A display
        // list can still refer to live child layers, which change when those nodes are reused.
        val preview=ReturnPreview(Rect(bounds))
        entries[previous]=preview
        val scale=minOf(1.0,1024.0/maxOf(bounds.width(),bounds.height()))
        val bitmap=createBitmap((bounds.width()*scale).roundToInt().coerceAtLeast(1),
            (bounds.height()*scale).roundToInt().coerceAtLeast(1))
        try {
            PixelCopy.request(window,preview.bounds,bitmap,{ result ->
                if(result==PixelCopy.SUCCESS && entries[previous]===preview) preview.image=bitmap.asImageBitmap()
                else bitmap.recycle()
            },handler)
        } catch (_: IllegalArgumentException) {
            bitmap.recycle() // The window lost its surface during a configuration change.
        }
    }

    fun parent(routes:List<String>)=entries[routes.getOrNull(routes.lastIndex-1)]
    fun clear()=entries.clear()
}

/** Keep only bounded, in-memory previews of ancestors. Forward navigation just draws the new screen. */
@Composable internal fun CardRouteHost(routes: List<String>, content: @Composable () -> Unit) {
    val window=LocalActivity.current?.window
    val previews=remember(window) { RoutePreviews() }
    DisposableEffect(previews) { onDispose { previews.clear() } }
    val scope=key(routes.last()) { rememberCoroutineScope() }
    val motion=remember(scope) { CardReturn(scope) }
    SideEffect { previews.enter(routes,window) }
    val surface=MaterialTheme.colorScheme.background
    CompositionLocalProvider(LocalCardReturn provides motion) {
        Box(Modifier.fillMaxSize().clipToBounds().onGloballyPositioned {
            val rect=it.boundsInWindow()
            previews.bounds=Rect(rect.left.roundToInt(),rect.top.roundToInt(),rect.right.roundToInt(),rect.bottom.roundToInt())
            motion.width=it.size.width
        }.drawBehind {
            drawRect(surface)
            val t=motion.progress
            if(t>0f) {
                val preview=previews.parent(routes)
                val image=preview?.image
                if(image!=null && preview.bounds.width()==size.width.roundToInt() && preview.bounds.height()==size.height.roundToInt()) {
                    withTransform({
                        translate(-motion.direction*size.width*.055f*(1f-t),0f)
                        scale(.975f+.025f*t,.975f+.025f*t,center)
                    }) {
                        drawImage(image,dstSize=IntSize(size.width.roundToInt(),size.height.roundToInt()))
                        drawRect(Color.Black.copy(alpha=.22f*(1f-t)))
                    }
                }
            }
        }) {
            Box(Modifier.fillMaxSize().graphicsLayer { translationX=motion.direction*size.width*motion.progress }
                .background(surface)) { content() }
        }
    }
}

/** Keep Material ripple and click semantics; transform only the rendered surface. */
@Composable fun Modifier.pressFeedback(interactions:MutableInteractionSource):Modifier {
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if(pressed) .98f else 1f,
        spring(dampingRatio=Spring.DampingRatioNoBouncy,stiffness=Spring.StiffnessMedium),label="cardPress")
    return graphicsLayer { scaleX=scale;scaleY=scale }
}
