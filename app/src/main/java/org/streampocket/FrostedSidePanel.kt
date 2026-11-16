// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.core.graphics.drawable.toDrawable

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import kotlinx.coroutines.*
import androidx.compose.ui.graphics.luminance
import android.os.Build
import android.view.MotionEvent
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.background
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.function.Consumer

internal enum class GlassPlacement { SIDE, BOTTOM }

/** Shared native blur with a bounded one-shot fallback; no capture loop or extra decoder. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable internal fun FrostedDialog(onDismissRequest:()->Unit,placement:GlassPlacement=GlassPlacement.BOTTOM,
    immersive:Boolean=false,tag:String="glassDialog",dismissOnOutside:Boolean=true,content:@Composable ()->Unit) {
    val context=LocalContext.current
    val allowOutside by rememberUpdatedState(dismissOnOutside)
    val composition=rememberCompositionContext()
    val windowSize = windowSizeDp()
    val width=when(placement) { GlassPlacement.SIDE->(windowSize.width.value*.5f).dp;GlassPlacement.BOTTOM->minOf(windowSize.width,640.dp) }
    val density=LocalDensity.current
    val widthPx=with(density) { width.roundToPx() }
    val radius=with(density) { 20.dp.toPx() }
    val tint=MaterialTheme.colorScheme.surface
    val dark=tint.luminance()<.5f
    val stroke=MaterialTheme.colorScheme.onSurface.copy(alpha=if(dark) .26f else .16f).toArgb()
    val dismiss by rememberUpdatedState(onDismissRequest)
    val body by rememberUpdatedState(content)
    val dialog=remember(context,composition) { ComponentDialog(context,R.style.PocketFrostedDialogTheme) }
    DisposableEffect(dialog,widthPx,radius,tint,stroke,placement,immersive) {
        val window=checkNotNull(dialog.window)
        val view=ComposeView(dialog.context).apply {
            val panel=this
            setParentCompositionContext(composition)
            setContent {
                val scope = rememberCoroutineScope()
                Surface(Modifier.width(width).then(if(placement==GlassPlacement.SIDE) Modifier.fillMaxHeight() else Modifier.heightIn(max=(windowSize.height.value*.88f).dp)).testTag(tag),color=Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface) {
                    Column {
                        if(placement!=GlassPlacement.SIDE) {
                            var settle by remember { mutableStateOf<Job?>(null) }
                            var dragOrigin by remember { mutableFloatStateOf(0f) }
                            val velocity = remember { androidx.compose.ui.input.pointer.util.VelocityTracker() }
                            val flingSpeed = with(density) { 900.dp.toPx() }
                            val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                            val threshold=with(density) { 48.dp.toPx() }
                            Box(Modifier.fillMaxWidth().height(48.dp).testTag("pickerDragHandle")
                                .semantics { contentDescription="向下拖动关闭面板";customActions=listOf(CustomAccessibilityAction("关闭") { dismiss();true }) }
                                .pointerInteropFilter { event ->
                                    if (event.actionMasked == MotionEvent.ACTION_DOWN) velocity.resetTracking()
                                    velocity.addPosition(event.eventTime, androidx.compose.ui.geometry.Offset(0f, event.rawY))
                                    when(event.actionMasked) {
                                        MotionEvent.ACTION_DOWN -> {
                                            settle?.cancel()
                                            dragOrigin=event.rawY-panel.translationY
                                        }
                                        MotionEvent.ACTION_MOVE -> panel.translationY=(event.rawY-dragOrigin).coerceIn(0f,panel.height.toFloat())
                                        MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                                            val start=panel.translationY
                                            val speed = velocity.calculateVelocity().y
                                            val close = event.actionMasked == MotionEvent.ACTION_UP && speed > -flingSpeed &&
                                                (start >= threshold || (start > touchSlop && speed >= flingSpeed))
                                            val end=if(close) panel.height.toFloat() else 0f
                                            settle=scope.launch {
                                                androidx.compose.animation.core.animate(start,end,animationSpec=androidx.compose.animation.core.tween(
                                                    if(close) 160 else 210,easing=androidx.compose.animation.core.FastOutSlowInEasing)) { value,_->panel.translationY=value }
                                                if(close) {
                                                    window.setWindowAnimations(0)
                                                    dismiss()
                                                }
                                            }
                                        }
                                    }
                                    true
                                },contentAlignment=androidx.compose.ui.Alignment.Center) {
                                Spacer(Modifier.size(36.dp,4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.4f),androidx.compose.foundation.shape.CircleShape))
                            }
                            DisposableEffect(Unit) { onDispose { settle?.cancel() } }
                        }
                    Box(Modifier.windowInsetsPadding(if(immersive) WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal) else WindowInsets(0,0,0,0))) { body() }
                    }
                }
            }
        }
        val host=android.widget.FrameLayout(dialog.context)
        host.addView(view,android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,if(placement==GlassPlacement.SIDE) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setContentView(host)
        dialog.setCanceledOnTouchOutside(allowOutside)
        dialog.setOnCancelListener { dismiss() }
        val background=GradientDrawable().apply { cornerRadius=radius;setStroke(with(density) { 1.dp.roundToPx() },stroke) }
        background.setColor(tint.toArgb())
        if(placement==GlassPlacement.BOTTOM) {
            window.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
            view.background=background
        } else window.setBackgroundDrawable(background)
        window.setGravity(when(placement) { GlassPlacement.SIDE->Gravity.END or Gravity.CENTER_VERTICAL;GlassPlacement.BOTTOM->Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL })
        window.setWindowAnimations(if(placement==GlassPlacement.SIDE) R.style.PocketSidePanelAnimation else R.style.PocketDrawerAnimation)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        WindowCompat.setDecorFitsSystemWindows(window,!immersive)
        window.setDimAmount(.16f)
        dialog.show()
        WindowCompat.getInsetsController(window,view).apply {
            isAppearanceLightStatusBars=!dark
            isAppearanceLightNavigationBars=!dark
        }
        window.setLayout(widthPx,if(placement==GlassPlacement.SIDE) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT)
        if(immersive) WindowCompat.getInsetsController(window,view).apply {
            systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            dialog.setOnCancelListener(null)
            dialog.dismiss()
            view.disposeComposition()
        }
    }
}

@Composable internal fun FrostedSidePanel(onDismiss:()->Unit,content:@Composable ColumnScope.()->Unit) {
    FrostedDialog(onDismiss,GlassPlacement.SIDE,immersive=true,tag="fullscreenEpisodePanel") {
        Column(Modifier.fillMaxSize().padding(horizontal=20.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}

@Composable internal fun GlassAlertDialog(onDismissRequest:()->Unit,title:@Composable ()->Unit,text:@Composable ()->Unit,
    confirmButton:@Composable ()->Unit,dismissButton:(@Composable ()->Unit)?=null,dismissOnOutside:Boolean=true) {
    FrostedDialog(onDismissRequest,dismissOnOutside=dismissOnOutside) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            ProvideTextStyle(MaterialTheme.typography.titleLarge,content=title)
            Box(Modifier.weight(1f,false)) { ProvideTextStyle(MaterialTheme.typography.bodyMedium,content=text) }
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp,androidx.compose.ui.Alignment.End)) { dismissButton?.invoke();confirmButton() }
        }
    }
}

@Composable internal fun GlassMenu(expanded:Boolean,onDismissRequest:()->Unit,content:@Composable ColumnScope.()->Unit) {
    if(expanded) FrostedDialog(onDismissRequest) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp),content=content)
    }
}
