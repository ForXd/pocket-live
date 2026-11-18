// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.view.WindowManager
import android.content.pm.ActivityInfo
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Dialog
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import kotlinx.coroutines.launch
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
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
    val state by model.state.collectAsStateWithLifecycle()
    var playerView by remember(playback.sessionId) { mutableStateOf<GesturePlayerView?>(null) }
    var controlsVisible by remember(playback.sessionId) { mutableStateOf(false) }
    var knownDuration by remember(playback.sessionId) { mutableLongStateOf(playback.duration) }
    var gestureFeedback by remember(playback.sessionId) { mutableStateOf("") }
    LaunchedEffect(gestureFeedback) { if(gestureFeedback.isNotEmpty()) { delay(1000);gestureFeedback="" } }
    var episodesOpen by remember { mutableStateOf(false) }
    var optionsOpen by remember { mutableStateOf(false) }
    var filterSummary by remember(playback.sessionId) { mutableStateOf("") }
    var health by remember(playback.sessionId) { mutableStateOf(PlaybackHealth()) }
    var keepPosition by remember { mutableStateOf(true) }
    var error by remember(playback.sessionId) { mutableStateOf<String?>(null) }
    var requestFull by rememberSaveable { mutableStateOf(false) }
    var entryOrientation by rememberSaveable { mutableIntStateOf(activity.resources.configuration.orientation) }
    val orientation = LocalConfiguration.current.orientation
    var rotatingBack by rememberSaveable { mutableStateOf(false) }
    val full = requestFull || (rotatingBack && orientation != entryOrientation)
    fun exitFullscreen() {
        rotatingBack = true
        requestFull = false
    }
    LaunchedEffect(rotatingBack, orientation) {
        if (rotatingBack) {
            // An unlocked phone may remain landscape after restoring its orientation policy.
            if (orientation != entryOrientation) delay(600)
            rotatingBack = false
        }
    }
    val levels=remember(activity) { PlaybackLevels(activity) }
    DisposableEffect(full,pip,levels) {
        onDispose { levels.restoreBrightness() }
    }
    val originalOrientation = rememberSaveable { activity.requestedOrientation }
    // Configuration changes resize the existing player instead of releasing/recreating it.
    // Restore the caller's policy (including unlocked/tablet layouts), not a portrait lock.
    LaunchedEffect(requestFull, pip) {
        if(!pip) activity.requestedOrientation = if(requestFull) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else originalOrientation
    }
    DisposableEffect(activity) {
        onDispose {
            if(!activity.isChangingConfigurations) activity.requestedOrientation = originalOrientation
        }
    }
    val controller = remember(playback.sessionId) {
        PlaybackController(context.applicationContext,model.network,playback,
            checkpoint={ position,autoPlay -> model.checkpoint(playback,position,autoPlay,knownDuration) },
            intent={ model.playbackIntent(playback.sessionId,it) },
            playing={ if(it) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) },
            failed={ error=it },cleanupFailed=model::message,
            videoSize={ width,height -> model.measuredQuality(playback.sessionId,width,height) },
            automaticAds=state.saved.adFilter,
            adPaths=if(state.saved.adFilter) state.saved.adRules[playback.video.source.key].orEmpty().toSet() else emptySet(),
            filterReport={filterSummary=it},health={health=it},smoothMode=state.smoothPlayback,durationChanged={knownDuration=it})
    }
    LaunchedEffect(controller,state.smoothPlayback) { controller.setSmoothMode(state.smoothPlayback) }
    fun showAlternatives() {
        episodesOpen=false
        optionsOpen=false
        gestureFeedback=""
        playerView?.cancelGestures()
        controller.checkpointNow()
        model.openAlternatives(playback.sessionId)
    }
    LaunchedEffect(full,pip,playerView,playback.sessionId) {
        playerView?.cancelGestures()
        playerView?.gesturesEnabled=!pip
        playerView?.verticalGesturesEnabled=full && !pip
        playerView?.beginVertical=levels::begin
        playerView?.adjustVertical=levels::adjust
        if(full || pip) playerView?.hideController()
    }
    BackHandler { playerView?.cancelGestures(); if(state.alternativeSession!=null) model.closeAlternatives() else if(episodesOpen) episodesOpen=false else if(optionsOpen) optionsOpen=false else if(full) exitFullscreen() else model.stopPlayback() }
    val darkTheme=LocalPocketDark.current
    LaunchedEffect(full, pip,darkTheme) {
        val bars=WindowCompat.getInsetsController(activity.window,activity.window.decorView)
        bars.isAppearanceLightStatusBars=!(full || pip || darkTheme)
        bars.isAppearanceLightNavigationBars=!(full || pip || darkTheme)
        if(full && !pip) { bars.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; bars.hide(WindowInsetsCompat.Type.systemBars()) }
        else bars.show(WindowInsetsCompat.Type.systemBars())
    }
    DisposableEffect(activity,darkTheme) {
        val bars=WindowCompat.getInsetsController(activity.window,activity.window.decorView)
        onDispose { bars.show(WindowInsetsCompat.Type.systemBars());bars.isAppearanceLightStatusBars=!darkTheme;bars.isAppearanceLightNavigationBars=!darkTheme }
    }
    DisposableEffect(controller,lifecycle) {
        val observer=LifecycleEventObserver { _,event -> when(event) {
            Lifecycle.Event.ON_START -> controller.foreground()
            Lifecycle.Event.ON_STOP -> { model.pauseRelatedDiscovery();model.closeAlternatives();playerView?.cancelGestures();levels.restoreBrightness();controller.background() }
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.foreground()
        onDispose { lifecycle.removeObserver(observer); controller.close(activity.isChangingConfigurations) }
    }
    val windowSize = windowSizeDp()
    // Media3 needs room for both center transport and its bottom time/settings bar.
    // Keep those controls available at 320dp, instead of entering minimal mode at 180dp high.
    val inlineVideoHeight=(windowSize.width.value / (16f/9f)).coerceAtLeast(224f).coerceAtMost(windowSize.height.value * 0.42f).dp
    val matchingDetail=state.detail?.takeIf { it.video.source.key==playback.video.source.key && it.video.id==playback.video.id }
    val currentLine=remember(matchingDetail,playback.episode.url) { matchingDetail?.lines?.firstOrNull { line -> line.episodes.any { it.url==playback.episode.url } } }
    val isLive=playback.video.source.kind==SourceKind.LIVE
    val hasEpisodes=currentLine!=null
    val previous=adjacentEpisode(state.detail,playback,-1)
    val next=adjacentEpisode(state.detail,playback,1)
    val slowBuffering by remember(playback.sessionId) { derivedStateOf { health.slow } }
    val openEpisodes={ controller.checkpointNow();playerView?.cancelGestures();playerView?.hideController();episodesOpen=true }
    val openOptions={ playerView?.cancelGestures();playerView?.hideController();optionsOpen=true }
    val leavePlayback={ playerView?.cancelGestures();model.stopPlayback() }
    val stepPrevious={ playerView?.cancelGestures();previous?.let { model.play(playback.video,it) };Unit }
    val stepNext={ playerView?.cancelGestures();next?.let { model.play(playback.video,it) };Unit }
    val supportsPip = activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
    fun enterPip() {
        optionsOpen=false
        playerView?.cancelGestures()
        if(!supportsPip) { error="设备不支持画中画";return }
        try {
            if(!activity.enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().apply {
                setAspectRatio(android.util.Rational(16,9))
                val bounds = android.graphics.Rect()
                if (playerView?.getGlobalVisibleRect(bounds) == true) setSourceRectHint(bounds)
                // Home backgrounds playback. PiP is explicitly requested by the player button.
                if (android.os.Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(false)
            }.build())) error="设备当前不允许画中画"
        } catch(_:IllegalStateException) { error="设备当前不允许画中画" }
    }
    val density = LocalDensity.current
    val quickWidth = if(supportsPip) IconTarget * 2 + ActionGap else IconTarget
    val cutout = WindowInsets.displayCutout
    val horizontalInset = maxOf(cutout.getLeft(density, androidx.compose.ui.unit.LayoutDirection.Ltr),
        cutout.getRight(density, androidx.compose.ui.unit.LayoutDirection.Ltr))
    val fullscreenPadding = Modifier.windowInsetsPadding(cutout.only(WindowInsetsSides.Vertical))
        .padding(horizontal=with(density) { horizontalInset.toDp() })
    MaterialTheme(colorScheme=if(full || pip) NightColors else MaterialTheme.colorScheme) {
    if(optionsOpen && !pip) {
        FrostedDialog(onDismissRequest={optionsOpen=false},placement=if(full) GlassPlacement.SIDE else GlassPlacement.BOTTOM,immersive=full,tag="playbackOptions") {
            BackHandler { optionsOpen=false }
            Surface(shape=MaterialTheme.shapes.extraLarge,color=Color.Transparent) {
                Column(Modifier.fillMaxWidth().then(if(full) Modifier.fillMaxHeight() else Modifier.heightIn(max=(windowSize.height.value*.88f).dp)).padding(20.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("播放选项",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                        PocketClose({optionsOpen=false})
                    }
                    Column(Modifier.weight(1f,false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        PlaybackTrackSettings(playerView?.player)
                        SettingSwitch("流畅优先","多清晰度优先 720p；单清晰度可通过换源改善",state.smoothPlayback,model::smoothPlayback)
                        BufferingLabel { health }
                        if(state.saved.adFilter) Text(filterSummary.ifBlank { "广告过滤已启用" }+" · 可在设置页关闭",style=MaterialTheme.typography.bodySmall)
                        if(!isLive && state.detailLoading) LoadingStatus("正在加载选集…")
                        else if(!isLive && !hasEpisodes) {
                            Text("选集暂不可用",style=MaterialTheme.typography.bodySmall)
                            GlassTextButton(onClick=model::retryPlaybackDetail) { Text("重试选集") }
                        }

                    }
                }
            }
        }
    }
    if(state.alternativeSession==playback.sessionId && !pip) {
        PlaybackSourcesSheet(playback, state.alternatives, state.alternativesLoading, state.alternativesError,
            full, keepPosition, { keepPosition = it }, { option ->
                controller.checkpointNow()
                model.chooseAlternative(option, keepPosition)
            }, ::showAlternatives, model::closeAlternatives)
    }
    if(episodesOpen && !pip && !full) {
        LaunchedEffect(episodesOpen) { playerView?.cancelGestures() }
        FrostedDialog(onDismissRequest={episodesOpen=false}) {
            BackHandler { episodesOpen=false }
            Surface(modifier=Modifier.widthIn(max=600.dp).fillMaxWidth(.94f),shape=MaterialTheme.shapes.extraLarge,color=Color.Transparent) {
                Column(Modifier.heightIn(max=(windowSize.height.value*.8f).dp).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text("选集",style=MaterialTheme.typography.titleLarge)
                            Text("正在播放 · ${playback.video.title}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                        }
                        PocketClose({episodesOpen=false})
                    }
                    PlayerCollectionBrowser(playback,matchingDetail,state,model,{playerView?.cancelGestures();episodesOpen=false},Modifier.weight(1f,false))
                }
            }
        }
    }
    val playbackInformation: @Composable ()->Unit = {
                Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(if(isLive) playback.video.typeName.ifBlank { "直播频道" } else playback.video.title,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if(isLive) playback.video.title else playback.episode.name,style=MaterialTheme.typography.headlineMedium)
                        }
                    }
                    if(!isLive && hasEpisodes) {
                        val canStep = previous!=null || next!=null
                        val largeText = density.fontScale > 1.3f
                        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            if(canStep) PlaybackStep("上一集",previous!=null,stepPrevious,if(largeText) Modifier else Modifier.weight(1f))
                            PlaybackStep("选集",true,openEpisodes,if(largeText || !canStep) Modifier else Modifier.weight(1f))
                            if(canStep) PlaybackStep("下一集",next!=null,stepNext,if(largeText) Modifier else Modifier.weight(1f))
                        }
                    }
                    val line=currentLine
                    if(!isLive && line!=null && line.episodes.size>1 && (windowSize.width<840.dp || windowSize.height<480.dp)) {
                        val index=remember(line,playback.episode.url) { line.episodes.indexOfFirst { it.url==playback.episode.url }.coerceAtLeast(0) }
                        val list=androidx.compose.foundation.lazy.rememberLazyListState(index)
                        LaunchedEffect(line,index) { list.scrollToItem(index) }
                        Text("本线路选集",style=MaterialTheme.typography.titleMedium)
                        LazyRow(state=list,horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.testTag("inlineEpisodeStrip")) {
                            items(line.episodes.size) { n ->
                                val episode=line.episodes[n]
                                ContentFilterChip(selected=n==index,onClick={if(n!=index) model.play(playback.video,episode)},
                                    label={Text(episode.name,maxLines=1,overflow=TextOverflow.Ellipsis)},modifier=Modifier.widthIn(max=160.dp))
                            }
                        }
                    }
                    Text("播放来源",style=MaterialTheme.typography.titleMedium)
                    Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest,MaterialTheme.shapes.medium),contentAlignment=Alignment.Center) {
                                Icon(painterResource(R.drawable.ic_play),null,Modifier.size(18.dp))
                            }
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                Text(playback.video.source.name,style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                                Text(listOfNotNull("当前来源",playback.episode.quality.takeUnless { it.evidence==QualityEvidence.UNKNOWN }?.label).joinToString(" · "),
                                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if(playback.video.source.kind==SourceKind.VOD) GlassButton(shape=MaterialTheme.shapes.medium,onClick=::showAlternatives) { Text("换源") }
                        }
                    }
                    if(!isLive && state.detailLoading) LoadingStatus("正在加载选集…")
                    else if(!isLive && !hasEpisodes) {
                        Text(if(state.detailError!=null) "选集暂不可用：${state.detailError}" else "当前集不在可用线路中，选集暂不可用",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        GlassTextButton(onClick=model::retryPlaybackDetail) { Text("重试选集") }
                    }
                    (error ?: if(slowBuffering) (if(isLive) "缓冲较慢，可重新连接" else "缓冲较慢，可重连或快速换源") else null)?.let { problem ->
                        Surface(color=MaterialTheme.colorScheme.surfaceContainerHigh,shape=MaterialTheme.shapes.large) {
                            Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                Text(problem,style=MaterialTheme.typography.bodyMedium)
                                GlassTextButton(onClick={error=null;controller.retry()}) { Text(if(error!=null) "重试播放" else "重新连接") }
                            }
                        }
                    }
                }
    }
    MaterialTheme(colorScheme=if(full || pip) NightColors else MaterialTheme.colorScheme) {
    CompositionLocalProvider(LocalContentColor provides if(full || pip) Color.White else MaterialTheme.colorScheme.onSurface) {
    Box(Modifier.fillMaxSize().background(if(full || pip) Color.Black else MaterialTheme.colorScheme.surface).testTag("playbackSurface")) {
        BoxWithConstraints(Modifier.fillMaxSize().then(if(full || pip) Modifier else Modifier.safeDrawingPadding())) {
        val wide=maxWidth>=840.dp && maxHeight>=480.dp && !full && !pip
        // Reserve space for two hour-long timestamps at the user's text scale.
        val separateActions = !wide && !full && !pip &&
            maxWidth < PageGutter + quickWidth + ActionGap + IconTarget + 120.dp * density.fontScale
        Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if(!full && !pip) {
                Row(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=PageGutter),verticalAlignment=Alignment.CenterVertically) {
                    PocketBack(leavePlayback, label = if(playback.video.source.kind==SourceKind.LIVE) "返回频道" else "返回选集")
                    Text("播放",Modifier.weight(1f).padding(horizontal=8.dp),style=MaterialTheme.typography.headlineSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                    GlassIconButton(onClick=openOptions,modifier=Modifier.size(48.dp)) { Icon(Icons.Default.MoreVert,"更多播放选项") }
                }
            }
            Box((if(full || pip || wide) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().height(inlineVideoHeight + if(separateActions) IconTarget else 0.dp)).background(Color.Black).testTag("videoViewport")) {
            AndroidView(
                factory={ GesturePlayerView.inflateForCompose(it).apply {
                    useController=true
                    setShowPreviousButton(false)
                    setShowNextButton(false)
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
                    it.setControlGeometry(full && !pip, horizontalInset,
                        with(density) { if(full || pip || separateActions) 0 else (PageGutter + quickWidth + ActionGap).roundToPx() })
                    it.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_settings)?.setOnClickListener { openOptions() }
                    playerView=it;it.useController=!pip
                },
                onReset=null,onRelease={it.cancelGestures();controller.detach(it);if(playerView===it) playerView=null},
                modifier=Modifier.fillMaxSize().padding(bottom=if(separateActions) IconTarget else 0.dp)
            )
            if(!full && !pip) {
                Row(Modifier.align(Alignment.BottomEnd).padding(end=PageGutter),
                    horizontalArrangement=Arrangement.spacedBy(ActionGap),verticalAlignment=Alignment.CenterVertically) {
                    val colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=Color.Black.copy(alpha=.5f),contentColor=Color.White)
                    if(supportsPip) GlassIconButton(onClick=::enterPip,colors=colors) {
                        Icon(painterResource(R.drawable.ic_pip),"进入画中画")
                    }
                    GlassIconButton(modifier=Modifier.testTag("fullscreenToggle"),colors=colors,
                        onClick={playerView?.cancelGestures();entryOrientation=orientation;rotatingBack=false;requestFull=true}) {
                        Icon(painterResource(R.drawable.ic_fullscreen_enter),"横屏全屏")
                    }
                }
            }
            if(gestureFeedback.isNotEmpty() && !pip) {
                Text(gestureFeedback,color=Color.White,modifier=Modifier.align(Alignment.Center).offset(y=(-64).dp).background(Color.Black.copy(alpha=0.7f),androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(horizontal=24.dp,vertical=16.dp))
            }
            }
            if(!full && !pip && !wide) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { playbackInformation() }
            }
        }
        if(wide) {
            Surface(Modifier.width(340.dp).fillMaxHeight().testTag("playerSidePanel"),color=MaterialTheme.colorScheme.surfaceContainerLow) {
                Column {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { playbackInformation() }
                    if(!isLive) HorizontalDivider(Modifier.padding(horizontal=PageGutter),color=MaterialTheme.colorScheme.outlineVariant)
                    if(!isLive) Column(Modifier.weight(1f).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("本线路选集",style=MaterialTheme.typography.titleMedium)
                        PlayerCollectionBrowser(playback,matchingDetail,state,model,{playerView?.cancelGestures();episodesOpen=false},Modifier.weight(1f))
                    }
                }
            }
        }
        }
        }

        if(full && episodesOpen && !pip) FrostedSidePanel(onDismiss={episodesOpen=false}) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("选集",style=MaterialTheme.typography.titleLarge)
                    Text("正在播放 · ${playback.video.title}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                PocketClose({episodesOpen=false})
            }
            PlayerCollectionBrowser(playback,matchingDetail,state,model,{playerView?.cancelGestures();episodesOpen=false},Modifier.weight(1f))
        }
        if(full && !pip && !episodesOpen && (controlsVisible || error!=null || slowBuffering)) {
            val oneRow = windowSize.width >= 700.dp && density.fontScale <= 1.3f
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().then(fullscreenPadding).padding(horizontal=PageGutter-IconFaceInset,vertical=4.dp)) {
                if(!oneRow) FullscreenStatus()
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(ActionGap),verticalAlignment=Alignment.CenterVertically) {
                    PocketBack({playerView?.cancelGestures();exitFullscreen()},Modifier.testTag("fullscreenToggle"), label = "退出全屏",overVideo=true)
                    Text(playback.video.title,Modifier.weight(1f).padding(start=6.dp),style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                    if(oneRow) FullscreenStatus(compact=true)
                    if(oneRow && !isLive) Spacer(Modifier.weight(0.35f))
                    val actionColors = ButtonDefaults.textButtonColors(contentColor=Color.White,disabledContentColor=Color.White.copy(alpha=.42f))
                    if(!isLive && oneRow) {
                        TextButton(onClick=stepPrevious,enabled=previous!=null,colors=actionColors,modifier=Modifier.semantics { contentDescription="上一集" }) { Text("上一集") }
                        TextButton(onClick=stepNext,enabled=next!=null,colors=actionColors,modifier=Modifier.semantics { contentDescription="下一集" }) { Text("下一集") }
                    } else if(!isLive) {
                        PlayerIconAction(R.drawable.ic_previous,"上一集",enabled=previous!=null,onClick=stepPrevious)
                        PlayerIconAction(R.drawable.ic_next,"下一集",enabled=next!=null,onClick=stepNext)
                    }
                    if(!isLive) TextButton(onClick=openEpisodes,enabled=hasEpisodes,colors=actionColors) { Text("选集") }
                    if(playback.video.source.kind==SourceKind.VOD && error==null && !slowBuffering) {
                        TextButton(onClick=::showAlternatives,colors=actionColors) { Text("换源") }
                    }
                    GlassIconButton(onClick={ if(supportsPip) enterPip() else openOptions() },colors=IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor=Color.Black.copy(alpha=.5f),contentColor=Color.White)) {
                        if(supportsPip) Icon(painterResource(R.drawable.ic_pip),"进入画中画")
                        else Icon(Icons.Default.MoreVert,"更多播放选项")
                    }
                }
            }
        }
        // Recovery must remain reachable even with hidden controls or no initialized player.
        if(full && !pip) {
            (error ?: if(slowBuffering) "缓冲较慢" else null)?.let { problem ->
                Row(Modifier.align(Alignment.BottomCenter).then(fullscreenPadding).padding(horizontal=PageGutter,vertical=52.dp)
                    .widthIn(max=600.dp).fillMaxWidth().smokeGlass(MaterialTheme.shapes.large)
                    .heightIn(max=windowSize.height*.6f).verticalScroll(rememberScrollState()).padding(horizontal=12.dp,vertical=4.dp)
                    .testTag("playbackError"), verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(problem, style=MaterialTheme.typography.titleSmall)
                        Text("${playback.episode.name} · ${android.text.format.DateUtils.formatElapsedTime(playback.position.coerceAtLeast(0)/1000)}",
                            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    GlassTextButton(onClick={error=null;controller.retry()}) { Text(if(error==null) "重连" else "重试播放") }
                    if(playback.video.source.kind==SourceKind.VOD) GlassButton(onClick=::showAlternatives,
                        colors=ButtonDefaults.filledTonalButtonColors(containerColor=MaterialTheme.colorScheme.tertiary,contentColor=MaterialTheme.colorScheme.onTertiary)) { Text("换源") }
                }
            }
        }
    }
    }
}
}

}

