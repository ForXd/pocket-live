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

@Composable fun QualityBadge(quality:VideoQuality,lastPlayback:Boolean=false) {
    Surface(shape=RoundedCornerShape(8.dp),color=if(quality.evidence==QualityEvidence.MEASURED) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
        Text((if(lastPlayback && quality.evidence==QualityEvidence.MEASURED) "最近播放 · " else "")+quality.label,Modifier.padding(horizontal=8.dp,vertical=5.dp).widthIn(max=176.dp),style=MaterialTheme.typography.labelSmall)
    }
}
@Composable fun EmptyPanel(title:String,body:String,icon:Int) {
    Surface(Modifier.fillMaxWidth().padding(vertical=8.dp),shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(20.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Icon(painterResource(icon),null,Modifier.size(28.dp),tint=MaterialTheme.colorScheme.primary)
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(title,style=MaterialTheme.typography.titleMedium)
                Text(body,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
