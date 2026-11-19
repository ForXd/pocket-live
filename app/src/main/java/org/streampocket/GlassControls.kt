// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
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

internal val PageGutter = 20.dp
internal val ActionGap = 8.dp
internal val IconTarget = 48.dp
internal val IconFace = 36.dp
internal val IconFaceInset = (IconTarget - IconFace) / 2

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
    colors:ButtonColors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.surfaceContainerHigh,contentColor=MaterialTheme.colorScheme.onSurface),
    contentPadding:PaddingValues=PaddingValues(horizontal=16.dp,vertical=12.dp),content:@Composable RowScope.()->Unit) {
    FilledTonalButton(onClick,modifier.heightIn(min=48.dp),enabled,shape,colors,border=border,contentPadding=contentPadding,content=content)
}
@Composable internal fun GlassIconButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    shape:Shape=CircleShape,
    colors:IconButtonColors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow,contentColor=MaterialTheme.colorScheme.onSurface),
    border:BorderStroke?=null,content:@Composable ()->Unit) {
    val target = if (border == null) modifier else modifier.border(border, shape)
    val fill = if(enabled) colors.containerColor else colors.disabledContainerColor
    val ink = if(enabled) colors.contentColor else colors.disabledContentColor
    IconButton(onClick=onClick,modifier=target.size(IconTarget),enabled=enabled) {
        Box(Modifier.size(IconFace).background(fill,shape),contentAlignment=androidx.compose.ui.Alignment.Center) {
            androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides ink) {
                Box(Modifier.size(20.dp),propagateMinConstraints=true) { content() }
            }
        }
    }
}

/** The selector and neighbouring icon have the same visual height and a full touch target. */
@Composable internal fun FilterSelector(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick=onClick,modifier=modifier.sizeIn(minHeight=IconTarget,minWidth=IconTarget),
        shape=MaterialTheme.shapes.medium,contentPadding=PaddingValues(0.dp)) {
        Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainer,MaterialTheme.shapes.medium)
            .heightIn(min=IconFace).padding(horizontal=12.dp,vertical=4.dp),
            horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Text(text,Modifier.weight(1f,fill=false),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurface)
            Icon(Icons.Default.ArrowDropDown,null,Modifier.size(16.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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

/** Compact phone destinations use a single named, selectable touch target. */
@Composable internal fun DockDestination(selected:Boolean,label:String,onClick:()->Unit,modifier:Modifier=Modifier,icon:@Composable ()->Unit) {
    val interactions=androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    Box(modifier.heightIn(min=48.dp).clip(androidx.compose.foundation.shape.CircleShape)
        .selectable(selected=selected,role=androidx.compose.ui.semantics.Role.Tab,onClick=onClick,interactionSource=interactions,indication=null)
        .semantics { contentDescription=label },contentAlignment=androidx.compose.ui.Alignment.Center) {
        val tint=if(selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        Box(Modifier.size(36.dp).background(if(pressed) tint.copy(alpha=.14f) else if(selected) tint.copy(alpha=.08f) else Color.Transparent,
            androidx.compose.foundation.shape.CircleShape),contentAlignment=androidx.compose.ui.Alignment.Center) {
            androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides tint,content=icon)
        }
    }
}

/** One selected surface includes both icon and label, with a single accessible tab target. */
@Composable internal fun PocketNavigationItem(selected:Boolean,label:String,onClick:()->Unit,
    modifier:Modifier=Modifier,icon:@Composable ()->Unit) {
    val tint=if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val fill = androidx.compose.animation.animateColorAsState(if(selected) glassFill(true) else Color.Transparent,
        animationSpec=androidx.compose.animation.core.tween(160),label="navigationSelection").value
    val shape=androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
    androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides tint) {
        Column(modifier.heightIn(min=64.dp)
            .clip(shape).background(fill)
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

/** A single underline follows the active home tab. */
@Composable internal fun SlidingTabs(index:Int,count:Int,modifier:Modifier=Modifier,
    content:@Composable RowScope.()->Unit) {
    BoxWithConstraints(modifier) {
        val cell = maxWidth/count
        val position by androidx.compose.animation.core.animateDpAsState(
            targetValue=cell*index,
            animationSpec=androidx.compose.animation.core.spring(dampingRatio=.88f,stiffness=420f),
            label="tabPosition")
        Box(Modifier.matchParentSize()) {
            val shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
            Box(Modifier.align(androidx.compose.ui.Alignment.BottomStart)
                .offset { androidx.compose.ui.unit.IntOffset((position+cell*.25f).roundToPx(),0) }
                .width(cell*.5f).height(3.dp).background(MaterialTheme.colorScheme.primary,shape))
        }
        Row(Modifier.fillMaxWidth().selectableGroup(),
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
    GlassIconButton(onClick=onClick,modifier=modifier,colors=IconButtonDefaults.filledTonalIconButtonColors(
        containerColor=if(overVideo) Color.Black.copy(alpha=.50f) else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor=if(overVideo) Color.White else MaterialTheme.colorScheme.onSurface)) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack,label)
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

@Composable internal fun PageHeading(title: String, inset: androidx.compose.ui.unit.Dp = PageGutter, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min=60.dp).padding(horizontal=inset),
        horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        Text(title,Modifier.weight(1f),style=MaterialTheme.typography.headlineMedium)
        actions()
    }
}

@Composable internal fun PocketSwitch(checked: Boolean) {
    Switch(checked=checked,onCheckedChange=null,colors=SwitchDefaults.colors(
        checkedTrackColor=MaterialTheme.colorScheme.tertiary,checkedThumbColor=MaterialTheme.colorScheme.onTertiary))
}

@Composable internal fun SearchHeader(back: (() -> Unit)? = null, backLabel: String = "返回", field: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=PageGutter),
        verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        if(back!=null) PocketBack(back, label=backLabel)
        Box(Modifier.weight(1f)) { field() }
    }
}
