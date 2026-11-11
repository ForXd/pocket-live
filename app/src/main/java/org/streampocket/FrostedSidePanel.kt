// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import android.graphics.drawable.GradientDrawable
import android.os.Build
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

internal enum class GlassPlacement { CENTER, SIDE, BOTTOM }

/** One native backdrop material for every overlay. No frame capture or extra decoder. */
@Composable internal fun FrostedDialog(onDismissRequest:()->Unit,placement:GlassPlacement=GlassPlacement.CENTER,
    immersive:Boolean=false,tag:String="glassDialog",content:@Composable ()->Unit) {
    val context=LocalContext.current
    val composition=rememberCompositionContext()
    val config=LocalConfiguration.current
    val width=when(placement) { GlassPlacement.SIDE->(config.screenWidthDp*.5f).dp;GlassPlacement.BOTTOM->minOf(config.screenWidthDp,640).dp;else->minOf(config.screenWidthDp-32,560).dp }
    val density=LocalDensity.current
    val widthPx=with(density) { width.roundToPx() }
    val radius=with(density) { 20.dp.toPx() }
    val blurRadius=with(density) { 24.dp.roundToPx() }.coerceAtMost(80)
    val tint=MaterialTheme.colorScheme.surface
    val stroke=MaterialTheme.colorScheme.onSurface.copy(alpha=.15f).toArgb()
    val dismiss by rememberUpdatedState(onDismissRequest)
    val body by rememberUpdatedState(content)
    val dialog=remember(context,composition) { ComponentDialog(context,R.style.PocketFrostedDialogTheme) }
    DisposableEffect(dialog,widthPx,blurRadius,radius,tint,stroke,placement,immersive) {
        val window=checkNotNull(dialog.window)
        val view=ComposeView(dialog.context).apply {
            setParentCompositionContext(composition)
            setContent {
                Surface(Modifier.width(width).then(if(placement==GlassPlacement.SIDE) Modifier.fillMaxHeight() else Modifier.heightIn(max=(config.screenHeightDp*.88f).dp)).testTag(tag),color=Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface) {
                    Box(Modifier.windowInsetsPadding(if(immersive) WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal) else WindowInsets(0,0,0,0))) { body() }
                }
            }
        }
        dialog.setContentView(view)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnCancelListener { dismiss() }
        val background=GradientDrawable().apply { cornerRadius=radius;setStroke(with(density) { 1.dp.roundToPx() },stroke) }
        window.setBackgroundDrawable(background)
        window.setGravity(when(placement) { GlassPlacement.SIDE->Gravity.RIGHT or Gravity.CENTER_VERTICAL;GlassPlacement.BOTTOM->Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL;else->Gravity.CENTER })
        if(placement==GlassPlacement.SIDE) window.setWindowAnimations(R.style.PocketSidePanelAnimation)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        WindowCompat.setDecorFitsSystemWindows(window,!immersive)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        fun update(enabled:Boolean) {
            background.setColor(tint.copy(alpha=if(enabled) .78f else .96f).toArgb())
            window.setDimAmount(.22f)
            if(Build.VERSION.SDK_INT>=31) window.setBackgroundBlurRadius(if(enabled) blurRadius else 0)
        }
        val manager=context.getSystemService(WindowManager::class.java)
        val listener=Consumer<Boolean> { update(it) }
        update(false)
        dialog.show()
        window.setLayout(widthPx,if(placement==GlassPlacement.SIDE) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT)
        if(immersive) WindowCompat.getInsetsController(window,view).apply {
            systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        if(Build.VERSION.SDK_INT>=31) {
            update(manager.isCrossWindowBlurEnabled)
            manager.addCrossWindowBlurEnabledListener(listener)
        }
        onDispose {
            if(Build.VERSION.SDK_INT>=31) {
                manager.removeCrossWindowBlurEnabledListener(listener)
                window.setBackgroundBlurRadius(0)
            }
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
    confirmButton:@Composable ()->Unit,dismissButton:(@Composable ()->Unit)?=null) {
    FrostedDialog(onDismissRequest) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            ProvideTextStyle(MaterialTheme.typography.titleLarge,content=title)
            Box(Modifier.weight(1f,false)) { ProvideTextStyle(MaterialTheme.typography.bodyMedium,content=text) }
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) { dismissButton?.invoke();confirmButton() }
        }
    }
}

@Composable internal fun GlassMenu(expanded:Boolean,onDismissRequest:()->Unit,content:@Composable ColumnScope.()->Unit) {
    if(expanded) FrostedDialog(onDismissRequest) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp),content=content)
    }
}
