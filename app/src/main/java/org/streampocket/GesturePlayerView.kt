// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar

/** Owns only touch recognition. All delayed work dies with its session/view/lifecycle. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class GesturePlayerView(context:Context):PlayerView(context) {
    var seekRelative:(Long)->String = { "当前不可跳转" }
    var feedback:(String)->Unit = {}
    var gesturesEnabled=true
        set(value) { if(field!=value) cancelGestures();field=value }
    private val timer=Handler(Looper.getMainLooper())
    private var held=false
    private var direction=1
    private var controlsTouch=false
    private var gestureOwner:androidx.media3.common.Player?=null
    private val repeatSeek=object:Runnable {
        override fun run() {
            if(!held || !gesturesEnabled || player==null || player!==gestureOwner || !isAttachedToWindow) { cancelGestures();return }
            // Drop intermediate ticks while the decoder/source is catching up. There is
            // no accumulated target or seek queue to replay after release/cancellation.
            if(player?.playbackState!=androidx.media3.common.Player.STATE_BUFFERING)
                feedback(seekRelative(direction*2000L))
            timer.postDelayed(this,250)
        }
    }
    private val detector=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e:MotionEvent)=true
        override fun onSingleTapConfirmed(e:MotionEvent):Boolean {
            if(gesturesEnabled && player===gestureOwner) performClick()
            return true
        }
        override fun onDoubleTap(e:MotionEvent):Boolean {
            if(gesturesEnabled && player===gestureOwner) feedback(seekRelative(if(e.x<width/2f) -10000 else 10000))
            return true
        }
        override fun onLongPress(e:MotionEvent) {
            if(!gesturesEnabled || player!==gestureOwner) return
            direction=if(e.x<width/2f) -1 else 1;held=true;timer.post(repeatSeek)
        }
        override fun onScroll(e1:MotionEvent?,e2:MotionEvent,distanceX:Float,distanceY:Float):Boolean {
            held=false;timer.removeCallbacks(repeatSeek);return true
        }
    })
    init {
        controllerAutoShow=false
        controllerShowTimeoutMs=3000
        setControllerAnimationEnabled(false)
        setShowNextButton(false);setShowPreviousButton(false)
        contentDescription="视频，单击显示控制，左右双击跳转10秒，长按连续跳转"
    }
    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        if(!gesturesEnabled) return super.dispatchTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            controlsTouch=isControllerFullyVisible && hitsControl(findViewById(androidx.media3.ui.R.id.exo_controller),event.rawX.toInt(),event.rawY.toInt())
            gestureOwner=player
        }
        if(controlsTouch) return super.dispatchTouchEvent(event)
        if(event.pointerCount>1) { cancelGestures();return true }
        detector.onTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) {
            held=false;timer.removeCallbacks(repeatSeek)
        }
        return true
    }
    private fun hitsControl(v:View?,x:Int,y:Int):Boolean {
        if(v==null || !v.isShown) return false
        val rect=Rect();if(!v.getGlobalVisibleRect(rect) || !rect.contains(x,y)) return false
        if(v is TimeBar || (v !is ViewGroup && (v.isClickable || v.isLongClickable))) return true
        return v is ViewGroup && (0 until v.childCount).any { hitsControl(v.getChildAt(it),x,y) }
    }
    fun cancelGestures() {
        held=false;timer.removeCallbacksAndMessages(null)
        val e=MotionEvent.obtain(0,0,MotionEvent.ACTION_CANCEL,0f,0f,0)
        detector.onTouchEvent(e);e.recycle();gestureOwner=null
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) { cancelGestures();super.onSizeChanged(w,h,oldw,oldh) }
    override fun onWindowFocusChanged(hasWindowFocus:Boolean) { if(!hasWindowFocus) cancelGestures();super.onWindowFocusChanged(hasWindowFocus) }
    override fun onDetachedFromWindow() { cancelGestures();seekRelative={ "" };feedback={};super.onDetachedFromWindow() }
}

internal fun boundedSeek(position:Long,delta:Long,duration:Long,seekable:Boolean):Long? {
    if(!seekable || duration<=0) return null
    val current=position.coerceIn(0,duration)
    return if(delta>=0) current+delta.coerceAtMost(duration-current) else current+delta.coerceAtLeast(-current)
}
internal fun adjacentEpisode(detail:Detail?,playback:Playback,direction:Int):Episode? {
    if(direction !in listOf(-1,1) || detail?.video?.id!=playback.video.id || detail.video.source.key!=playback.video.source.key) return null
    val episodes=detail.lines.firstOrNull { line -> line.episodes.any { it.url==playback.episode.url } }?.episodes ?: return null
    val index=episodes.indexOfFirst { it.url==playback.episode.url }
    return episodes.getOrNull(index+direction)
}
