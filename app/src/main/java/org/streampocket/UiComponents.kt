// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable fun QualityBadge(quality:VideoQuality,lastPlayback:Boolean=false) {
    Surface(shape=RoundedCornerShape(8.dp),color=if(quality.evidence==QualityEvidence.MEASURED) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
        Text((if(lastPlayback && quality.evidence==QualityEvidence.MEASURED) "最近播放 · " else "")+quality.label,Modifier.padding(horizontal=8.dp,vertical=5.dp).widthIn(max=176.dp),style=MaterialTheme.typography.labelSmall)
    }
}
@Composable fun EmptyPanel(title:String,body:String,icon:Int) {
    Surface(Modifier.fillMaxWidth().padding(vertical=16.dp),shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(icon),null,Modifier.size(32.dp),tint=MaterialTheme.colorScheme.primary)
            Text(title,style=MaterialTheme.typography.titleLarge)
            Text(body,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
