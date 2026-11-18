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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp

private class RouteLayers {
    var lastRoute: String? = null
    var depth = 0
    var retired = false
    var front = 0
}

/** Keep a display list of the outgoing card. Only the current route owns UI, effects and input. */
@Composable internal fun CardRouteHost(route: String, depth: Int, content: @Composable () -> Unit) {
    val first = rememberGraphicsLayer()
    val second = rememberGraphicsLayer()
    val layers = remember { RouteLayers() }
    val previousDepth = remember(route) { layers.depth }
    val hasPrevious = remember(route) { layers.lastRoute != null }
    val front = remember(route) { 1 - layers.front }
    SideEffect { if(layers.lastRoute != route) layers.retired = hasPrevious; layers.lastRoute = route; layers.depth = depth; layers.front = front }
    val incoming = if (front == 0) first else second
    val outgoing = if (front == 0) second else first
    val back = depth < previousDepth
    val progress = remember(route) { Animatable(if (hasPrevious) 0f else 1f) }
    LaunchedEffect(progress) {
        if (hasPrevious) progress.animateTo(1f, tween(if (back) 170 else 210, easing = FastOutSlowInEasing))
    }
    val surface = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().clipToBounds().drawWithContent {
        incoming.record { drawRect(surface); this@drawWithContent.drawContent() }
        val t = progress.value
        fun paint(layer: GraphicsLayer, x: Float, zoom: Float, dim: Float) {
            withTransform({ translate(size.width * x, 0f); scale(zoom, zoom, center) }) {
                drawLayer(layer)
                if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
            }
        }
        if (t >= 1f || !hasPrevious) {
            if (layers.retired) { outgoing.record { }; layers.retired = false }
            drawLayer(incoming)
        } else if (back) {
            paint(incoming, -.055f * (1-t), .975f + .025f*t, .22f * (1-t))
            paint(outgoing, .98f*t, 1f, 0f)
        } else {
            paint(outgoing, -.055f*t, 1f - .025f*t, .22f*t)
            paint(incoming, .68f * (1-t), 1f, 0f)
        }
    }) { content() }
}

/** Keep Material ripple and click semantics; transform only the rendered surface. */
@Composable fun Modifier.pressFeedback(interactions:MutableInteractionSource):Modifier {
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if(pressed) .98f else 1f,
        spring(dampingRatio=Spring.DampingRatioNoBouncy,stiffness=Spring.StiffnessMedium),label="cardPress")
    return graphicsLayer { scaleX=scale;scaleY=scale }
}
