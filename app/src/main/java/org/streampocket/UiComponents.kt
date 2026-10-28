// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun QualityBadge(quality:VideoQuality,lastPlayback:Boolean=false,reserveLines:Int=1) {
    val reservedHeight=with(androidx.compose.ui.platform.LocalDensity.current) { MaterialTheme.typography.labelSmall.lineHeight.toDp()*2+8.sp.toDp() }+10.dp
    val badge:@Composable ()->Unit = {
        Surface(shape=RoundedCornerShape(8.dp),color=if(quality.evidence==QualityEvidence.MEASURED) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
            Text((if(lastPlayback && quality.evidence==QualityEvidence.MEASURED) "最近播放 · " else "")+quality.label,Modifier.padding(horizontal=8.dp,vertical=5.dp).widthIn(max=176.dp),style=MaterialTheme.typography.labelSmall,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
    if(reserveLines>1) Box(Modifier.height(reservedHeight)) { badge() } else badge()
}
@Composable fun EmptyPanel(title:String,body:String,icon:Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=36.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Surface(shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.surfaceContainerHigh) {
            Icon(painterResource(icon),null,Modifier.padding(22.dp).size(32.dp),tint=MaterialTheme.colorScheme.primary)
        }
        Text(title,style=MaterialTheme.typography.titleMedium,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
        Text(body,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable fun SettingsGroup(title:String,summary:String,content:@Composable ColumnScope.()->Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer)) {
        TextButton(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth().heightIn(min=64.dp),shape=MaterialTheme.shapes.large,contentPadding=PaddingValues(16.dp)) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(title,style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurface)
                Text(summary,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if(expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,if(expanded) "收起$title" else "展开$title")
        }
        if(expanded) Column(Modifier.padding(start=16.dp,end=16.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}

@Composable fun SettingSwitch(title:String,description:String,checked:Boolean,onCheckedChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked,role=Role.Switch,onValueChange=onCheckedChange).padding(vertical=8.dp).heightIn(min=56.dp),
        horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,style=MaterialTheme.typography.bodyLarge)
            Text(description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked=checked,onCheckedChange=null)
    }
}

/** Shared quiet surface for discovery, search and viewing history. */
@Composable fun ContentCard(modifier:Modifier=Modifier,onClick:(()->Unit)?=null,content:@Composable ColumnScope.()->Unit) {
    val colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer)
    if(onClick==null) Card(modifier=modifier,shape=RoundedCornerShape(28.dp),colors=colors,content=content)
    else Card(onClick=onClick,modifier=modifier,shape=RoundedCornerShape(28.dp),colors=colors,content=content)
}
@Composable fun ContentDivider(modifier:Modifier=Modifier) {
    HorizontalDivider(modifier,color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.35f))
}

@Composable fun ContentFilterChip(selected:Boolean,onClick:()->Unit,label:@Composable ()->Unit) {
    FilterChip(selected=selected,onClick=onClick,label=label,shape=RoundedCornerShape(28.dp),border=null,
        colors=FilterChipDefaults.filterChipColors(containerColor=MaterialTheme.colorScheme.surfaceContainer,
            selectedContainerColor=MaterialTheme.colorScheme.primaryContainer))
}

/** Reserve geometry independently of fallback-font metrics and whether the text wraps. */
@Composable fun CardTextSlot(text:String,style:androidx.compose.ui.text.TextStyle,modifier:Modifier=Modifier,color:androidx.compose.ui.graphics.Color=LocalContentColor.current) {
    val height=with(androidx.compose.ui.platform.LocalDensity.current) { style.lineHeight.toDp()*2+8.sp.toDp() }
    Box(modifier.fillMaxWidth().height(height)) {
        Text(text,style=style,color=color,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
