// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Velocity
import kotlin.math.roundToInt

@Stable internal class HomeSearchScroll {
    var hidden by mutableFloatStateOf(0f)
    var height by mutableFloatStateOf(0f)
    fun reveal() { hidden=0f }
    fun scroll(delta:Float):Float {
        val before=hidden
        hidden=(hidden-delta).coerceIn(0f,height)
        return before-hidden
    }
    suspend fun settle(velocity:Float) {
        if(hidden<=0f || hidden>=height) return
        val target=if(velocity< -50 || (velocity<=50 && hidden>height/2)) height else 0f
        Animatable(hidden).animateTo(target,tween(160)) { hidden=value }
    }
}

@Composable internal fun rememberSearchScrollConnection(state:HomeSearchScroll,enabled:Boolean):NestedScrollConnection {
    val canScroll by rememberUpdatedState(enabled)
    return remember(state) { object:NestedScrollConnection {
        override fun onPreScroll(available:Offset,source:NestedScrollSource):Offset =
            if(canScroll && source==NestedScrollSource.UserInput) Offset(0f,state.scroll(available.y)) else Offset.Zero
        override suspend fun onPostFling(consumed:Velocity,available:Velocity):Velocity {
            if(canScroll) state.settle(consumed.y+available.y)
            return Velocity.Zero
        }
    } }
}

/** Collapse actual layout space, not just opacity, while keeping status-bar insets outside. */
@Composable internal fun CollapsingHomeSearch(state:HomeSearchScroll,content:@Composable ()->Unit) {
    Box(Modifier.fillMaxWidth().statusBarsPadding()) {
        Layout(content=content,modifier=Modifier.fillMaxWidth().clipToBounds()) { measurables,constraints ->
            val child=measurables.single().measure(constraints.copy(minHeight=0))
            state.height=child.height.toFloat()
            val hidden=state.hidden.coerceIn(0f,state.height).roundToInt()
            layout(child.width,child.height-hidden) { child.placeRelative(0,-hidden) }
        }
    }
}
