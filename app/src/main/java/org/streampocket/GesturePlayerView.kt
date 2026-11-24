// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import kotlin.math.roundToLong
import androidx.media3.common.Player
import kotlin.math.roundToInt
import androidx.core.view.updateLayoutParams

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
class GesturePlayerView @JvmOverloads constructor(context:Context, attrs:android.util.AttributeSet?=null):PlayerView(context,attrs) {
    companion object {
        // AndroidView supplies both dimensions from Compose constraints.
        @android.annotation.SuppressLint("InflateParams")
        fun inflateForCompose(context: Context): GesturePlayerView =
            android.view.LayoutInflater.from(context).inflate(R.layout.pocket_player, null) as GesturePlayerView
    }
    var seekRelative:(Long)->String = { "当前不可跳转" }
    var feedback:(String)->Unit = {}
    internal var onScrub:(ScrubPreview?)->Unit = {}
    private var scrubStart:ScrubPreview?=null
    private var scrubX=0f
    private var scrubTarget=0L
    private var draggingScrub=false
    private val slop=android.view.ViewConfiguration.get(context).scaledTouchSlop
    var liveSource=false
    private fun canScrub(p:Player)=!liveSource && p.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) &&
        !p.isCurrentMediaItemLive && !p.isPlayingAd && p.isCurrentMediaItemSeekable && p.duration>0

    private fun previewAt(x:Float) {
        val start=scrubStart ?: return
        val dx=x-scrubX
        if(!draggingScrub && kotlin.math.abs(dx)<slop) return
        draggingScrub=true
        val available=if(dx<0) scrubX else width-scrubX
        val range=if(dx<0) start.position else start.duration-start.position
        val fraction=(dx/available.coerceAtLeast(1f)).coerceIn(-1f,1f)
        scrubTarget=(start.position+fraction*range.toDouble()).roundToLong().coerceIn(0,start.duration)
        onScrub(start.copy(position=scrubTarget))
    }
    private fun endScrub(commit:Boolean) {
        val start=scrubStart ?: return
        scrubStart=null
        val p=player
        if(commit && scrubTarget!=start.position && p!=null && p===gestureOwner && canScrub(p)) {
            p.seekTo(scrubTarget.coerceIn(0,p.duration))
        }
        onScrub(null)
    }
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
            if(fullscreenLayout) {
                val p=player
                if(p==null || !canScrub(p)) { feedback("当前媒体不可跳转");return }
                val start=ScrubPreview(p.currentPosition.coerceIn(0,p.duration),p.duration)
                scrubX=e.x;draggingScrub=false;scrubStart=start;scrubTarget=start.position;onScrub(start)
                return
            }
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
        findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar).updateLayoutParams<ViewGroup.MarginLayoutParams> {
            height = resources.getDimensionPixelSize(R.dimen.player_bottom_bar_height)
            topMargin = resources.getDimensionPixelSize(R.dimen.player_bottom_bar_margin_top)
        }
        findViewById<View>(androidx.media3.ui.R.id.exo_progress).updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = resources.getDimensionPixelSize(R.dimen.player_progress_margin_bottom)
        }
        for (id in listOf(androidx.media3.ui.R.id.exo_position, androidx.media3.ui.R.id.exo_duration)) {
            findViewById<android.widget.TextView>(id).setShadowLayer(resources.displayMetrics.density * 2, 0f, 1f, android.graphics.Color.BLACK)
        }
        findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
            setPlayedColor(android.graphics.Color.rgb(239,227,208))
            setScrubberColor(android.graphics.Color.WHITE)
            setBufferedColor(0xff77777b.toInt())
        }
        setEnableComposeSurfaceSyncWorkaround(true)
        controllerAutoShow=false
        controllerShowTimeoutMs=3000
        setControllerAnimationEnabled(false)
        setShowNextButton(false);setShowPreviousButton(false)
        contentDescription="视频，单击显示控制，左右双击跳转10秒，全屏长按后左右滑动预览进度，松手跳转，左侧上下滑动调亮度、右侧调音量"
    }
    private var fullscreenLayout=false
    // DefaultTimeBar insets the drawn track by half its largest thumb. The toolbar
    // aligns to that drawn edge. Its touch region and the video still extend past it.
    private val thumbInset = (resources.getDimensionPixelSize(R.dimen.player_dragged_thumb_size) + 1) / 2
    fun setControlGeometry(enabled:Boolean, horizontalInset:Int, inlineActionsWidth:Int) {
        val controllerView = findViewById<View>(androidx.media3.ui.R.id.exo_controller)
        val frame = controllerView.layoutParams as ViewGroup.MarginLayoutParams
        val gutter = if (enabled) horizontalInset + (PageGutter.value * resources.displayMetrics.density).roundToInt() - thumbInset else 0
        if (frame.leftMargin != gutter || frame.rightMargin != gutter) {
            frame.leftMargin = gutter
            frame.rightMargin = gutter
            controllerView.layoutParams = frame
        }
        val bottom = findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar)
        val footer = bottom.layoutParams as ViewGroup.MarginLayoutParams
        if (footer.marginEnd != inlineActionsWidth) {
            footer.marginEnd = inlineActionsWidth
            bottom.layoutParams = footer
        }
        if(fullscreenLayout==enabled) return
        cancelGestures()
        fullscreenLayout=enabled
        findViewById<View>(androidx.media3.ui.R.id.exo_controls_background).setBackgroundColor(if(enabled) android.graphics.Color.TRANSPARENT else 0x80000000.toInt())
    }
    // Media3 places this full-width layer above the movie and behind its native controls.
    private val topShade=0xd6000000.toInt()
    private val fullscreenShading by lazy {
        android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(topShade,0xa3000000.toInt(),0x30000000,0,0,0x30000000,0xa3000000.toInt(),topShade))
    }
    private val fullscreenRecoveryShading by lazy {
        android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(topShade,0xa3000000.toInt(),0x30000000,0,0,0,0,0))
    }
    internal fun setFullscreenControlShading(showHeader:Boolean,showTransport:Boolean) {
        val layer=overlayFrameLayout ?: return
        val shade=when {
            showTransport -> fullscreenShading
            showHeader -> fullscreenRecoveryShading
            else -> null
        }
        if(layer.background!==shade) layer.background=shade
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
        if(scrubStart!=null) {
            when(event.actionMasked) {
                MotionEvent.ACTION_MOVE -> previewAt(event.x)
                MotionEvent.ACTION_UP -> { previewAt(event.x);endScrub(true) }
                MotionEvent.ACTION_CANCEL -> endScrub(false)
            }
        }
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
        endScrub(false)
        held=false;verticalCancelled=true;timer.removeCallbacksAndMessages(null)
        val e=MotionEvent.obtain(0,0,MotionEvent.ACTION_CANCEL,0f,0f,0)
        detector.onTouchEvent(e);e.recycle();gestureOwner=null
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) { cancelGestures();super.onSizeChanged(w,h,oldw,oldh) }
    override fun onWindowFocusChanged(hasWindowFocus:Boolean) { if(!hasWindowFocus) cancelGestures();super.onWindowFocusChanged(hasWindowFocus) }
    override fun onDetachedFromWindow() { cancelGestures();onScrub={};seekRelative={ "" };feedback={};beginVertical={};adjustVertical={ "" };super.onDetachedFromWindow() }
}

internal data class ScrubPreview(val position:Long,val duration:Long)

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
