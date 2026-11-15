// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable internal fun FullscreenStatus() {
    val context=LocalContext.current
    var percent by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(context) {
        val receiver=object:BroadcastReceiver() {
            override fun onReceive(context:Context,intent:Intent) {
                val level=intent.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)
                val scale=intent.getIntExtra(BatteryManager.EXTRA_SCALE,0)
                percent=if(level>=0 && scale>0) (100L*level/scale).toInt().coerceIn(0,100) else null
            }
        }
        context.registerReceiver(receiver,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    Box(Modifier.fillMaxWidth().heightIn(min=24.dp).testTag("fullscreenStatus")) {
        AndroidView(factory={ android.widget.TextClock(it).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize=13f
            format24Hour="HH:mm"
            format12Hour="h:mm a"
        } },modifier=Modifier.align(Alignment.Center))
        val value=percent
        Row(Modifier.align(Alignment.CenterEnd).padding(end=8.dp).semantics(mergeDescendants=true) {
            contentDescription=if(value==null) "电量未知" else "电量 $value%"
        },verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(value?.let { "$it%" } ?: "—",color=Color.White,style=MaterialTheme.typography.labelMedium)
            Canvas(Modifier.size(23.dp,12.dp)) {
                val line=1.3.dp.toPx()
                val width=size.width-3.dp.toPx()
                drawRect(Color.White,Offset(line/2,line/2),Size(width-line,size.height-line),style=Stroke(line))
                drawRect(Color.White,Offset(width,size.height*.3f),Size(2.dp.toPx(),size.height*.4f))
                if(value!=null && value>0) drawRect(Color.White,Offset(line*2,line*2),Size((width-4*line)*value/100f,size.height-4*line))
            }
        }
    }
}
