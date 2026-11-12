// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import android.app.Activity
import android.media.AudioManager
import android.provider.Settings
import kotlin.math.roundToInt

/** Window-only brightness; media volume follows the device's normal stream limits. */
internal class PlaybackLevels(private val activity:Activity) {
    private val audio=activity.getSystemService(AudioManager::class.java)
    private var original:Float?=null
    private var brightness=true
    private var start=0f
    fun begin(left:Boolean) {
        brightness=left
        start=if(left) {
            val value=activity.window.attributes.screenBrightness
            if(original==null) original=value
            if(value>=0) value else Settings.System.getInt(activity.contentResolver,Settings.System.SCREEN_BRIGHTNESS,128)/255f
        } else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()/audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }
    fun adjust(delta:Float):String {
        val value=(start+delta).coerceIn(if(brightness) .05f else 0f,1f)
        if(brightness) {
            activity.window.attributes=activity.window.attributes.apply { screenBrightness=value }
            return "亮度 ${(value*100).roundToInt()}%"
        }
        if(audio.isVolumeFixed) return "此设备音量固定"
        val max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volume=(value*max).roundToInt()
        try { if(audio.getStreamVolume(AudioManager.STREAM_MUSIC)!=volume) audio.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0) }
        catch(_:SecurityException) { return "音量由系统管理" }
        return "音量 ${(audio.getStreamVolume(AudioManager.STREAM_MUSIC)*100f/max).roundToInt()}%"
    }
    fun restoreBrightness() {
        original?.let { value -> activity.window.attributes=activity.window.attributes.apply { screenBrightness=value } }
        original=null
    }
}
