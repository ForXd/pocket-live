// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.runtime.getValue
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.BorderStroke
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

/** Controls share the parent backdrop, using light translucent tint rather than opaque paint. */
@Composable internal fun glassFill(selected:Boolean=false)=MaterialTheme.colorScheme.primary.copy(alpha=if(MaterialTheme.colorScheme.surface.luminance()<.5f) { if(selected) .20f else .10f } else { if(selected) .12f else .04f })
@Composable internal fun GlassButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    shape:Shape=MaterialTheme.shapes.medium,border:BorderStroke?=null,
    colors:ButtonColors=ButtonDefaults.outlinedButtonColors(containerColor=glassFill(),contentColor=MaterialTheme.colorScheme.onSurface),
    contentPadding:PaddingValues=PaddingValues(horizontal=16.dp,vertical=12.dp),content:@Composable RowScope.()->Unit) {
    val sheen=Brush.verticalGradient(listOf(MaterialTheme.colorScheme.onSurface.copy(alpha=if(MaterialTheme.colorScheme.surface.luminance()<.5f) .12f else 0f),Color.Transparent))
    OutlinedButton(onClick,modifier.heightIn(min=48.dp).clip(shape).background(sheen),enabled,shape,colors,border=border,contentPadding=contentPadding,content=content)
}
@Composable internal fun GlassIconButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    shape:Shape=MaterialTheme.shapes.medium,
    colors:IconButtonColors=IconButtonDefaults.outlinedIconButtonColors(containerColor=glassFill(),contentColor=MaterialTheme.colorScheme.onSurface),
    border:BorderStroke?=null,content:@Composable ()->Unit) {
    OutlinedIconButton(onClick,modifier,enabled,shape,colors=colors,border=border,content=content)
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
    modifier:Modifier=Modifier,icon:@Composable ()->Unit) {
    val tint=if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val fill by androidx.compose.animation.animateColorAsState(if(selected) glassFill(true) else Color.Transparent,
        animationSpec=androidx.compose.animation.core.tween(160),label="navigationSelection")
    androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides tint) {
        Column(modifier.heightIn(min=68.dp).clip(MaterialTheme.shapes.large).background(fill)
            .selectable(selected=selected,role=androidx.compose.ui.semantics.Role.Tab,onClick=onClick)
            .padding(horizontal=6.dp,vertical=8.dp),horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.spacedBy(4.dp,androidx.compose.ui.Alignment.CenterVertically)) {
            icon()
            Text(label,style=MaterialTheme.typography.labelMedium,fontWeight=if(selected) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,maxLines=1)
        }
    }
}
