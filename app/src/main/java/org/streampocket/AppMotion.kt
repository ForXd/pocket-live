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
@Composable fun Modifier.destinationArrival(destination:Any):Modifier {
    val progress=remember(destination) { Animatable(0f) }
    val distance=with(LocalDensity.current) { 12.dp.toPx() }
    LaunchedEffect(progress) { progress.animateTo(1f,tween(220,easing=FastOutSlowInEasing)) }
    return graphicsLayer { alpha=progress.value;translationY=distance*(1f-progress.value) }
}

/** Keep Material ripple and click semantics; transform only the rendered surface. */
@Composable fun Modifier.pressFeedback(interactions:MutableInteractionSource):Modifier {
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if(pressed) .98f else 1f,
        spring(dampingRatio=Spring.DampingRatioNoBouncy,stiffness=Spring.StiffnessMedium),label="cardPress")
    return graphicsLayer { scaleX=scale;scaleY=scale }
}
