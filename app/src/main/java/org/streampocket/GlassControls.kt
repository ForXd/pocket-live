// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.getValue
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

internal val LocalDockInset = androidx.compose.runtime.compositionLocalOf { 0.dp }

@Composable internal fun PocketLoading(modifier:Modifier=Modifier, progress:(()->Float)?=null) {
    Box(modifier,contentAlignment=androidx.compose.ui.Alignment.Center) {
        val ring=Modifier.size(28.dp)
        if(progress==null) CircularProgressIndicator(modifier=ring,strokeWidth=3.dp)
        else CircularProgressIndicator(progress=progress,modifier=ring,strokeWidth=3.dp)
    }
}

@Composable internal fun GlassTextButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    shape:Shape=MaterialTheme.shapes.medium, colors:ButtonColors=ButtonDefaults.textButtonColors(),
    contentPadding:PaddingValues=ButtonDefaults.TextButtonContentPadding,content:@Composable RowScope.()->Unit) {
    TextButton(onClick=onClick,modifier=modifier.sizeIn(minWidth=48.dp,minHeight=48.dp),enabled=enabled,shape=shape,
        colors=colors,contentPadding=contentPadding,content=content)
}

/** Controls share the parent backdrop, using light translucent tint rather than opaque paint. */
@Composable internal fun glassFill(selected:Boolean=false)=MaterialTheme.colorScheme.primary.copy(alpha=if(MaterialTheme.colorScheme.surface.luminance()<.5f) { if(selected) .20f else .10f } else { if(selected) .12f else .04f })
@Composable internal fun GlassButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    shape:Shape=MaterialTheme.shapes.medium,border:BorderStroke?=null,
    colors:ButtonColors=ButtonDefaults.filledTonalButtonColors(),
    contentPadding:PaddingValues=PaddingValues(horizontal=16.dp,vertical=12.dp),content:@Composable RowScope.()->Unit) {
    FilledTonalButton(onClick,modifier.heightIn(min=48.dp),enabled,shape,colors,border=border,contentPadding=contentPadding,content=content)
}
@Composable internal fun GlassIconButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    shape:Shape=MaterialTheme.shapes.medium,
    colors:IconButtonColors=IconButtonDefaults.filledTonalIconButtonColors(),
    border:BorderStroke?=null,content:@Composable ()->Unit) {
    val target = if (border == null) modifier else modifier.border(border, shape)
    FilledTonalIconButton(onClick, target.sizeIn(minWidth=48.dp,minHeight=48.dp), enabled, shape, colors=colors, content=content)
}

/** Color supplements an explicit label, so availability is never conveyed by color alone. */
@Composable internal fun MediaProbeIndicator(status:ProbeStatus?,modifier:Modifier=Modifier,textModifier:Modifier=Modifier) {
    val ready=status==ProbeStatus.READY
    val green=if(MaterialTheme.colorScheme.surface.luminance()<.5f) Color(0xff99d6ad) else Color(0xff236b3d)
    val foreground=if(ready) green else MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.background(if(ready) green.copy(alpha=.10f) else Color.Transparent,MaterialTheme.shapes.small).padding(horizontal=8.dp,vertical=4.dp),
        verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        if(ready) Box(Modifier.size(6.dp).background(green,androidx.compose.foundation.shape.CircleShape))
        Text(status?.label ?: "未探测",textModifier,style=MaterialTheme.typography.labelMedium,color=foreground)
    }
}

