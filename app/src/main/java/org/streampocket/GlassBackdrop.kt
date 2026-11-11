// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Window
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.math.max

/** Bounded, one-shot backdrop. Never captures on a frame timer and never creates a player. */
internal suspend fun captureGlassBackdrop(window:Window):Bitmap? {
    val view=window.decorView
    if(view.width<=0 || view.height<=0) return null
    val scale=320f/max(view.width,view.height)
    val bitmap=Bitmap.createBitmap(max(1,(view.width*scale).toInt()),max(1,(view.height*scale).toInt()),Bitmap.Config.ARGB_8888)
    val copied=suspendCancellableCoroutine<Boolean> { continuation ->
        try { PixelCopy.request(window,bitmap,{result ->
            if(continuation.isActive) continuation.resume(result==PixelCopy.SUCCESS)
            else bitmap.recycle()
        },Handler(Looper.getMainLooper())) }
        catch(_:Exception) { if(continuation.isActive) continuation.resume(false) }
    }
    if(!copied) { bitmap.recycle();return null }
    return try { withContext(Dispatchers.Default) { blurGlassBitmap(bitmap) } } finally { bitmap.recycle() }
}

/** Three separable box passes approximate a Gaussian at <= 320 pixels on the long edge. */
internal fun blurGlassBitmap(source:Bitmap):Bitmap {
    val scale=minOf(1f,320f/max(source.width,source.height))
    val small=Bitmap.createScaledBitmap(source,max(1,(source.width*scale).toInt()),max(1,(source.height*scale).toInt()),true)
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

/** Aligns a frozen blurred backdrop with the actual screen region behind this dialog. */
internal class GlassBackdropDrawable(private val source:Window,private val target:Window,private val radius:Float):Drawable() {
    var bitmap:Bitmap?=null
        set(value) { field=value;invalidateSelf() }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    override fun draw(canvas:Canvas) {
        val image=bitmap ?: return
        val from=IntArray(2);val to=IntArray(2)
        source.decorView.getLocationOnScreen(from);target.decorView.getLocationOnScreen(to)
        val rect=RectF(bounds)
        val clip=Path().apply { addRoundRect(rect,radius,radius,Path.Direction.CW) }
        canvas.save();canvas.clipPath(clip)
        canvas.drawBitmap(image,null,RectF((from[0]-to[0]).toFloat(),(from[1]-to[1]).toFloat(),(from[0]-to[0]+source.decorView.width).toFloat(),(from[1]-to[1]+source.decorView.height).toFloat()),paint)
        canvas.restore()
    }
    override fun setAlpha(alpha:Int) { paint.alpha=alpha }
    override fun setColorFilter(filter:ColorFilter?) { paint.colorFilter=filter }
    @Deprecated("Required by Drawable") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
