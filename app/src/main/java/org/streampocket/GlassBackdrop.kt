// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import android.graphics.*
import androidx.core.graphics.scale
import kotlin.math.max

/** Three separable box passes approximate a Gaussian at <= 320 pixels on the long edge. */
internal fun blurGlassBitmap(source:Bitmap):Bitmap {
    val scale=minOf(1f,320f/max(source.width,source.height))
    val small=source.scale(max(1,(source.width*scale).toInt()),max(1,(source.height*scale).toInt()),true)
    val result=small.copy(Bitmap.Config.ARGB_8888,true)
    if(small!==source) small.recycle()
    val w=result.width;val h=result.height
    var input=IntArray(w*h);var output=IntArray(w*h)
    result.getPixels(input,0,w,0,0,w,h)
    val radius=5;val count=radius*2+1
    repeat(3) {
        for(horizontal in listOf(true,false)) {
            val lines=if(horizontal) h else w;val length=if(horizontal) w else h
            for(line in 0 until lines) {
                fun index(position:Int)=if(horizontal) line*w+position.coerceIn(0,w-1) else position.coerceIn(0,h-1)*w+line
                var r=0;var g=0;var b=0
                for(i in -radius..radius) { val c=input[index(i)];r+=Color.red(c);g+=Color.green(c);b+=Color.blue(c) }
                for(i in 0 until length) {
                    output[index(i)]=Color.rgb(r/count,g/count,b/count)
                    val old=input[index(i-radius)];val next=input[index(i+radius+1)]
                    r+=Color.red(next)-Color.red(old);g+=Color.green(next)-Color.green(old);b+=Color.blue(next)-Color.blue(old)
                }
            }
            val temp=input;input=output;output=temp
        }
    }
    result.setPixels(input,0,w,0,0,w,h)
    return result
}
