// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
    val rotation by animateFloatAsState(if(expanded) 180f else 0f,tween(220),label="settingsChevron")
    Card(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.background)) {
        TextButton(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth().heightIn(min=64.dp),shape=MaterialTheme.shapes.large,contentPadding=PaddingValues(16.dp)) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(title,style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurface)
                Text(summary.replace(" · "," / "),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.KeyboardArrowDown,if(expanded) "收起$title" else "展开$title",Modifier.graphicsLayer { rotationZ=rotation })
        }
        AnimatedVisibility(expanded,enter=expandVertically(tween(220))+fadeIn(tween(150)),exit=shrinkVertically(tween(180))+fadeOut(tween(100))) {
            Column(Modifier.padding(start=16.dp,end=16.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
        }
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
@Composable fun ContentCard(modifier:Modifier=Modifier,onClick:(()->Unit)?=null,containerColor:androidx.compose.ui.graphics.Color=MaterialTheme.colorScheme.surfaceContainer,content:@Composable ColumnScope.()->Unit) {
    val colors=CardDefaults.cardColors(containerColor=containerColor)
    if(onClick==null) Card(modifier=modifier,shape=MaterialTheme.shapes.medium,colors=colors,content=content)
    else {
        val interactions=remember { MutableInteractionSource() }
        Card(onClick=onClick,modifier=modifier.pressFeedback(interactions),interactionSource=interactions,shape=MaterialTheme.shapes.medium,colors=colors,content=content)
    }
}
@Composable fun ContentDivider(modifier:Modifier=Modifier) {
    HorizontalDivider(modifier,color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.35f))
}

@Composable fun ContentFilterChip(selected:Boolean,onClick:()->Unit,modifier:Modifier=Modifier,reserveSelectionSpace:Boolean=false,label:@Composable ()->Unit) {
    FilterChip(selected=selected,onClick=onClick,label=label,modifier=modifier.minimumInteractiveComponentSize(),shape=RoundedCornerShape(10.dp),
        border=androidx.compose.foundation.BorderStroke(1.dp,if(selected) MaterialTheme.colorScheme.primary.copy(alpha=.45f) else androidx.compose.ui.graphics.Color.Transparent),
        leadingIcon=if(selected) { { Icon(Icons.Default.Check,null,Modifier.size(14.dp)) } } else if(reserveSelectionSpace) { { Spacer(Modifier.size(14.dp)) } } else null,
        colors=FilterChipDefaults.filterChipColors(containerColor=androidx.compose.ui.graphics.Color.Transparent,
            labelColor=MaterialTheme.colorScheme.onSurfaceVariant,selectedContainerColor=MaterialTheme.colorScheme.primary.copy(alpha=.10f),
            selectedLabelColor=MaterialTheme.colorScheme.primary,selectedLeadingIconColor=MaterialTheme.colorScheme.primary))
}

/** Reserve geometry independently of fallback-font metrics and whether the text wraps. */
@Composable fun CardTextSlot(text:String,style:androidx.compose.ui.text.TextStyle,modifier:Modifier=Modifier,color:androidx.compose.ui.graphics.Color=LocalContentColor.current,lines:Int=2) {
    val height=with(androidx.compose.ui.platform.LocalDensity.current) { style.lineHeight.toDp()*lines+8.sp.toDp() }
    Box(modifier.fillMaxWidth().height(height)) {
        Text(text,style=style,color=color,maxLines=lines,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** Shared filled search surface used by home and history, without a decorative outline. */
@Composable internal fun contentSearchColors()=TextFieldDefaults.colors(
    focusedContainerColor=MaterialTheme.colorScheme.surfaceContainer,
    unfocusedContainerColor=MaterialTheme.colorScheme.surfaceContainer,
    focusedIndicatorColor=androidx.compose.ui.graphics.Color.Transparent,
    unfocusedIndicatorColor=androidx.compose.ui.graphics.Color.Transparent)

/** One search field geometry and keyboard contract; callers supply their own action. */
@Composable internal fun ContentSearchField(value:String,onValueChange:(String)->Unit,placeholder:String,
    onSearch:()->Unit,modifier:Modifier=Modifier,trailingIcon:@Composable ()->Unit) {
    val interactions=remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    androidx.compose.foundation.text.BasicTextField(value=value,onValueChange=onValueChange,modifier=modifier,
        singleLine=true,interactionSource=interactions,
        textStyle=MaterialTheme.typography.bodyLarge.copy(fontSize=15.sp,color=MaterialTheme.colorScheme.onSurface),
        cursorBrush=androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions=androidx.compose.foundation.text.KeyboardActions(onSearch={onSearch()}),
        decorationBox={input ->
            Surface(shape=RoundedCornerShape(10.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,
                border=androidx.compose.foundation.BorderStroke(1.dp,if(focused) MaterialTheme.colorScheme.primary.copy(alpha=.65f) else androidx.compose.ui.graphics.Color.Transparent)) {
                Row(Modifier.fillMaxWidth().heightIn(min=52.dp).padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(R.drawable.ic_search),null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.weight(1f).padding(vertical=12.dp)) {
                        if(value.isEmpty()) Text(placeholder,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        input()
                    }
                    trailingIcon()
                }
            }
        })
}
