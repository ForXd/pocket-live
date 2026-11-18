// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** One live destination: transitions never duplicate requests, playback or saveable state.
 * Compose animations inherit Android's animator duration scale, including scale zero.
 */
@Composable fun Modifier.destinationArrival(destination: String, depth: Int):Modifier {
    val lastDepth = remember { intArrayOf(depth) }
    val reverse = remember(destination) { depth < lastDepth[0] }
    SideEffect { lastDepth[0] = depth }
    val progress = remember(destination) { Animatable(0f) }
    LaunchedEffect(progress) {
        progress.animateTo(1f, tween(if(reverse) 170 else 210, easing=LinearOutSlowInEasing))
    }
    return graphicsLayer {
        val remaining = 1f - progress.value
        translationX = size.width * (if(reverse) -.05f else .16f) * remaining
        alpha = 1f - .18f * remaining
        scaleX = 1f - .022f * remaining
        scaleY = scaleX
    }
}

/** Keep Material ripple and click semantics; transform only the rendered surface. */
@Composable fun Modifier.pressFeedback(interactions:MutableInteractionSource):Modifier {
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if(pressed) .98f else 1f,
        spring(dampingRatio=Spring.DampingRatioNoBouncy,stiffness=Spring.StiffnessMedium),label="cardPress")
    return graphicsLayer { scaleX=scale;scaleY=scale }
}
