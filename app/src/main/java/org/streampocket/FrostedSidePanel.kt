// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import kotlinx.coroutines.*
import androidx.compose.ui.graphics.luminance
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

/** Shared native blur with a bounded one-shot fallback; no capture loop or extra decoder. */
@Composable internal fun FrostedDialog(onDismissRequest:()->Unit,placement:GlassPlacement=GlassPlacement.CENTER,
    immersive:Boolean=false,tag:String="glassDialog",content:@Composable ()->Unit) {
    val context=LocalContext.current
    val activity=androidx.activity.compose.LocalActivity.current
    val scope=rememberCoroutineScope()
    val composition=rememberCompositionContext()
    val config=LocalConfiguration.current
    val width=when(placement) { GlassPlacement.SIDE->(config.screenWidthDp*.5f).dp;GlassPlacement.BOTTOM->minOf(config.screenWidthDp,640).dp;else->minOf(config.screenWidthDp-32,560).dp }
    val density=LocalDensity.current
    val widthPx=with(density) { width.roundToPx() }
    val radius=with(density) { 20.dp.toPx() }
    val blurRadius=with(density) { 32.dp.roundToPx() }.coerceAtMost(120)
    val tint=MaterialTheme.colorScheme.surface
    val dark=tint.luminance()<.5f
    val stroke=MaterialTheme.colorScheme.onSurface.copy(alpha=if(dark) .26f else .16f).toArgb()
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
        val backdrop=activity?.window?.let { GlassBackdropDrawable(it,window,radius) }
        window.setBackgroundDrawable(if(backdrop!=null) LayerDrawable(arrayOf(backdrop,background)) else background)
        var captureJob:Job?=null
        var nativeBlur=false
        window.setGravity(when(placement) { GlassPlacement.SIDE->Gravity.RIGHT or Gravity.CENTER_VERTICAL;GlassPlacement.BOTTOM->Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL;else->Gravity.CENTER })
        if(placement==GlassPlacement.SIDE) window.setWindowAnimations(R.style.PocketSidePanelAnimation)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        WindowCompat.setDecorFitsSystemWindows(window,!immersive)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        fun material() {
            val translucent=nativeBlur || backdrop?.bitmap!=null
            val top=if(dark) Color(0xff55544f) else Color(0xfffffdf7)
            val bottom=if(dark) Color(0xff252623) else Color(0xffe8e5dc)
            background.orientation=GradientDrawable.Orientation.TL_BR
            background.colors=intArrayOf(top.copy(alpha=if(translucent) .70f else .96f).toArgb(),bottom.copy(alpha=if(translucent) .82f else .98f).toArgb())
        }
        fun update(enabled:Boolean) {
            nativeBlur=enabled
            window.setDimAmount(.16f)
            if(Build.VERSION.SDK_INT>=31) {
                window.setBackgroundBlurRadius(if(enabled) blurRadius else 0)
                if(enabled && !immersive) window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND) else window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes=window.attributes.apply { setBlurBehindRadius(if(enabled && !immersive) (blurRadius/2) else 0) }
            }
            captureJob?.cancel()
            if(enabled) backdrop?.bitmap=null
            else if(backdrop!=null && backdrop.bitmap==null) captureJob=scope.launch(Dispatchers.Main.immediate) {
                val image=withTimeoutOrNull(1_500) { captureGlassBackdrop(activity!!.window) }
                ensureActive()
                if(!nativeBlur) { backdrop.bitmap=image;material() }
            }
            material()
        }
        val manager=context.getSystemService(WindowManager::class.java)
        val listener=Consumer<Boolean> { update(it) }
        material();window.setDimAmount(.16f)
        dialog.show()
        window.setLayout(widthPx,if(placement==GlassPlacement.SIDE) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT)
        if(immersive) WindowCompat.getInsetsController(window,view).apply {
            systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        if(Build.VERSION.SDK_INT>=31) {
            update(manager.isCrossWindowBlurEnabled)
            manager.addCrossWindowBlurEnabledListener(listener)
        } else update(false)
        onDispose {
            if(Build.VERSION.SDK_INT>=31) {
                manager.removeCrossWindowBlurEnabledListener(listener)
                window.setBackgroundBlurRadius(0)
            }
            captureJob?.cancel()
            backdrop?.bitmap=null
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
