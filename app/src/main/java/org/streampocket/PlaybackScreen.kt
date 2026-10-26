// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import android.view.WindowManager
import android.content.pm.ActivityInfo
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@OptIn(ExperimentalLayoutApi::class)
@Composable fun PlaybackScreen(playback: Playback, model: AppModel) {
    val context = LocalContext.current
    val activity = context as androidx.activity.ComponentActivity
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var pip by remember { mutableStateOf(activity.isInPictureInPictureMode) }
    DisposableEffect(activity) {
        val listener=androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { pip=it.isInPictureInPictureMode }
        activity.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity.removeOnPictureInPictureModeChangedListener(listener) }
    }
    val state by model.state.collectAsState()
    var playerView by remember(playback.sessionId) { mutableStateOf<GesturePlayerView?>(null) }
    var controlsVisible by remember(playback.sessionId) { mutableStateOf(false) }
    var gestureFeedback by remember(playback.sessionId) { mutableStateOf("") }
    LaunchedEffect(gestureFeedback) { if(gestureFeedback.isNotEmpty()) { delay(1000);gestureFeedback="" } }
    var episodesOpen by remember { mutableStateOf(false) }
    var filterSummary by remember(playback.sessionId) { mutableStateOf("") }
    var health by remember(playback.sessionId) { mutableStateOf(PlaybackHealth()) }
    var keepPosition by remember { mutableStateOf(true) }
    var error by remember(playback.sessionId) { mutableStateOf<String?>(null) }
    var full by rememberSaveable { mutableStateOf(false) }
    val originalOrientation = rememberSaveable { activity.requestedOrientation }
    // Configuration changes resize the existing player instead of releasing/recreating it.
    // Restore the caller's policy (including unlocked/tablet layouts), not a portrait lock.
    LaunchedEffect(full, pip) {
        if(!pip) activity.requestedOrientation = if(full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else originalOrientation
    }
    DisposableEffect(activity) {
        onDispose {
            if(!activity.isChangingConfigurations) activity.requestedOrientation = originalOrientation
        }
    }
    val controller = remember(playback.sessionId) {
        PlaybackController(context.applicationContext,model.network,playback,
            checkpoint={ position,autoPlay -> model.checkpoint(playback,position,autoPlay) },
            intent={ model.playbackIntent(playback.sessionId,it) },
            playing={ if(it) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) },
            failed={ error=it },cleanupFailed=model::message,
            videoSize={ width,height -> model.measuredQuality(playback.sessionId,width,height) },
            automaticAds=state.saved.adFilter,
            adPaths=if(state.saved.adFilter) state.saved.adRules[playback.video.source.key].orEmpty().toSet() else emptySet(),
            filterReport={filterSummary=it},health={health=it},smoothMode=state.smoothPlayback)
    }
    LaunchedEffect(controller,state.smoothPlayback) { controller.setSmoothMode(state.smoothPlayback) }
    fun showAlternatives() { playerView?.cancelGestures();controller.checkpointNow();model.openAlternatives(playback.sessionId) }
    LaunchedEffect(full,pip,playerView,playback.sessionId) {
        playerView?.cancelGestures()
        playerView?.gesturesEnabled=!pip
        if(full || pip) playerView?.hideController()
    }
    BackHandler { playerView?.cancelGestures(); if(state.alternativeSession!=null) model.closeAlternatives() else if(episodesOpen) episodesOpen=false else if(full) full=false else model.stopPlayback() }
    DisposableEffect(full, pip) {
        val bars=WindowCompat.getInsetsController(activity.window,activity.window.decorView)
        if(full && !pip) { bars.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; bars.hide(WindowInsetsCompat.Type.systemBars()) }
        else bars.show(WindowInsetsCompat.Type.systemBars())
        onDispose { bars.show(WindowInsetsCompat.Type.systemBars()) }
    }
    DisposableEffect(controller,lifecycle) {
        val observer=LifecycleEventObserver { _,event -> when(event) {
            Lifecycle.Event.ON_START -> controller.start()
            Lifecycle.Event.ON_STOP -> { playerView?.cancelGestures();controller.stop(activity.isChangingConfigurations) }
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.start()
        onDispose { lifecycle.removeObserver(observer); controller.close(activity.isChangingConfigurations) }
    }
    val matchingDetail=state.detail?.takeIf { it.video.source.key==playback.video.source.key && it.video.id==playback.video.id }
    val hasEpisodes=matchingDetail?.lines?.any { line -> line.episodes.any { it.url==playback.episode.url } }==true
    val controls: @Composable () -> Unit = {
        Column(Modifier.heightIn(max=(LocalConfiguration.current.screenHeightDp * 0.55f).dp).verticalScroll(rememberScrollState())) {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            val previous=adjacentEpisode(state.detail,playback,-1)
            val next=adjacentEpisode(state.detail,playback,1)
            TextButton(enabled=previous!=null,onClick={ playerView?.cancelGestures();previous?.let { model.play(playback.video,it) } }) { Text("上一集") }
            TextButton(enabled=next!=null,onClick={ playerView?.cancelGestures();next?.let { model.play(playback.video,it) } }) { Text("下一集") }
            FilledTonalButton(modifier=Modifier.heightIn(min=48.dp),enabled=hasEpisodes,onClick={playerView?.cancelGestures();episodesOpen=true}) { Text("选集") }
            if(playback.video.source.kind==SourceKind.VOD) OutlinedButton(modifier=Modifier.heightIn(min=48.dp),onClick=::showAlternatives) { Text("快速换源") }
        }
        FlowRow(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            FilterChip(selected=state.smoothPlayback,onClick={model.smoothPlayback(!state.smoothPlayback)},label={Text("流畅优先")})
            Text(if(health.buffering) "正在缓冲 · 已缓冲 ${health.bufferedSeconds} 秒" else "已缓冲 ${health.bufferedSeconds} 秒",modifier=Modifier.padding(vertical=16.dp),style=MaterialTheme.typography.bodySmall)
        }
        if(state.smoothPlayback) Text("多清晰度视频优先 720p；单清晰度可换源改善",Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall)
        if(state.saved.adFilter) Text(filterSummary+" · 可在订阅页关闭",Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall)
        if(state.detailLoading) Text("正在加载选集…",modifier=Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall)
        else if(!hasEpisodes) Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(if(state.detailError!=null) "选集暂不可用：${state.detailError}" else "当前集不在可用线路中，选集暂不可用",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
            TextButton(onClick=model::retryPlaybackDetail) { Text("重试选集") }
        }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(onClick={playerView?.cancelGestures();model.stopPlayback()}) { Text("返回选集") }
            TextButton(onClick={
                playerView?.cancelGestures()
                if(activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                    try { activity.enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16,9)).build()) } catch(_:Exception) { error="设备当前不允许画中画" }
                } else error="设备不支持画中画"
            }) { Text("画中画") }
            TextButton(onClick={full=!full},modifier=Modifier.testTag("fullscreenToggle")) { Text(if(full) "退出全屏" else "横屏全屏") }
        }
    }
    }
    if(state.alternativeSession==playback.sessionId && !pip) {
        Dialog(onDismissRequest=model::closeAlternatives) {
            BackHandler(onBack=model::closeAlternatives)
            Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().heightIn(max=500.dp).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("切换同集来源",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                        TextButton(onClick=model::closeAlternatives) { Text("关闭") }
                    }
                    LazyColumn(Modifier.weight(1f,false),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        item { Text("${playback.video.title} · ${playback.episode.name}",style=MaterialTheme.typography.bodySmall) }
                        item { Row(verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(checked=keepPosition,onCheckedChange={keepPosition=it})
                            Text("保留当前进度（不同片源可能有偏差）",style=MaterialTheme.typography.bodySmall)
                        } }
                        if(state.alternativesLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        state.alternativesError?.let { item { Text(it,style=MaterialTheme.typography.bodySmall) } }
                        if(!state.alternativesLoading && state.alternatives.isEmpty()) item { Text("未找到同名同集的备用地址。可添加其他订阅来源后再试。") }
                        itemsIndexed(state.alternatives,key={_,it->it.episode.url}) { _,option ->
                            OutlinedButton(onClick={controller.checkpointNow();model.chooseAlternative(option,keepPosition)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),shape=MaterialTheme.shapes.medium) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text("${option.line} · ${option.episode.name}")
                                    Text(option.video.source.name,style=MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if(!state.alternativesLoading) item { TextButton(onClick=::showAlternatives) { Text("重新查找") } }
                    }
                }
            }
        }
    }
    if(episodesOpen && !pip) {
        val episodes=state.detail?.takeIf { it.video.id==playback.video.id && it.video.source.key==playback.video.source.key }?.lines?.firstOrNull { line -> line.episodes.any { it.url==playback.episode.url } }?.episodes.orEmpty()
        val selected=episodes.indexOfFirst { it.url==playback.episode.url }.coerceAtLeast(0)
        val listState=rememberLazyListState(initialFirstVisibleItemIndex=selected)
        LaunchedEffect(episodesOpen) { playerView?.cancelGestures() }
        Dialog(onDismissRequest={episodesOpen=false}) {
            BackHandler { episodesOpen=false }
            Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().heightIn(max=360.dp).padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text("选集",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                        TextButton(onClick={episodesOpen=false}) { Text("关闭") }
                    }
                    LazyColumn(state=listState,modifier=Modifier.weight(1f,false)) {
                        itemsIndexed(episodes,key={ index,episode -> "$index:${episode.url}" }) { _,episode ->
                            TextButton(onClick={ playerView?.cancelGestures();episodesOpen=false;if(episode.url!=playback.episode.url) model.play(playback.video,episode) },modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                                Text((if(episode.url==playback.episode.url) "正在播放 · " else "")+episode.name,color=if(episode.url==playback.episode.url) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("playbackSurface")) {
        Column(Modifier.fillMaxSize().then(if(full || pip) Modifier else Modifier.safeDrawingPadding())) {
            if(!full && !pip) {
                Text(playback.video.title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,modifier=Modifier.padding(16.dp))
                Row(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) { Text(playback.episode.name,Modifier.weight(1f));QualityBadge(playback.episode.quality) }
            }
            AndroidView(
                factory={GesturePlayerView(it).apply {
                    useController=true
                    seekRelative=controller::seekRelative
                    feedback={ gestureFeedback=it }
                    playerView=this
                    resizeMode=AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }},
                update={
                    controller.attach(it)
                    it.seekRelative=controller::seekRelative
                    it.feedback={ gestureFeedback=it }
                    // AndroidView may survive an episode change: rebind the listener to
                    // this session's Compose state, just like the seek/feedback callbacks.
                    it.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility -> controlsVisible=visibility==android.view.View.VISIBLE })
                    controlsVisible=it.isControllerFullyVisible
                    playerView=it;it.useController=!pip
                },
                onReset=null,onRelease={it.cancelGestures();controller.detach(it);if(playerView===it) playerView=null},
                modifier=Modifier.weight(1f).fillMaxWidth().testTag("videoViewport")
            )
            if(!full && !pip) {
                error?.let { Text(it,modifier=Modifier.padding(12.dp)); TextButton(onClick={error=null; controller.retry()}) { Text("重试播放") } }
                if(error==null && health.slow) Row(Modifier.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("缓冲较慢，可重连或快速换源",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={controller.retry()}) { Text("重新连接") }
                }
                controls()
            }
        }
        if(gestureFeedback.isNotEmpty() && !pip) {
            Text(gestureFeedback,color=Color.White,modifier=Modifier.align(Alignment.Center).background(Color.Black.copy(alpha=0.7f)).padding(12.dp))
        }
        if(full && !pip && controlsVisible && error==null && !health.slow) {
            // Overlay controls leave the entire landscape surface available to video.
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha=0.65f)).displayCutoutPadding()) { controls() }
        }
        // Recovery must remain reachable even with hidden controls or no initialized player.
        if(full && !pip) {
            (error ?: if(health.slow) "缓冲较慢，可重连或快速换源" else null)?.let {
                Column(Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(alpha=0.8f)).padding(12.dp).testTag("playbackError")) {
                    Text(it)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick={error=null; controller.retry()}) { Text("重试播放") }
                        if(playback.video.source.kind==SourceKind.VOD) TextButton(onClick=::showAlternatives) { Text("快速换源") }
                        TextButton(onClick={playerView?.cancelGestures();model.stopPlayback()}) { Text("返回选集") }
                    }
                }
            }
        }
    }
    }
}
