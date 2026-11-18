// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal class PosterFlight(val video: Video, val image: ImageBitmap?, val origin: Rect, val visible: Rect) {
    val progress = Animatable(0f)
    var target by mutableStateOf<Rect?>(null)
}

internal class PosterExpansion {
    var flight by mutableStateOf<PosterFlight?>(null)
}
internal val LocalPosterExpansion = staticCompositionLocalOf<PosterExpansion?> { null }

internal class PosterAnchor(private val host: PosterExpansion?, private val video: Video,
    private val model: AppModel, private val large: Boolean) {
    private var coordinates: LayoutCoordinates? = null
    val modifier = Modifier.onGloballyPositioned { coordinates = it }

    fun open(destination: Video, navigate: () -> Unit) {
        val layout = coordinates
        if (host != null && layout != null && layout.isAttached) {
            val visible = layout.boundsInRoot()
            if (!visible.isEmpty) {
                val origin = Rect(layout.localToRoot(Offset.Zero),
                    layout.localToRoot(Offset(layout.size.width.toFloat(),layout.size.height.toFloat())))
                val image = model.posters.cached(video.poster, large) ?: model.posters.cached(video.poster)
                host.flight = PosterFlight(destination, image?.asImageBitmap(), origin, visible)
            }
        }
        navigate()
    }
}

@Composable internal fun rememberPosterAnchor(video: Video, model: AppModel, large: Boolean = false): PosterAnchor {
    val host = LocalPosterExpansion.current
    return remember(host, video, model, large) { PosterAnchor(host, video, model, large) }
}

@Composable internal fun Modifier.posterDestination(video: Video): Modifier {
    val flight = LocalPosterExpansion.current?.flight ?: return this
    if (flight.video.id != video.id || flight.video.source.key != video.source.key) return this
    val back = LocalCardReturn.current
    return onGloballyPositioned { flight.target = it.boundsInRoot() }.graphicsLayer {
        alpha = if (back != null && back.progress > 0f) 1f else ((flight.progress.value - .8f) / .2f).coerceIn(0f, 1f)
    }
}

@Composable internal fun PosterFlightOverlay(host: PosterExpansion, flight: PosterFlight,
    parentImage: () -> ImageBitmap?, modifier: Modifier) {
    val progress = flight.progress
    val back = LocalCardReturn.current
    LaunchedEffect(flight) {
        try { progress.animateTo(1f, tween(300, easing=FastOutSlowInEasing)) }
        finally { if (host.flight === flight) host.flight = null }
    }
    val tint = MaterialTheme.colorScheme.surfaceContainer
    val surface = MaterialTheme.colorScheme.background
    val path = remember { Path() }
    Canvas(modifier) {
        if (back != null && back.progress > 0f) return@Canvas
        val end = flight.target ?: return@Canvas
        val t = progress.value
        val viewport = IntSize(size.width.roundToInt(), size.height.roundToInt())
        // The controls on the two screens occupy the same space. Fade them in sequence
        // while the artwork remains opaque and follows one continuous path.
        drawRect(surface, alpha=((1f-t)/.4f).coerceIn(0f,1f))
        val parentAlpha = (1f-t/.65f).coerceAtLeast(0f)
        if (parentAlpha > 0f) parentImage()?.let { parent ->
            val hole = flight.visible
            clipRect(hole.left,hole.top,hole.right,hole.bottom,clipOp=ClipOp.Difference) {
                drawImage(parent, dstSize=viewport, alpha=parentAlpha)
            }
        }
        val frame = lerp(flight.origin, end, t)
        val visible = lerp(flight.visible, end, t)
        if (frame.isEmpty || visible.isEmpty) return@Canvas
        val radius = (16.dp.toPx() * (1f-t))
        path.reset()
        path.addRoundRect(RoundRect(frame, CornerRadius(radius)))
        val alpha = ((1f-t)/.2f).coerceIn(0f,1f)
        clipRect(visible.left,visible.top,visible.right,visible.bottom) {
            clipPath(path) {
                val image = flight.image
                if (image == null) drawRect(tint,frame.topLeft,frame.size,alpha)
                else {
                    val scale = maxOf(frame.width/image.width, frame.height/image.height)
                    val width = (image.width*scale).roundToInt().coerceAtLeast(1)
                    val height = (image.height*scale).roundToInt().coerceAtLeast(1)
                    drawImage(image, dstOffset=IntOffset((frame.center.x-width/2f).roundToInt(),
                        (frame.center.y-height/2f).roundToInt()), dstSize=IntSize(width,height), alpha=alpha)
                }
            }
        }
    }
}
