// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import android.media.AudioManager
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class PlaybackLevelsDeviceTest {
    @Test fun verticalControlsClampCancelAndRestore() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val audio=activity.getSystemService(AudioManager::class.java)
                val volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                val original=activity.window.attributes.screenBrightness
                val levels=PlaybackLevels(activity)
                val player=ExoPlayer.Builder(activity).build()
                val view=GesturePlayerView(activity)
                var message="";var seeks=0
                view.player=player
                view.layout(0,0,1000,600)
                view.verticalGesturesEnabled=true
                view.beginVertical=levels::begin;view.adjustVertical=levels::adjust
                view.feedback={message=it};view.seekRelative={seeks++;"seek"}
                var time=SystemClock.uptimeMillis()
                fun event(action:Int,x:Float,y:Float) {
                    val e=MotionEvent.obtain(time,time+50,action,x,y,0)
                    view.dispatchTouchEvent(e);e.recycle()
                }
                fun swipe(x:Float,dx:Float,dy:Float) {
                    time+=1000
                    event(MotionEvent.ACTION_DOWN,x,300f)
                    event(MotionEvent.ACTION_MOVE,x+dx,300f+dy)
                    event(MotionEvent.ACTION_UP,x+dx,300f+dy)
                }
                try {
                    swipe(200f,0f,-500f)
                    assertEquals(1f,activity.window.attributes.screenBrightness,0f)
                    assertTrue(message.startsWith("亮度"))
                    swipe(200f,0f,1000f)
                    assertEquals(.05f,activity.window.attributes.screenBrightness,0f)
                    swipe(200f,400f,-30f)
                    assertEquals("Horizontal movement cannot change brightness",.05f,activity.window.attributes.screenBrightness,0f)
                    if(!audio.isVolumeFixed) {
                        swipe(800f,0f,-1000f)
                        assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                        swipe(800f,0f,1000f)
                        assertEquals(0,audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                        assertEquals("音量 0%",message)
                    }
                    view.verticalGesturesEnabled=false
                    swipe(200f,0f,-500f)
                    assertEquals(.05f,activity.window.attributes.screenBrightness,0f)
                    view.verticalGesturesEnabled=true
                    event(MotionEvent.ACTION_DOWN,200f,300f)
                    view.cancelGestures()
                    event(MotionEvent.ACTION_MOVE,200f,0f)
                    event(MotionEvent.ACTION_UP,200f,0f)
                    assertEquals(.05f,activity.window.attributes.screenBrightness,0f)
                    assertEquals(0,seeks)
                    levels.restoreBrightness()
                    assertEquals(original,activity.window.attributes.screenBrightness,0f)
                    levels.restoreBrightness()
                    assertEquals(original,activity.window.attributes.screenBrightness,0f)
                } finally {
                    view.cancelGestures();view.player=null;player.release();levels.restoreBrightness()
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0)
                }
            }
        }
    }
}