@Composable private fun PlaybackStep(label: String, enabled: Boolean, action: () -> Unit, modifier: Modifier) {
    GlassButton(onClick=action, enabled=enabled, modifier=modifier, shape=MaterialTheme.shapes.medium) {
        Text(label)
    }
}

@Composable private fun PlayerIconAction(icon:Int,label:String,modifier:Modifier=Modifier,enabled:Boolean=true,onClick:()->Unit) {
    IconButton(onClick=onClick,enabled=enabled,modifier=modifier.size(48.dp)) {
        Icon(painterResource(icon),contentDescription=label,modifier=Modifier.size(24.dp))
    }
}

/** Presentation only; selection delegates to the existing session owner. */
@Composable internal fun PlaybackEpisodeGrid(episodes:List<Episode>,currentUrl:String,select:(Episode)->Unit,history:Map<String,Playback>,modifier:Modifier=Modifier) {
    val playingIndex=remember(episodes,currentUrl) { episodes.indexOfFirst { it.url==currentUrl } }
    val selected=playingIndex.coerceAtLeast(0)
    val pageSize = 50
    val currentRange = selected / pageSize
    var range by remember(episodes,currentUrl) { mutableIntStateOf(currentRange) }
    val start = range * pageSize
    val visible = remember(episodes, start) { episodes.subList(start, minOf(start + pageSize, episodes.size)) }
    val grid = rememberLazyGridState()
    val rangeList = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(episodes,currentUrl,range) {
        if(visible.isNotEmpty()) grid.scrollToItem(if(range==currentRange) selected-start else 0)
        if(episodes.size>pageSize) rangeList.scrollToItem(range)
    }
    val shortPanel = windowSizeDp().height < 360.dp && LocalDensity.current.fontScale > 1.3f
    Column(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(episodes.size>pageSize) {
            val locate = {
                if(range!=currentRange) range=currentRange else scope.launch { grid.scrollToItem(selected-start) }
                Unit
            }
            val currentLabel = if(playingIndex>=0) "定位当前集 · ${episodes[selected].name}" else "回到开头"
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(ActionGap),verticalAlignment=Alignment.CenterVertically) {
                LazyRow(state=rangeList,horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.weight(1f).testTag("playbackEpisodeRanges")) {
                    items((episodes.size+pageSize-1)/pageSize) { index ->
                        ContentFilterChip(selected=index==range,onClick={range=index},modifier=Modifier.testTag("playbackRange:$index"),
                            label={Text("${index*pageSize+1}–${minOf((index+1)*pageSize,episodes.size)}")})
                    }
                }
                if(shortPanel) GlassIconButton(onClick=locate,modifier=Modifier.testTag("playbackCurrent")) {
                    Icon(painterResource(R.drawable.ic_locate),currentLabel)
                }
            }
            if(!shortPanel) GlassTextButton(onClick=locate,modifier=Modifier.testTag("playbackCurrent")) { Text(currentLabel) }
        }
    LazyVerticalGrid(columns=GridCells.Adaptive(if(LocalDensity.current.fontScale>1.3f) 144.dp else 100.dp),state=grid,
        modifier=Modifier.weight(1f,false).fillMaxWidth().testTag("playbackEpisodes"),verticalArrangement=Arrangement.spacedBy(8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        itemsIndexed(visible,key={index,episode->"$index:${episode.url}"}) { _,episode ->
            val playing=episode.url==currentUrl
            EpisodeProgressButton(episode,history[episode.url],playing,{select(episode)},Modifier.fillMaxWidth().heightIn(min=52.dp))
        }
    }
}
}

