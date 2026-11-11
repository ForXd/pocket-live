// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.function.Consumer

/** System-composited backdrop blur; no video readback, bitmap loop or second decoder. */
@Composable internal fun FrostedSidePanel(onDismiss:()->Unit,content:@Composable ColumnScope.()->Unit) {
    val context=LocalContext.current
    val composition=rememberCompositionContext()
    val width=(LocalConfiguration.current.screenWidthDp*.5f).dp
    val density=LocalDensity.current
    val widthPx=with(density) { width.roundToPx() }
    val radius=with(density) { 16.dp.toPx() }
    val blurRadius=with(density) { 24.dp.roundToPx() }.coerceAtMost(80)
    val dismiss by rememberUpdatedState(onDismiss)
    val body by rememberUpdatedState(content)
    // Compose's stock Dialog supplies its own opaque window theme. The translucent theme
    // must be applied before PhoneWindow creates its decor, not after showing the dialog.
    val dialog=remember(context,composition) { ComponentDialog(context,R.style.PocketFrostedDialogTheme) }
    DisposableEffect(dialog,widthPx,blurRadius,radius) {
        val window=checkNotNull(dialog.window)
        val view=ComposeView(dialog.context).apply {
            setParentCompositionContext(composition)
            setContent {
                Surface(Modifier.width(width).fillMaxHeight().testTag("fullscreenEpisodePanel"),color=Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface) {
                    Column(Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End)).padding(horizontal=20.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=body)
                }
            }
        }
        dialog.setContentView(view)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnCancelListener { dismiss() }
        val background=GradientDrawable().apply { cornerRadius=radius }
        window.setBackgroundDrawable(background)
        window.setGravity(Gravity.RIGHT or Gravity.CENTER_VERTICAL)
        window.setWindowAnimations(R.style.PocketSidePanelAnimation)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        WindowCompat.setDecorFitsSystemWindows(window,false)
        fun update(enabled:Boolean) {
            background.setColor(android.graphics.Color.argb(if(enabled) 224 else 248,24,23,21))
            window.setDimAmount(.18f)
            if(Build.VERSION.SDK_INT>=31) window.setBackgroundBlurRadius(if(enabled) blurRadius else 0)
        }
        val manager=context.getSystemService(WindowManager::class.java)
        val listener=Consumer<Boolean> { update(it) }
        update(false)
        dialog.show()
        window.setLayout(widthPx,ViewGroup.LayoutParams.MATCH_PARENT)
        WindowCompat.getInsetsController(window,view).apply {
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
