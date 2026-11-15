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
    var verticalGesturesEnabled=false
        set(value) { if(field!=value) cancelGestures();field=value }
    var beginVertical:(Boolean)->Unit = {}
    var adjustVertical:(Float)->String = { "" }
    private var verticalAxis=0 // 0 undecided, 1 vertical, -1 horizontal
    private var verticalCancelled=false
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
            held=false;timer.removeCallbacks(repeatSeek)
            if(!verticalGesturesEnabled || verticalCancelled || player!==gestureOwner || e1==null) return true
            val dx=e2.x-e1.x;val dy=e2.y-e1.y
            if(verticalAxis==0) {
                verticalAxis=if(kotlin.math.abs(dy)>kotlin.math.abs(dx)*1.2f) 1 else -1
                if(verticalAxis==1 && e1.x/width !in 0f..0.25f && e1.x/width !in 0.75f..1f) verticalAxis=-1
                if(verticalAxis==1) beginVertical(e1.x<width/2f)
            }
            if(verticalAxis==1) feedback(adjustVertical(-dy/(height*.65f).coerceAtLeast(1f)))
            return true
        }
    })
    init {
        // A bounded scrim keeps white transport icons legible even over bright video frames.
        findViewById<View>(androidx.media3.ui.R.id.exo_controls_background).setBackgroundColor(0x80000000.toInt())
        findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar).setBackgroundColor(android.graphics.Color.TRANSPARENT)
        for (id in listOf(androidx.media3.ui.R.id.exo_position, androidx.media3.ui.R.id.exo_duration)) {
            findViewById<android.widget.TextView>(id).setShadowLayer(resources.displayMetrics.density * 2, 0f, 1f, android.graphics.Color.BLACK)
        }
        findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
            setPlayedColor(android.graphics.Color.rgb(239,227,208))
            setScrubberColor(android.graphics.Color.WHITE)
            setBufferedColor(0xff77777b.toInt())
        }
        controllerAutoShow=false
        controllerShowTimeoutMs=3000
        setControllerAnimationEnabled(false)
        setShowNextButton(false);setShowPreviousButton(false)
        contentDescription="视频，单击显示控制，左右双击跳转10秒，长按连续跳转，全屏左侧上下滑动调亮度、右侧调音量"
    }
    private var fullscreenLayout=false
    fun setFullscreenControls(enabled:Boolean, leftGutter:Int, rightGutter:Int) {
        val controllerView = findViewById<View>(androidx.media3.ui.R.id.exo_controller)
        val frame = controllerView.layoutParams as ViewGroup.MarginLayoutParams
        val left = if (enabled) leftGutter else 0
        val right = if (enabled) rightGutter else 0
        if (frame.leftMargin != left || frame.rightMargin != right) {
            frame.leftMargin = left
            frame.rightMargin = right
            controllerView.layoutParams = frame
        }
        if(fullscreenLayout==enabled) return
        fullscreenLayout=enabled
        val bar=findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_bottom_bar)
        val time=findViewById<View>(androidx.media3.ui.R.id.exo_time)
        val transport=findViewById<View>(androidx.media3.ui.R.id.exo_center_controls)
        val unit=resources.displayMetrics.density
        fun dp(value:Int)=(value*unit).toInt()
        setShowRewindButton(!enabled)
        setShowFastForwardButton(!enabled)
        val barParams=bar.layoutParams
        barParams.height=if(enabled) dp(100) else resources.getDimensionPixelSize(androidx.media3.ui.R.dimen.exo_styled_bottom_bar_height)
        bar.layoutParams=barParams
        val timeParams=time.layoutParams as android.widget.FrameLayout.LayoutParams
        timeParams.gravity=android.view.Gravity.START or (if(enabled) android.view.Gravity.TOP else android.view.Gravity.CENTER_VERTICAL)
        timeParams.marginStart=0
        time.layoutParams=timeParams
        val controls=transport.layoutParams as android.widget.FrameLayout.LayoutParams
        controls.gravity=if(enabled) android.view.Gravity.BOTTOM or android.view.Gravity.START else android.view.Gravity.CENTER
        controls.marginStart=0
        val group = transport as ViewGroup
        val play = findViewById<android.widget.ImageButton>(androidx.media3.ui.R.id.exo_play_pause)
        val groupPad = if (enabled) 0 else resources.getDimensionPixelSize(androidx.media3.ui.R.dimen.exo_styled_controls_padding)
        group.setPadding(groupPad, groupPad, groupPad, groupPad)
        group.removeView(play)
        group.addView(play, if (enabled) 0 else 2)
        val buttonSize = if (enabled) dp(48) else resources.getDimensionPixelSize(androidx.media3.ui.R.dimen.exo_icon_size)
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            val params = child.layoutParams as ViewGroup.MarginLayoutParams
            val side = if (enabled) 0 else resources.getDimensionPixelSize(androidx.media3.ui.R.dimen.exo_icon_horizontal_margin)
            params.setMargins(side, 0, side, 0)
            params.width = buttonSize
            params.height = buttonSize
            child.layoutParams = params
        }
        val pad = if (enabled) dp(12) else resources.getDimensionPixelSize(androidx.media3.ui.R.dimen.exo_icon_padding)
        play.setPadding(pad, pad, pad, pad)
        play.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        transport.layoutParams=controls
        bar.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        findViewById<View>(androidx.media3.ui.R.id.exo_controls_background).setBackgroundColor(if(enabled) android.graphics.Color.TRANSPARENT else 0x80000000.toInt())
    }
    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        if(!gesturesEnabled) return super.dispatchTouchEvent(event)
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            controlsTouch=isControllerFullyVisible && hitsControl(findViewById(androidx.media3.ui.R.id.exo_controller),event.rawX.toInt(),event.rawY.toInt())
            gestureOwner=player
            verticalAxis=0;verticalCancelled=false
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
        held=false;verticalCancelled=true;timer.removeCallbacksAndMessages(null)
        val e=MotionEvent.obtain(0,0,MotionEvent.ACTION_CANCEL,0f,0f,0)
        detector.onTouchEvent(e);e.recycle();gestureOwner=null
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) { cancelGestures();super.onSizeChanged(w,h,oldw,oldh) }
    override fun onWindowFocusChanged(hasWindowFocus:Boolean) { if(!hasWindowFocus) cancelGestures();super.onWindowFocusChanged(hasWindowFocus) }
    override fun onDetachedFromWindow() { cancelGestures();seekRelative={ "" };feedback={};beginVertical={};adjustVertical={ "" };super.onDetachedFromWindow() }
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