/** Browsing another season/source is isolated from the active Media3 session until an episode is chosen. */
@Composable private fun PlayerCollectionBrowser(current:Playback,detail:Detail?,state:UiState,model:AppModel,close:()->Unit,modifier:Modifier=Modifier) {
    var chosen by remember(current.sessionId) { mutableStateOf(current.video) }
    var browsing by remember(current.sessionId) { mutableStateOf(detail) }
    var loading by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var lineIndex by remember(chosen) { mutableIntStateOf(if(chosen.probeKey()==current.video.probeKey()) detail?.lines?.indexOfFirst { line -> line.episodes.any { it.url==current.episode.url } }?.coerceAtLeast(0) ?: 0 else 0) }
    LaunchedEffect(current.video) { model.related(current.video) }
    LaunchedEffect(chosen,attempt,detail) {
        problem=null
        if(chosen.probeKey()==detail?.video?.probeKey()) { browsing=detail;loading=false }
        else {
            loading=true;browsing=null
            try { browsing=model.browseDetail(chosen) }
            catch(e:kotlinx.coroutines.TimeoutCancellationException) { problem="加载超时，请重试或选择其他来源" }
            catch(e:kotlinx.coroutines.CancellationException) { throw e }
            catch(e:Exception) { problem=safeError(e) }
            finally { loading=false }
        }
    }
    val shortPanel = windowSizeDp().height < 360.dp && LocalDensity.current.fontScale > 1.3f
    val shown=browsing
    val selectedLine=shown?.lines?.getOrNull(lineIndex)
    val entries=selectedLine?.episodes.orEmpty()
    val progress=remember(chosen,state.saved.history,current) { episodeProgress(chosen,state.saved.history).toMutableMap().apply { if(chosen.probeKey()==current.video.probeKey() && current.duration>0) put(current.episode.url,current) } }
    Column(modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        // Limit the source/season header's height so the episode grid always retains room.
        val browseLabel = "正在浏览 · ${chosen.title}" + (if(loading || problem!=null) "" else " · ${entries.size} 集")
        Column(Modifier.heightIn(max=if(shortPanel) 56.dp else if(LocalDensity.current.fontScale>1.3f) 104.dp else 160.dp).verticalScroll(rememberScrollState())) {
            CollectionControls(chosen,state,model,{chosen=it})
            if((shown?.lines?.size ?: 0)>1) LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                items(shown!!.lines.size) { index -> ContentFilterChip(selected=index==lineIndex,onClick={lineIndex=index},label={Text(shown.lines[index].name)}) }
            }
            if(shortPanel) Text(browseLabel,style=MaterialTheme.typography.labelMedium)
        }
        if(!shortPanel) Text(browseLabel,style=MaterialTheme.typography.labelMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
        when {
            loading -> Text("正在加载 ${chosen.title}…",style=MaterialTheme.typography.bodySmall)
            problem!=null -> { Text(problem!!,style=MaterialTheme.typography.bodySmall);TextButton(onClick={attempt++}) { Text("重试") } }
            entries.isEmpty() -> Text("当前来源暂无剧集，可选择其他来源",style=MaterialTheme.typography.bodySmall)
            else -> PlaybackEpisodeGrid(entries,current.episode.url,{episode -> shown?.let { if(it.video.probeKey()!=current.video.probeKey() || episode.url!=current.episode.url) model.playDetail(it,episode);close() }},progress,Modifier.weight(1f))
        }
    }
}


@Composable private fun BufferingLabel(readHealth: () -> PlaybackHealth) {
    val status = readHealth()
    val prefix = if (status.buffering) "正在缓冲 ·" else "已缓冲"
    Text("$prefix ${status.bufferedSeconds} 秒", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