/** One selected surface includes both icon and label, with a single accessible tab target. */
@Composable internal fun PocketNavigationItem(selected:Boolean,label:String,onClick:()->Unit,
    modifier:Modifier=Modifier,indicator:Boolean=true,icon:@Composable ()->Unit) {
    val tint=if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val fill = if (indicator) androidx.compose.animation.animateColorAsState(if(selected) glassFill(true) else Color.Transparent,
        animationSpec=androidx.compose.animation.core.tween(160),label="navigationSelection").value else Color.Transparent
    val shape=androidx.compose.foundation.shape.RoundedCornerShape(if(indicator) 22.dp else 24.dp)
    val surface=when {
        indicator -> fill
        else -> Color.Transparent
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides tint) {
        Column(modifier.heightIn(min=if(indicator) 64.dp else 56.dp)
            .clip(shape).background(surface)
            .selectable(selected=selected,role=androidx.compose.ui.semantics.Role.Tab,onClick=onClick,
                interactionSource=androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication=androidx.compose.foundation.LocalIndication.current)
            .padding(horizontal=6.dp,vertical=8.dp),horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.spacedBy(4.dp,androidx.compose.ui.Alignment.CenterVertically)) {
            icon()
            Text(label,style=MaterialTheme.typography.labelMedium,fontWeight=if(selected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,maxLines=1)
        }
    }
}

/** One traveling indicator. The shadow belongs to the moving surface. */
@Composable internal fun SlidingTabs(index:Int,count:Int,dock:Boolean,modifier:Modifier=Modifier,
    content:@Composable RowScope.()->Unit) {
    BoxWithConstraints(modifier) {
        val gap = if (dock) 4.dp else 0.dp
        val cell = (maxWidth-gap*(count-1))/count
        val position by androidx.compose.animation.core.animateDpAsState(
            targetValue=(cell+gap)*index,
            animationSpec=androidx.compose.animation.core.spring(dampingRatio=.88f,stiffness=420f),
            label="tabPosition")
        Box(Modifier.matchParentSize()) {
            val shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
            if (dock) Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(position.roundToPx(),0) }
                .width(cell).fillMaxHeight().shadow(3.dp,shape).background(MaterialTheme.colorScheme.surfaceContainerHigh,shape))
            else Box(Modifier.align(androidx.compose.ui.Alignment.BottomStart)
                .offset { androidx.compose.ui.unit.IntOffset((position+cell*.25f).roundToPx(),0) }
                .width(cell*.5f).height(3.dp).background(MaterialTheme.colorScheme.primary,shape))
        }
        Row(Modifier.fillMaxWidth().selectableGroup(),horizontalArrangement=Arrangement.spacedBy(gap),
            verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,content=content)
    }
}


/** Static tinted surfaces stay inexpensive over the video and during list scrolling. */
@Composable internal fun Modifier.smokeGlass(shape: Shape): Modifier {
    val dark = LocalPocketDark.current
    val top = if (dark) Color(0xE640505B) else Color(0xF7FFFFFF)
    val bottom = if (dark) Color(0xED202B32) else Color(0xE9DCE4E8)
    val tint = androidx.compose.runtime.remember(top, bottom) { Brush.linearGradient(listOf(top, bottom)) }
    return clip(shape).background(tint, shape).border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha=.34f), shape)
}

@Composable internal fun PocketBack(onClick: () -> Unit, modifier: Modifier = Modifier, label: String = "返回", overVideo: Boolean = false) {
    IconButton(onClick, modifier.size(48.dp)) {
        Box(Modifier.size(34.dp).background(if(overVideo) Color.Black.copy(alpha=.50f) else glassFill(true), CircleShape),
            contentAlignment=androidx.compose.ui.Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, label, Modifier.size(20.dp),
                tint=if(overVideo) Color.White else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable internal fun PocketClose(onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(48.dp)) { Icon(Icons.Default.Close,"关闭", Modifier.size(20.dp)) }
}

@Composable internal fun SheetHeading(title:String, close:()->Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style=MaterialTheme.typography.titleLarge)
        PocketClose(close)
    }
}

@Composable internal fun LoadingStatus(text:String,modifier:Modifier=Modifier) {
    Row(modifier, verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(12.dp,androidx.compose.ui.Alignment.CenterHorizontally)) {
        PocketLoading()
        Text(text,style=MaterialTheme.typography.bodyMedium)
    }
}
