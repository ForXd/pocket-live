package org.streampocket

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.media3.common.util.Log

/** Opt-in instrumentation only. Keeps bounded strings, never Throwable/player/context references in logs. */
@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
class ReleaseDiagnostics(private val app:Application):Application.ActivityLifecycleCallbacks {
    private val entries=java.util.ArrayDeque<String>()
    private val logger=object:Log.Logger {
        override fun d(tag:String,message:String,t:Throwable?)=Unit
        override fun i(tag:String,message:String,t:Throwable?)=Unit
        override fun w(tag:String,message:String,t:Throwable?)=record(tag,t)
        override fun e(tag:String,message:String,t:Throwable?)=record(tag,t)
    }
    @Synchronized private fun record(tag:String,t:Throwable?) {
        val chain=generateSequence(t) { it.cause }.take(5).joinToString("\n") { error ->
            error.javaClass.name+"\n"+error.stackTrace.take(16).joinToString("\n")+
                error.suppressed.take(8).joinToString("\n",prefix="\n") { suppressed ->
                    "suppressed="+suppressed.javaClass.name+"\n"+suppressed.stackTrace.take(10).joinToString("\n")
                }
        }
        entries.addLast("elapsed=${android.os.SystemClock.elapsedRealtime()} thread=${Thread.currentThread().name} tag=$tag\n$chain".take(6000))
        while(entries.size>128) entries.removeFirst()
    }
    fun start() { PlaybackMetrics.diagnosticEnabled=true;app.registerActivityLifecycleCallbacks(this);Log.setLogger(logger);Log.setLogLevel(Log.LOG_LEVEL_WARNING) }
    @Synchronized fun snapshot(label:String) {
        val dir=java.io.File(app.getExternalFilesDir(null),"release-diagnostic").apply { mkdirs() }
        java.io.File(dir,"$label-errors.txt").writeText(entries.joinToString("\n\n"))
        java.io.File(dir,"$label-release-events.txt").writeText(PlaybackMetrics.releaseEvidence())
        java.io.File(dir,"$label-threads.txt").writeText(Thread.getAllStackTraces().entries
            .filter { it.key.name.contains("ExoPlayer") || it.key.name=="main" }
            .joinToString("\n\n") { "${it.key.name} state=${it.key.state}\n"+it.value.take(25).joinToString("\n") })
        java.io.File(dir,"$label-timing.txt").writeText("elapsed=${android.os.SystemClock.elapsedRealtime()} lastRelease=${PlaybackMetrics.lastReleaseElapsed} maxReleaseMs=${PlaybackMetrics.maxReleaseMs} timeouts=${PlaybackMetrics.releaseTimeouts} failures=${PlaybackMetrics.releaseFailures}")
    }
    fun close() { snapshot("final");PlaybackMetrics.diagnosticEnabled=false;app.unregisterActivityLifecycleCallbacks(this);Log.setLogLevel(Log.LOG_LEVEL_OFF);Log.setLogger(Log.Logger.DEFAULT) }
    override fun onActivityResumed(activity:Activity) { Log.setLogLevel(Log.LOG_LEVEL_WARNING) }
    override fun onActivityCreated(activity:Activity,state:Bundle?)=Unit
    override fun onActivityStarted(activity:Activity)=Unit
    override fun onActivityPaused(activity:Activity)=Unit
    override fun onActivityStopped(activity:Activity)=Unit
    override fun onActivitySaveInstanceState(activity:Activity,state:Bundle)=Unit
    override fun onActivityDestroyed(activity:Activity)=Unit
}
