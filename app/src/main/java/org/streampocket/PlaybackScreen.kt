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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
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
    var optionsOpen by remember { mutableStateOf(false) }
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
    BackHandler { playerView?.cancelGestures(); if(state.alternativeSession!=null) model.closeAlternatives() else if(episodesOpen) episodesOpen=false else if(optionsOpen) optionsOpen=false else if(full) full=false else model.stopPlayback() }
    val darkTheme=LocalPocketDark.current
    DisposableEffect(full, pip,darkTheme) {
        val bars=WindowCompat.getInsetsController(activity.window,activity.window.decorView)
        bars.isAppearanceLightStatusBars=!(full || pip || darkTheme)
        bars.isAppearanceLightNavigationBars=!(full || pip || darkTheme)
        if(full && !pip) { bars.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; bars.hide(WindowInsetsCompat.Type.systemBars()) }
        else bars.show(WindowInsetsCompat.Type.systemBars())
        onDispose { bars.show(WindowInsetsCompat.Type.systemBars());bars.isAppearanceLightStatusBars=!darkTheme;bars.isAppearanceLightNavigationBars=!darkTheme }
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
    val configuration=LocalConfiguration.current
    // Media3 needs room for both center transport and its bottom time/settings bar.
    // Keep those controls available at 320dp, instead of entering minimal mode at 180dp high.
    val inlineVideoHeight=(configuration.screenWidthDp / (16f/9f)).coerceAtLeast(224f).coerceAtMost(configuration.screenHeightDp * 0.42f).dp
    val matchingDetail=state.detail?.takeIf { it.video.source.key==playback.video.source.key && it.video.id==playback.video.id }
    val hasEpisodes=matchingDetail?.lines?.any { line -> line.episodes.any { it.url==playback.episode.url } }==true
    val previous=adjacentEpisode(state.detail,playback,-1)
    val next=adjacentEpisode(state.detail,playback,1)
    val bufferingText=if(health.buffering) "正在缓冲 · ${health.bufferedSeconds} 秒" else "已缓冲 ${health.bufferedSeconds} 秒"
    val openEpisodes={ playerView?.cancelGestures();episodesOpen=true }
    val openOptions={ playerView?.cancelGestures();optionsOpen=true }
    val leavePlayback={ playerView?.cancelGestures();model.stopPlayback() }
    val stepPrevious={ playerView?.cancelGestures();previous?.let { model.play(playback.video,it) };Unit }
    val stepNext={ playerView?.cancelGestures();next?.let { model.play(playback.video,it) };Unit }
    val episodeControls: @Composable RowScope.() -> Unit = {
        PlayerIconAction(androidx.media3.ui.R.drawable.exo_icon_previous,"上一集",previous!=null,onClick=stepPrevious)
        FilledTonalButton(onClick=openEpisodes,enabled=hasEpisodes,modifier=Modifier.weight(1f).heightIn(min=48.dp),shape=MaterialTheme.shapes.medium) { Text("选集") }
        PlayerIconAction(androidx.media3.ui.R.drawable.exo_icon_next,"下一集",next!=null,onClick=stepNext)
    }
    MaterialTheme(colorScheme=if(full || pip) NightColors else MaterialTheme.colorScheme) {
    if(optionsOpen && !pip) {
        Dialog(onDismissRequest={optionsOpen=false}) {
            BackHandler { optionsOpen=false }
            Surface(shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.fillMaxWidth().heightIn(max=420.dp).padding(20.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("播放选项",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                        TextButton(onClick={optionsOpen=false}) { Text("关闭") }
                    }
                    Column(Modifier.weight(1f,false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        SettingSwitch("流畅优先","多清晰度优先 720p；单清晰度可通过换源改善",state.smoothPlayback,model::smoothPlayback)
                        Text(bufferingText,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(state.saved.adFilter) Text(filterSummary.ifBlank { "广告过滤已启用" }+" · 可在订阅页关闭",style=MaterialTheme.typography.bodySmall)
                        if(state.detailLoading) Text("正在加载选集…",style=MaterialTheme.typography.bodySmall)
                        else if(!hasEpisodes) {
                            Text("选集暂不可用",style=MaterialTheme.typography.bodySmall)
                            TextButton(onClick=model::retryPlaybackDetail) { Text("重试选集") }
                        }
                        HorizontalDivider()
                        OutlinedButton(modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),onClick={
                            optionsOpen=false;playerView?.cancelGestures()
                            if(activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                                try { activity.enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16,9)).build()) } catch(_:Exception) { error="设备当前不允许画中画" }
                            } else error="设备不支持画中画"
                        }) { Text("画中画") }
                        TextButton(onClick=leavePlayback,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("返回选集") }
                    }
                }
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
    CompositionLocalProvider(LocalContentColor provides if(full || pip) Color.White else MaterialTheme.colorScheme.onSurface) {
    Box(Modifier.fillMaxSize().background(if(full || pip) Color.Black else MaterialTheme.colorScheme.surface).testTag("playbackSurface")) {
        Column(Modifier.fillMaxSize().then(if(full || pip) Modifier else Modifier.safeDrawingPadding())) {
            if(!full && !pip) {
                Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=leavePlayback,modifier=Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回选集") }
                    Text(playback.video.title,Modifier.weight(1f).padding(horizontal=8.dp),style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                    IconButton(onClick=openOptions,modifier=Modifier.size(48.dp)) { Icon(Icons.Default.MoreVert,"更多播放选项") }
                }
            }
            Box((if(full || pip) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().height(inlineVideoHeight)).background(Color.Black).testTag("videoViewport")) {
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
                modifier=Modifier.fillMaxSize()
            )
            if(gestureFeedback.isNotEmpty() && !pip) {
                Text(gestureFeedback,color=Color.White,modifier=Modifier.align(Alignment.Center).background(Color.Black.copy(alpha=0.7f)).padding(12.dp))
            }
            }
            if(!full && !pip) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(playback.episode.name,style=MaterialTheme.typography.titleLarge)
                            Text(bufferingText,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        QualityBadge(playback.episode.quality)
                    }
                    Surface(color=MaterialTheme.colorScheme.surfaceContainer,shape=MaterialTheme.shapes.large) {
                        Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            episodeControls()
                            PlayerIconAction(androidx.media3.ui.R.drawable.exo_icon_fullscreen_enter,"横屏全屏",modifier=Modifier.testTag("fullscreenToggle"),onClick={playerView?.cancelGestures();full=true})
                        }
                    }
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text("当前来源",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(playback.video.source.name,style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                        }
                        if(playback.video.source.kind==SourceKind.VOD) OutlinedButton(onClick=::showAlternatives,modifier=Modifier.heightIn(min=48.dp)) { Text("快速换源") }
                    }
                    if(state.detailLoading) Text("正在加载选集…",style=MaterialTheme.typography.bodySmall)
                    else if(!hasEpisodes) {
                        Text(if(state.detailError!=null) "选集暂不可用：${state.detailError}" else "当前集不在可用线路中，选集暂不可用",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick=model::retryPlaybackDetail) { Text("重试选集") }
                    }
                    (error ?: if(health.slow) "缓冲较慢，可重连或快速换源" else null)?.let { problem ->
                        Surface(color=MaterialTheme.colorScheme.surfaceContainerHigh,shape=MaterialTheme.shapes.large) {
                            Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text(problem,style=MaterialTheme.typography.bodyMedium)
                                TextButton(onClick={error=null;controller.retry()}) { Text(if(error!=null) "重试播放" else "重新连接") }
                            }
                        }
                    }
                }
            }
        }
        if(full && !pip && controlsVisible && error==null && !health.slow) {
            // A single top rail leaves Media3's transport and timeline unobstructed.
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha=0.72f)).displayCutoutPadding().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                IconButton(onClick={playerView?.cancelGestures();full=false},modifier=Modifier.size(48.dp).testTag("fullscreenToggle")) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"退出全屏") }
                Text("${playback.video.title} · ${playback.episode.name}",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                PlayerIconAction(androidx.media3.ui.R.drawable.exo_icon_previous,"上一集",previous!=null,onClick=stepPrevious)
                TextButton(onClick=openEpisodes,enabled=hasEpisodes,modifier=Modifier.heightIn(min=48.dp)) { Text("选集") }
                PlayerIconAction(androidx.media3.ui.R.drawable.exo_icon_next,"下一集",next!=null,onClick=stepNext)
                if(playback.video.source.kind==SourceKind.VOD) TextButton(onClick=::showAlternatives,modifier=Modifier.heightIn(min=48.dp)) { Text("快速换源") }
                IconButton(onClick=openOptions,modifier=Modifier.size(48.dp)) { Icon(Icons.Default.MoreVert,"更多播放选项") }
            }
        }
        // Recovery must remain reachable even with hidden controls or no initialized player.
        if(full && !pip) {
            (error ?: if(health.slow) "缓冲较慢，可重连或快速换源" else null)?.let {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha=0.8f)).displayCutoutPadding().heightIn(max=(LocalConfiguration.current.screenHeightDp * 0.6f).dp).verticalScroll(rememberScrollState()).padding(16.dp).testTag("playbackError")) {
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
}

@Composable private fun PlayerIconAction(icon:Int,label:String,enabled:Boolean=true,modifier:Modifier=Modifier,onClick:()->Unit) {
    IconButton(onClick=onClick,enabled=enabled,modifier=modifier.size(48.dp)) {
        Icon(painterResource(icon),contentDescription=label,modifier=Modifier.size(24.dp))
    }
}
