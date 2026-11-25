// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.animation.core.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

@Composable
internal fun rememberLightSweep(): State<Float> =
    rememberInfiniteTransition(label = "加载扫光").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1450, easing = LinearEasing)),
        label = "光带位置"
    )

internal fun Modifier.lightSweep(phase: State<Float>) = drawWithContent {
    drawContent()
    val band = size.width * .55f
    val x = (size.width + band * 2) * phase.value - band
    drawRect(Brush.linearGradient(
        listOf(Color.Transparent, Color.White.copy(alpha = .26f), Color.Transparent),
        start = Offset(x - band, size.height * .5f),
        end = Offset(x + band, size.height * .5f + band * .45f)
    ))
}
