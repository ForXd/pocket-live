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
import androidx.compose.ui.semantics.*
import kotlin.math.abs
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
import androidx.core.view.isVisible
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

internal data class PlayerUiState(
    val detail:Detail?, val detailLoading:Boolean, val detailError:String?,
    val alternativeSession:String?, val alternatives:List<PlaybackAlternative>, val alternativesLoading:Boolean,
    val alternativesError:String?, val smoothPlayback:Boolean, val saved:Saved,
    val relatedVideos:List<Video>, val relatedLoading:Boolean, val relatedError:String?,
    val probes:Map<String,ProbeStatus>, val probeResolutions:Map<String,String>, val directPlayback:Boolean
)
internal fun UiState.playerUiState()=PlayerUiState(detail,detailLoading,detailError,alternativeSession,alternatives,
    alternativesLoading,alternativesError,smoothPlayback,saved,relatedVideos,relatedLoading,relatedError,probes,probeResolutions,directPlayback)

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun PlaybackScreen(playback: Playback, model: AppModel,state:PlayerUiState) {
    val context = LocalContext.current
    val activity = context as androidx.activity.ComponentActivity
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var pip by remember { mutableStateOf(activity.isInPictureInPictureMode) }
    DisposableEffect(activity, model, playback.sessionId) {
        fun updatePip(active: Boolean) {
            pip = active
            if (active) model.closeAlternatives(playback.sessionId)
        }
        val listener = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { updatePip(it.isInPictureInPictureMode) }
        updatePip(activity.isInPictureInPictureMode)
        activity.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity.removeOnPictureInPictureModeChangedListener(listener) }
    }

    var playerView by remember(playback.sessionId) { mutableStateOf<GesturePlayerView?>(null) }
    var controlsVisible by remember(playback.sessionId) { mutableStateOf(false) }
    var knownDuration by remember(playback.sessionId) { mutableLongStateOf(playback.duration) }
    val scrub=remember(playback.sessionId) { mutableStateOf<ScrubPreview?>(null) }
    var speedHint by remember(playback.sessionId) { mutableStateOf("") }
    var lockedSpeed by remember(playback.sessionId) { mutableStateOf<Float?>(null) }
    var feedbackPosition by remember(playback.sessionId) { mutableStateOf<Alignment>(Alignment.Center) }
    val accessibility=androidx.compose.ui.platform.LocalAccessibilityManager.current
    var gestureFeedback by remember(playback.sessionId) { mutableStateOf("") }
    LaunchedEffect(gestureFeedback) { if(gestureFeedback.isNotEmpty()) { delay(accessibility?.calculateRecommendedTimeoutMillis(1000,containsText=true) ?: 1000);gestureFeedback="" } }
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
            failed={ error=it;model.playbackFailed(playback.sessionId) },cleanupFailed=model::message,
            videoSize={ width,height -> model.measuredQuality(playback.sessionId,width,height) },
            automaticAds=state.saved.adFilter,
            adPaths=if(state.saved.adFilter) state.saved.adRules[playback.video.source.key].orEmpty().toSet() else emptySet(),
            filterReport={filterSummary=it},health={health=it},smoothMode=state.smoothPlayback,durationChanged={knownDuration=it},
            playbackConfirmed={model.playbackConfirmed(playback.sessionId)})
    }
    LaunchedEffect(controller,state.smoothPlayback) { controller.setSmoothMode(state.smoothPlayback) }
    fun showAlternatives() {
        if (activity.isInPictureInPictureMode || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        episodesOpen=false
        optionsOpen=false
        gestureFeedback=""
        playerView?.cancelGestures(keepLockedSpeed=true)
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
    val returningFromPlayer = !full && !pip && state.alternativeSession==null && !episodesOpen && !optionsOpen
    val motion=LocalCardReturn.current
    CardBackHandler(enabled=returningFromPlayer) { playerView?.cancelGestures();model.stopPlayback() }
    BackHandler(enabled=!returningFromPlayer) { playerView?.cancelGestures(keepLockedSpeed=state.alternativeSession!=null || episodesOpen || optionsOpen); if(state.alternativeSession!=null) model.closeAlternatives(playback.sessionId) else if(episodesOpen) episodesOpen=false else if(optionsOpen) optionsOpen=false else if(full) exitFullscreen() else model.stopPlayback() }
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
            Lifecycle.Event.ON_STOP -> { model.pauseRelatedDiscovery();model.closeAlternatives(playback.sessionId);playerView?.cancelGestures();levels.restoreBrightness();controller.background() }
            else -> Unit
        } }
        lifecycle.addObserver(observer)
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.foreground()
        onDispose {
            lifecycle.removeObserver(observer)
            model.closeAlternatives(playback.sessionId)
            controller.close(activity.isChangingConfigurations)
        }
    }
    val windowSize = windowSizeDp()
    // Media3 needs room for both center transport and its bottom time/settings bar.
    // Keep those controls available at 320dp, instead of entering minimal mode at 180dp high.
    val inlineVideoHeight=(windowSize.width.value / (16f/9f)).coerceAtLeast(224f).coerceAtMost(windowSize.height.value * 0.42f).dp
    val matchingDetail=state.detail?.takeIf { it.video.source.key==playback.video.source.key && it.video.id==playback.video.id }
    val currentLine=remember(matchingDetail,playback.episode.url) { matchingDetail?.lines?.firstOrNull { line -> line.episodes.any { it.url==playback.episode.url } } }
    val isLive=playback.video.source.kind==SourceKind.LIVE
    val hasEpisodes=currentLine!=null
    val previous=remember(state.detail,playback.sessionId,playback.episode) { adjacentEpisode(state.detail,playback,-1) }
    val next=remember(state.detail,playback.sessionId,playback.episode) { adjacentEpisode(state.detail,playback,1) }
    val slowBuffering by remember(playback.sessionId) { derivedStateOf { health.slow } }
    val openEpisodes={ controller.checkpointNow();playerView?.cancelGestures(keepLockedSpeed=true);playerView?.hideController();episodesOpen=true }
    val openOptions={ playerView?.cancelGestures(keepLockedSpeed=true);playerView?.hideController();optionsOpen=true }
    val leavePlayback={ playerView?.cancelGestures();motion.commit(model::stopPlayback) }
    val stepPrevious={ playerView?.cancelGestures();previous?.let { model.play(playback.video,it) };Unit }
    val stepNext={ playerView?.cancelGestures();next?.let { model.play(playback.video,it) };Unit }
    val supportsPip = activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
    fun enterPip() {
        optionsOpen=false
        playerView?.cancelGestures()
        if(!supportsPip) { error="设备不支持画中画";return }
        try {
            if(!activity.enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().apply {
                val view=playerView
                val size=view?.player?.videoSize
                setAspectRatio(pictureInPictureAspect(size?.width ?: 0,size?.height ?: 0,size?.pixelWidthHeightRatio ?: 1f))
                val bounds=android.graphics.Rect()
                if(view?.videoSurfaceView?.getGlobalVisibleRect(bounds)==true) setSourceRectHint(bounds)
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
    val showFullscreenHeader=full && !pip && !episodesOpen && (controlsVisible || error!=null || slowBuffering)
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
                        PlaybackTrackSettings(playerView?.player) { speed -> playerView?.chooseSpeed(speed) }
                        var showGestures by remember { mutableStateOf(false) }
                        GlassTextButton(onClick={showGestures=!showGestures}) { Text(if(showGestures) "收起手势说明" else "全屏手势说明") }
                        if(showGestures) Text(fullscreenGestureHelp(isLive || playerView?.player?.isCurrentMediaItemLive==true),
                            style=MaterialTheme.typography.bodyMedium)
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
            }, ::showAlternatives, { model.closeAlternatives(playback.sessionId) })
    }
    if(episodesOpen && !pip && !full) {
        LaunchedEffect(episodesOpen) { playerView?.cancelGestures(keepLockedSpeed=true) }
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
                    PlayerCollectionBrowser(playback,matchingDetail,state,model,{playerView?.cancelGestures(keepLockedSpeed=true);episodesOpen=false},Modifier.weight(1f,false))
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
                    (error ?: if(slowBuffering) "缓冲较慢" else null)?.let { problem ->
                        PlaybackNotice(problem,error==null,playback.video.source.name,isLive,
                            code=playerView?.player?.playerError?.errorCode,retry={error=null;controller.retry()},alternatives=::showAlternatives)
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
                BackHeading("播放",if(state.directPlayback) "返回列表" else if(isLive) "返回频道" else "返回选集",leavePlayback) {
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
                }},
                update={
                    controller.attach(it)
                    it.setShowBuffering(if(full && slowBuffering) PlayerView.SHOW_BUFFERING_NEVER else PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    it.liveSource=isLive
                    it.onSpeedHint={ hint, locked -> speedHint=hint;lockedSpeed=locked }
                    it.verticalFeedback={ left,message ->
                        feedbackPosition=if(left) androidx.compose.ui.AbsoluteAlignment.CenterLeft else androidx.compose.ui.AbsoluteAlignment.CenterRight
                        gestureFeedback=message
                    }
                    it.onScrub={ preview -> scrub.value=preview }
                    it.seekRelative=controller::seekRelative
                    it.feedback={ feedbackPosition=Alignment.Center;gestureFeedback=it }
                    // AndroidView may survive an episode change: rebind the listener to
                    // this session's Compose state, just like the seek/feedback callbacks.
                    it.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility -> controlsVisible=visibility==android.view.View.VISIBLE })
                    controlsVisible=it.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_controller).isVisible
                    it.setControlGeometry(full && !pip, horizontalInset,
                        with(density) { when { pip || separateActions -> 0; full -> if(supportsPip) (IconTarget + ActionGap).roundToPx() else 0; else -> (PageGutter + quickWidth + ActionGap).roundToPx() } })
                    it.setFullscreenControlShading(showFullscreenHeader,full && !pip && controlsVisible && !episodesOpen)
                    it.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_settings)?.setOnClickListener { openOptions() }
                    playerView=it;it.useController=!pip
                },
                onReset=null,onRelease={it.cancelGestures();controller.detach(it);if(playerView===it) playerView=null},
                modifier=Modifier.fillMaxSize().padding(bottom=if(separateActions) IconTarget else 0.dp)
            )
            if(!pip && controlsVisible && (!full || supportsPip) && !episodesOpen) {
                Row(Modifier.align(Alignment.BottomEnd).then(if(full) fullscreenPadding.padding(end=PageGutter-IconFaceInset) else Modifier.padding(end=PageGutter)),
                    horizontalArrangement=Arrangement.spacedBy(ActionGap),verticalAlignment=Alignment.CenterVertically) {
                    val colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=Color.Transparent,contentColor=Color.White)
                    if(supportsPip) GlassIconButton(onClick=::enterPip,colors=colors) {
                        Icon(painterResource(R.drawable.ic_pip),"进入画中画")
                    }
                    if(!full) GlassIconButton(modifier=Modifier.testTag("fullscreenToggle"),colors=colors,
                        onClick={playerView?.cancelGestures();entryOrientation=orientation;rotatingBack=false;requestFull=true}) {
                        Icon(painterResource(R.drawable.ic_fullscreen_enter),"横屏全屏")
                    }
                }
            }
            if(full && !pip) ScrubIndicator(scrub,Modifier.align(Alignment.Center))
            if(full && !pip && lockedSpeed!=null && !episodesOpen && !optionsOpen && state.alternativeSession==null) {
                val top=if(showFullscreenHeader && (windowSize.width<760.dp || density.fontScale>1.3f) && !isLive) 110.dp else if(showFullscreenHeader) 58.dp else 8.dp
                val label=formatBoost(lockedSpeed!!)
                Box(Modifier.align(Alignment.TopEnd).then(fullscreenPadding).padding(top=top,end=PageGutter)
                    .sizeIn(minWidth=48.dp,minHeight=48.dp).clickable(role=Role.Button) { playerView?.unlockSpeed() }
                    .semantics { contentDescription="已锁定 $label，恢复原倍速" }.testTag("lockedSpeed"),
                    contentAlignment=Alignment.Center) {
                    Text(label,Modifier.background(Color.Black.copy(alpha=.72f),MaterialTheme.shapes.large).padding(horizontal=12.dp,vertical=5.dp),
                        color=Color.White,style=MaterialTheme.typography.labelLarge)
                }
            }
            if((gestureFeedback.isNotEmpty() || speedHint.isNotEmpty()) && !pip && scrub.value==null && !episodesOpen && !optionsOpen) {
                val msg=speedHint.ifEmpty { gestureFeedback }
                val position=if(speedHint.isNotEmpty()) Alignment.TopCenter else feedbackPosition
                val inset=if(position==Alignment.TopCenter) Modifier.padding(top=60.dp)
                    else if(position==Alignment.Center) Modifier.offset(y=(-64).dp) else Modifier.padding(horizontal=PageGutter)
                val announce=if(msg=="倍速已锁定" || msg=="已恢复原倍速") Modifier.semantics { liveRegion=LiveRegionMode.Polite } else Modifier
                Text(msg,color=Color.White,modifier=Modifier.align(position).then(fullscreenPadding).then(inset).then(announce)
                    .background(Color.Black.copy(alpha=.72f),MaterialTheme.shapes.large).padding(horizontal=16.dp,vertical=8.dp))
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
                        PlayerCollectionBrowser(playback,matchingDetail,state,model,{playerView?.cancelGestures(keepLockedSpeed=true);episodesOpen=false},Modifier.weight(1f))
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
            PlayerCollectionBrowser(playback,matchingDetail,state,model,{playerView?.cancelGestures(keepLockedSpeed=true);episodesOpen=false},Modifier.weight(1f))
        }
        if(showFullscreenHeader) {
            val usableWidth=windowSize.width-with(density) { (horizontalInset*2).toDp() }
            val oneRow = usableWidth >= 760.dp && density.fontScale <= 1.3f
            FullscreenHeader(
                title = fullscreenPlaybackTitle(playback.video.title, playback.episode.name, isLive),
                singleRow = oneRow,
                onExit = { playerView?.cancelGestures(); exitFullscreen() },
                onOptions = openOptions,
                modifier = Modifier.align(Alignment.TopCenter).then(fullscreenPadding),
            ) {
                if (!isLive) FullscreenEpisodeActions(
                    compact = !oneRow,
                    hasPrevious = previous != null,
                    hasNext = next != null,
                    hasEpisodes = hasEpisodes,
                    showSources = playback.video.source.kind == SourceKind.VOD && error == null && !slowBuffering,
                    onPrevious = stepPrevious,
                    onNext = stepNext,
                    onEpisodes = openEpisodes,
                    onSources = ::showAlternatives,
                )
            }
        }
        // Recovery must remain reachable even with hidden controls or no initialized player.
        if(full && !pip) {
            (error ?: if(slowBuffering) "缓冲较慢" else null)?.let { problem ->
                PlaybackNotice(problem, error==null, playback.video.source.name, isLive,
                    code=playerView?.player?.playerError?.errorCode,retry={error=null;controller.retry()}, alternatives=::showAlternatives,
                    modifier=Modifier.align(Alignment.BottomCenter).then(fullscreenPadding)
                        .padding(start=PageGutter,end=PageGutter,bottom=76.dp).heightIn(max=windowSize.height*.55f))
            }
        }
    }
    }
}
}

}

