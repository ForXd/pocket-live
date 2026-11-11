// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.semantics.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

/** The window owns no vertical drag detector; only the handle can move the sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun HandleOnlySheet(onDismissRequest:()->Unit,content:@Composable ColumnScope.()->Unit) {
    val threshold=with(LocalDensity.current) { 48.dp.toPx() }
    val dismiss by rememberUpdatedState(onDismissRequest)
    var drag by remember { mutableFloatStateOf(0f) }
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared=true }
    val entrance by animateFloatAsState(if(appeared) 0f else 1f,tween(220),label="pickerEntrance")
    Dialog(onDismissRequest=onDismissRequest,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val heightPx=with(LocalDensity.current) { maxHeight.toPx() }
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha=0.32f)).clickable(interactionSource=remember { MutableInteractionSource() },indication=null,onClick=onDismissRequest))
            Surface(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).widthIn(max=640.dp).fillMaxWidth().heightIn(max=maxHeight*0.9f).offset { IntOffset(0,(drag+entrance*heightPx).roundToInt()) },shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.navigationBarsPadding()) {
                    Box(Modifier.fillMaxWidth().height(48.dp).testTag("pickerDragHandle").semantics { contentDescription="向下拖动关闭筛选";customActions=listOf(CustomAccessibilityAction("关闭") { dismiss();true }) }.pointerInput(threshold) {
                        detectVerticalDragGestures(onDragStart={drag=0f},onDragCancel={drag=0f},onDragEnd={if(drag>=threshold) dismiss();drag=0f},onVerticalDrag={change,amount->change.consume();drag=(drag+amount).coerceAtLeast(0f)})
                    },contentAlignment=androidx.compose.ui.Alignment.Center) { BottomSheetDefaults.DragHandle() }
                    content()
                }
            }
        }
    }
}
