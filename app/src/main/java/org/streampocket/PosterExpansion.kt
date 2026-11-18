// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import kotlin.math.roundToInt

/** Keep only the selected artwork and its last measured bounds. Rebind it when the list is laid out again. */
internal class PosterSelection(val sourceKey: String, val destinationKey: String) {
    var origin by mutableStateOf(Rect.Zero)
    var visible by mutableStateOf(Rect.Zero)
    var image by mutableStateOf<ImageBitmap?>(null)
    var opened = false
    var thumbnailAlpha by mutableStateOf<(() -> Float)?>(null)
}
internal class PosterExpansion {
    var selected by mutableStateOf<PosterSelection?>(null)
    companion object {
        val Saver = listSaver<PosterExpansion,String>(
            save = { it.selected?.let { s -> listOf(s.sourceKey,s.destinationKey) }.orEmpty() },
            restore = { keys -> PosterExpansion().apply {
                if (keys.size == 2) selected = PosterSelection(keys[0],keys[1]).also { it.opened=true }
            } })
    }
}
internal val LocalPosterExpansion = staticCompositionLocalOf<PosterExpansion?> { null }
internal val LocalPosterClose = staticCompositionLocalOf<(() -> Unit)?> { null }

internal class PosterAnchor(private val host: PosterExpansion?, private val video: Video,
    private val model: AppModel, private val large: Boolean) {
    private val key = hash(video.probeKey())
    private var coordinates: LayoutCoordinates? = null
    private fun measure(selection: PosterSelection, layout: LayoutCoordinates) {
        selection.origin = Rect(layout.localToRoot(Offset.Zero),
            layout.localToRoot(Offset(layout.size.width.toFloat(),layout.size.height.toFloat())))
        selection.visible = layout.boundsInRoot()
        if (selection.image == null) selection.image = (model.posters.cached(video.poster,large)
            ?: model.posters.cached(video.poster))?.asImageBitmap()
    }
    val modifier = Modifier.onGloballyPositioned { layout ->
        coordinates=layout
        host?.selected?.takeIf { it.sourceKey == key }?.let { measure(it,layout) }
    }.graphicsLayer {
        val selected=host?.selected
        alpha = if (selected?.sourceKey==key) selected.thumbnailAlpha?.invoke() ?: 0f else 1f
    }

    fun open(destination: Video, load: () -> Unit) {
        host?.selected = PosterSelection(key,hash(destination.probeKey())).also { selection ->
            coordinates?.takeIf { it.isAttached }?.let { measure(selection,it) }
        }
        load()
    }
}

@Composable internal fun rememberPosterAnchor(video: Video, model: AppModel, large: Boolean = false): PosterAnchor {
    val host = LocalPosterExpansion.current
    return remember(host, video, model, large) { PosterAnchor(host, video, model, large) }
}

private val LocalCoverHeight = staticCompositionLocalOf<((Int) -> Unit)?> { null }
@Composable internal fun Modifier.posterDestination(): Modifier {
    val measure = LocalCoverHeight.current ?: return this
    return onSizeChanged { measure(it.height) }
}

/** A modal card in the existing composition. Only this card and its scrim respond to Back. */
@Composable internal fun ExpandedPosterCard(selection: PosterSelection, onClose: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val enter = remember(selection) { Animatable(if(selection.opened) 1f else 0f) }
    val back = remember(selection,scope) { CardReturn(scope) { true } }
    val close by rememberUpdatedState(onClose)
    var panel by remember { mutableStateOf(Rect.Zero) }
    var coverHeight by remember { mutableIntStateOf(0) }
    val measureCover = remember { { h:Int -> coverHeight=h } }
    val dismiss = remember(back) { { back.finish { close() } } }
    val t = remember(enter,back) { { enter.value * (1f-back.progress) } }
    DisposableEffect(selection,back) {
        selection.thumbnailAlpha={ ((t()-.85f)/.15f).coerceIn(0f,1f) }
        onDispose { selection.thumbnailAlpha=null }
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { back.windowHidden() }
    LaunchedEffect(selection) {
        enter.animateTo(1f,tween(280))
        selection.opened=true
    }
    CompositionLocalProvider(LocalCardReturn provides back, LocalPosterClose provides dismiss, LocalCoverHeight provides measureCover) {
        CardBackHandler(onBack={ close() })
        Box(Modifier.fillMaxSize().onSizeChanged { back.width=it.width }.testTag("expandedPoster"),contentAlignment=Alignment.Center) {
            Box(Modifier.matchParentSize().drawBehind { drawRect(Color.Black,alpha=.45f*t()) }
                .clickable(remember { MutableInteractionSource() },indication=null,onClick=dismiss).clearAndSetSemantics {})
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=20.dp,vertical=12.dp),
                contentAlignment=Alignment.Center) {
                val height = (maxHeight * .82f).coerceAtMost(620.dp)
                Box(Modifier.widthIn(max=520.dp).fillMaxWidth().height(height).onGloballyPositioned { panel=it.boundsInRoot() }) {
                    Surface(Modifier.fillMaxSize().graphicsLayer {
                        val progress=t()
                        val start=selection.origin
                        if (!panel.isEmpty && !start.isEmpty) {
                            val scale=start.width/panel.width
                            scaleX=scale+(1f-scale)*progress
                            scaleY=scaleX
                            transformOrigin=TransformOrigin(0f,0f)
                            translationX=(start.left-panel.left)*(1f-progress)
                            translationY=(start.top-panel.top)*(1f-progress)
                        }
                        alpha=((progress-.48f)/.52f).coerceIn(0f,1f)
                    }
                        .semantics { paneTitle="作品详情" },
                        shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface,
                        shadowElevation=8.dp) { content() }
                }
            }
            val tint = MaterialTheme.colorScheme.surfaceContainer
            val path=remember { Path() }
            Canvas(Modifier.matchParentSize()) {
                val p=t()
                val start=selection.origin
                if (p>=1f || start.isEmpty || panel.isEmpty || coverHeight==0) return@Canvas
                val end=Rect(panel.left,panel.top,panel.right,panel.top+coverHeight)
                val frame=lerp(start,end,p)
                val clip=lerp(selection.visible,end,p)
                if(clip.isEmpty || frame.isEmpty) return@Canvas
                path.reset()
                path.addRoundRect(RoundRect(frame,CornerRadius((16.dp+4.dp*p).toPx())))
                val alpha=((1f-p)/.13f).coerceIn(0f,1f)
                clipRect(clip.left,clip.top,clip.right,clip.bottom) {
                    clipPath(path) {
                        val image=selection.image
                        if(image==null) drawRect(tint,frame.topLeft,frame.size,alpha)
                        else {
                            val scale=maxOf(frame.width/image.width,frame.height/image.height)
                            val width=(image.width*scale).roundToInt().coerceAtLeast(1)
                            val height=(image.height*scale).roundToInt().coerceAtLeast(1)
                            drawImage(image,dstOffset=IntOffset((frame.center.x-width/2f).roundToInt(),
                                (frame.center.y-height/2f).roundToInt()),dstSize=IntSize(width,height),alpha=alpha)
                        }
                    }
                }
            }
        }
    }
}