internal fun videoDisplayAspect(width:Int,height:Int,pixelRatio:Float):Float {
    if(width<=0 || height<=0 || !pixelRatio.isFinite() || pixelRatio<=0f) return 16f/9f
    return width.toFloat()/height*pixelRatio
}

internal fun pictureInPictureAspect(width:Int,height:Int,pixelRatio:Float):android.util.Rational {
    val aspect=videoDisplayAspect(width,height,pixelRatio)
    // Android accepts ratios from 1:2.39 through 2.39:1. The common 4:3, 16:9
    // and 9:16 formats can all be represented exactly with this denominator.
    return when {
        aspect<=100f/239f -> android.util.Rational(100,239)
        aspect>=2.39f -> android.util.Rational(239,100)
        else -> android.util.Rational(kotlin.math.round(aspect*90_000).toInt(),90_000)
    }
}

@Composable private fun PlaybackStep(label: String, enabled: Boolean, action: () -> Unit, modifier: Modifier) {
    GlassButton(onClick=action, enabled=enabled, modifier=modifier, shape=MaterialTheme.shapes.medium) {
        Text(label)
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
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val shortPanel=maxHeight<384.dp && LocalDensity.current.fontScale>1.3f
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
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
}

/** Browsing another season/source is isolated from the active Media3 session until an episode is chosen. */
@Composable private fun PlayerCollectionBrowser(current:Playback,detail:Detail?,state:PlayerUiState,model:AppModel,close:()->Unit,modifier:Modifier=Modifier) {
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
    val shown=browsing
    val selectedLine=shown?.lines?.getOrNull(lineIndex)
    val entries=selectedLine?.episodes.orEmpty()
    val progress=remember(chosen,state.saved.history,current) { episodeProgress(chosen,state.saved.history).toMutableMap().apply { if(chosen.probeKey()==current.video.probeKey() && current.duration>0) put(current.episode.url,current) } }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val shortPanel=maxHeight<384.dp && LocalDensity.current.fontScale>1.3f
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
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
}


@Composable private fun BufferingLabel(readHealth: () -> PlaybackHealth) {
    val status = readHealth()
    val prefix = if (status.buffering) "正在缓冲 ·" else "已缓冲"
    Text("$prefix ${status.bufferedSeconds} 秒", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun ScrubIndicator(state:State<ScrubPreview?>,modifier:Modifier) {
    val preview=state.value ?: return
    fun time(ms:Long)=android.text.format.DateUtils.formatElapsedTime(ms/1000)
    Surface(modifier.padding(horizontal=24.dp).width(280.dp).testTag("scrubPreview"),
        shape=MaterialTheme.shapes.large,color=Color(0xEE181D21),contentColor=Color.White) {
        Column(Modifier.padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text(time(preview.position),style=MaterialTheme.typography.titleMedium)
                Text(time(preview.duration),style=MaterialTheme.typography.labelMedium,color=Color.LightGray)
            }
            Text(if(preview.atOrigin) "松手取消" else {
                val delta=preview.position-preview.origin
                (if(delta<0) "−" else "+")+time(abs(delta))
            },style=MaterialTheme.typography.labelLarge,color=Color.White)
            LinearProgressIndicator(progress={preview.position.toFloat()/preview.duration},modifier=Modifier.fillMaxWidth(),
                color=Color.White,trackColor=Color.DarkGray)
        }
    }
}

@Composable private fun PlaybackNotice(problem:String,buffering:Boolean,source:String,live:Boolean,code:Int?,
    retry:()->Unit,alternatives:()->Unit,modifier:Modifier=Modifier) {
    Surface(modifier.widthIn(max=480.dp).fillMaxWidth().heightIn(max=240.dp).testTag("playbackError"),
        shape=MaterialTheme.shapes.large,color=Color(0xF21C2227),contentColor=Color.White) {
        BoxWithConstraints(Modifier.verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=8.dp)) {
            val label:@Composable (Modifier)->Unit={ area ->
                Row(area,verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    if(buffering) CircularProgressIndicator(Modifier.size(16.dp),color=Color.White,strokeWidth=2.dp)
                    Column {
                        Text(problem,Modifier.semantics { liveRegion=LiveRegionMode.Polite },style=MaterialTheme.typography.titleSmall)
                        Text(source,style=MaterialTheme.typography.bodySmall,color=Color(0xFFD6DADE),maxLines=1,overflow=TextOverflow.Ellipsis)
                        if(code!=null && !buffering) {
                            var expanded by remember(problem,code) { mutableStateOf(false) }
                            TextButton(onClick={expanded=!expanded}) {
                                Text(if(expanded) "错误码 $code" else "错误详情",color=Color.White)
                            }
                        }
                    }
                }
            }
            val actions:@Composable ()->Unit={
                TextButton(onClick=retry,colors=ButtonDefaults.textButtonColors(contentColor=Color.White)) {
                    Text(if(buffering || live) "重新连接" else "重试播放")
                }
                if(!live) FilledTonalButton(onClick=alternatives,colors=ButtonDefaults.filledTonalButtonColors(
                    containerColor=Color(0xFFE2E8EB),contentColor=Color(0xFF172126))) { Text("换源") }
            }
            if(maxWidth>=340.dp*LocalDensity.current.fontScale) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { label(Modifier.weight(1f));actions() }
            } else Column {
                label(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically) { actions() }
            }
        }
    }
}
