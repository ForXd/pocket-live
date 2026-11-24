// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.auto(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT))
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
        setContent {
            PocketTheme {
                Surface(Modifier.fillMaxSize()) { Pocket(viewModel()) }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun Pocket(model: AppModel) {
    val s by model.state.collectAsStateWithLifecycle()
    val imeVisible=WindowInsets.isImeVisible
    val compact=windowSizeDp().height.value<500 || WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)>0
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext
    DisposableEffect(app,model) {
        val callbacks = object : android.content.ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = Unit
            override fun onLowMemory() { model.trimMemory() }
            override fun onTrimMemory(level: Int) {
                // Hiding the window is routine. Keep the bounded poster cache warm for Back.
                if (level != android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) model.trimMemory()
            }
        }
        app.registerComponentCallbacks(callbacks)
        onDispose { app.unregisterComponentCallbacks(callbacks) }
    }
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle,model) {
        val observer=androidx.lifecycle.LifecycleEventObserver { _,event ->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_START) model.refreshSourceAvailability()
            if(event==androidx.lifecycle.Lifecycle.Event.ON_START && !BuildConfig.DEBUG) model.checkUpdates()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val homeState=rememberSaveableStateHolder()
    val historyState=rememberSaveableStateHolder()
    var updatesOpen by rememberSaveable { mutableStateOf(false) }
    var settingsPage by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val historySearch=rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var focusSearch by remember { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val liveTab=rememberSaveable { mutableStateOf(false) }
    var liveQuery by rememberSaveable { mutableStateOf("") }
    val recommendations=rememberSaveable { mutableStateOf(true) }
    var catalogQuery by rememberSaveable { mutableStateOf("") }
    var submittedQuery by rememberSaveable { mutableStateOf("") }
    val searchMode=searchOpen && submittedQuery.isNotEmpty()
    val inLive=liveTab.value && !searchMode
    val inCatalog=!liveTab.value && !recommendations.value && !searchMode
    val resultScroll=rememberSaveable(saver=HomeSearchScroll.Saver) { HomeSearchScroll() }
    val expansion = rememberSaveable(saver=PosterExpansion.Saver) { PosterExpansion() }
    fun search(text:String) {
        if(text.isBlank()) return
        resultScroll.reveal()
        query=text.take(100);submittedQuery=query;searchOpen=true;tab=0
        focusManager.clearFocus();keyboard?.hide();model.search(query)
    }
    fun searchRecommendation(title:String) {
        if(title.isBlank()) return
        model.closeRecommendation()
        expansion.selected=null
        search(title)
    }
    fun closeSearch() {
        resultScroll.reveal()
        if(searchMode) model.cancelSearch()
        if(inCatalog && s.catalogQuery.isNotEmpty()) {
            model.browseSources(s.catalogSourceKeys,s.catalogCategory,"",genres=s.catalogGenres)
        }
        searchOpen=false;focusSearch=false;query="";submittedQuery="";liveQuery="";catalogQuery=""
        focusManager.clearFocus();keyboard?.hide()
    }
    fun openSearch() {
        resultScroll.reveal()
        focusSearch=true
        searchOpen=true
    }
    // Do not put token-bearing input into saved-instance-state or screen titles.
    var url by remember { mutableStateOf("") }
    var exportOpen by rememberSaveable { mutableStateOf(false) }
    var addingSubscription by remember { mutableStateOf(false) }
    var jsonDialog by remember { mutableStateOf(false) }
    var jsonText by remember { mutableStateOf("") }
    var fileTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    val jsonPicker=rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        val target=fileTargetId
        fileTargetId=null
        if(uri!=null) {
            val existing=target?.let { id -> model.state.value.saved.subscriptions.find { it.id==id } }
            if(target!=null && existing==null) model.message("订阅已删除") else model.importJsonFile(uri,existing)
        }
    }
    fun chooseJson(existing:Subscription?=null) {
        fileTargetId=existing?.id
        try { jsonPicker.launch(arrayOf("*/*")) }
        catch(_:android.content.ActivityNotFoundException) { fileTargetId=null;model.message("未找到文件选择器，可使用粘贴 JSON") }
    }
    if(jsonDialog) GlassAlertDialog(dismissOnOutside=false,onDismissRequest={model.cancelImport("new");jsonDialog=false;jsonText=""},title={Text("粘贴 JSON")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("粘贴订阅配置，支持与链接导入相同的格式。最多 2 MiB。")
            OutlinedTextField(shape=MaterialTheme.shapes.medium,value=jsonText,onValueChange={ if(it.length<=JsonData.LIMIT) jsonText=it else model.message("JSON 内容超过 2 MiB 上限") },
                modifier=Modifier.fillMaxWidth().heightIn(max=280.dp).testTag("subscriptionJsonInput"),label={Text("JSON 内容")},minLines=5,maxLines=10,enabled="new" !in s.importing)
            if("new" in s.importing) LoadingStatus("正在导入…") else s.message?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
        }
    },confirmButton={GlassTextButton(enabled=jsonText.isNotBlank() && "new" !in s.importing,onClick={model.message(null);model.importJson(jsonText,onImported={jsonDialog=false;jsonText=""})}) { Text("导入") }},
        dismissButton={GlassTextButton(onClick={model.cancelImport("new");jsonDialog=false;jsonText=""}) { Text("取消") }})
    var deleteSubscriptionId by rememberSaveable { mutableStateOf<String?>(null) }
    var subscriptionId by rememberSaveable { mutableStateOf<String?>(null) }
    val activeSubscription = remember(subscriptionId, s.saved.subscriptions) {
        s.saved.subscriptions.find { it.id == subscriptionId }
    }
    val subscriptionListState = rememberLazyListState()
    val sourceListState = key(subscriptionId) { rememberLazyListState() }
    LaunchedEffect(s.loading, subscriptionId, activeSubscription == null) {
        if (!s.loading && activeSubscription == null) subscriptionId = null
    }
    fun openSubscriptions() {
        subscriptionId = null
        tab = 1
    }
    fun updateSubscription(sub: Subscription) {
        if (sub.isLocalJson) chooseJson(sub) else model.importSubscription(sub.url, sub)
    }
    var ruleSource by remember { mutableStateOf<Source?>(null) }
    var ruleText by remember { mutableStateOf("") }
    LaunchedEffect(tab,s.saved,s.playback?.sessionId) { if(tab == 3 && s.playback == null) model.refreshStorage() }
    LaunchedEffect(s.saved.subscriptions) {
        if(url.isNotBlank() && s.saved.subscriptions.any { it.url == validUrl(url, true) }) { url = "";addingSubscription=false }
    }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(s.message) { s.message?.let { snack.showSnackbar(it); model.message(null) } }
    LaunchedEffect(imeVisible) { if(imeVisible && searchOpen) resultScroll.reveal() }
    LaunchedEffect(tab) { if(tab==1) model.refreshSourceAvailability() }
    val selected = expansion.selected
    val recommendation = s.recommendPreview.takeUnless { searchMode }
    val cardVideo = recommendation ?: s.detailLoadingVideo ?: s.detail?.video
    val destinationKey = remember(cardVideo?.source?.key,cardVideo?.id) { cardVideo?.let { hash(it.probeKey()) } }
    val inline = selected != null && selected.destinationKey == destinationKey
    LaunchedEffect(cardVideo,s.playback) {
        if(cardVideo == null && s.playback == null) expansion.selected=null
    }
    val playback = s.playback
    // The same hierarchy supplies the destination preview for buttons and system gestures.
    val routes = buildList {
        add(when { liveTab.value -> "live"; !recommendations.value -> "catalog"; else -> "tab:0" })
        if(searchOpen) add("search")
        if(tab!=0) add("tab:$tab")
        if(tab==1 && activeSubscription!=null) add("subscription:${activeSubscription.id}")
        if(tab==3 && settingsPage!=null) add("settings:${settingsPage!!.name}")
        if(!inline && recommendation!=null) add("preview")
        if(!inline && !s.directPlayback && (s.detail!=null || s.detailLoading)) add("detail")
        if(playback!=null) add("playback")
    }
    CardRouteHost(routes, canCaptureParent=recommendation==null) {
    val motion=LocalCardReturn.current
    if(playback != null) {
        val playerState by remember { derivedStateOf(structuralEqualityPolicy()) { s.playerUiState() } }
        PlaybackScreen(playback, model,playerState)
        return@CardRouteHost
    }
    if(exportOpen) SourceExportSheet(model) { exportOpen=false }
    if(addingSubscription) HandleOnlySheet(onDismissRequest={model.cancelImport("new");addingSubscription=false}) {
        SheetSystemBars()
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            SheetHeading("添加订阅") { model.cancelImport("new");addingSubscription=false }
                            OutlinedTextField(url,{url=it.take(4096)},modifier=Modifier.fillMaxWidth().testTag("subscriptionInput"),label={Text("粘贴订阅链接")},singleLine=true,shape=MaterialTheme.shapes.medium,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri))
                            GlassButton(shape=MaterialTheme.shapes.medium,onClick={model.importSubscription(url)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=url.isNotBlank() && "new" !in s.importing) {
                                Icon(Icons.Default.Add,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("添加订阅")
                            }
                            GlassButton(shape=MaterialTheme.shapes.medium,onClick={chooseJson()},enabled="new" !in s.importing,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("选择 JSON 文件") }
                            GlassTextButton(onClick={model.message(null);jsonDialog=true},enabled="new" !in s.importing,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("粘贴 JSON") }
                            if("new" in s.importing) GlassTextButton(onClick={model.cancelImport("new")}) { Text("取消导入") }
                            Text("JSON 文件上限 2 MiB",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
    }
    if(appearanceOpen) AppearanceDialog { appearanceOpen=false }
    if(updatesOpen) UpdateDialog(s,model) { updatesOpen=false }
    CardBackHandler(!inline && (s.detail != null || s.detailLoading || searchOpen || tab!=0)) {
        when {
            s.detail!=null || s.detailLoading -> model.closeDetail()
            tab==1 && activeSubscription!=null -> subscriptionId=null
            tab==3 && settingsPage!=null -> settingsPage=null
            tab!=0 -> tab=0
            else -> closeSearch()
        }
    }
    val searchHeaderVisible=tab==0 && searchOpen && (inline || (s.detail==null && !s.detailLoading))
    val searchConnection=rememberSearchScrollConnection(resultScroll,searchHeaderVisible && !imeVisible && searchMode && s.results.isNotEmpty())
    val wide=windowSizeDp().width.value>=840
    if(!inline && recommendation!=null) { RecommendationPreview(s,model,::searchRecommendation);return@CardRouteHost }
    val destinations=listOf("首页","订阅","历史","设置")
    val navIcon:@Composable (Int)->Unit = { i -> Icon(painterResource(if(i==tab) listOf(R.drawable.ic_home_solid,R.drawable.ic_library_solid,R.drawable.ic_history_solid,R.drawable.ic_settings_solid)[i] else listOf(R.drawable.ic_home,R.drawable.ic_library,R.drawable.ic_history,R.drawable.ic_settings)[i]),null) }
    val showDock = !wide && !imeVisible && (inline || (s.detail==null && !s.detailLoading))
    CompositionLocalProvider(LocalPosterExpansion provides expansion) {
    Box(if(inline) Modifier.fillMaxSize().clearAndSetSemantics {} else Modifier.fillMaxSize()) {
    Scaffold(modifier=Modifier.fillMaxSize().nestedScroll(searchConnection),
        topBar = {
            if(searchHeaderVisible || (tab==0 && wide && searchMode)) {
                fun browseQuery(value:String) {
                    catalogQuery=value;focusManager.clearFocus();keyboard?.hide()
                    model.browseSources(s.catalogSourceKeys,s.catalogCategory,value.trim(),genres=s.catalogGenres)
                }
                CollapsingHomeSearch(resultScroll) {
                    SearchHeader(back={ motion.commit { closeSearch() } },
                        backLabel=when { inLive -> "返回直播"; inCatalog -> "返回分类"; else -> "返回推荐" }) {
                        HomeSearchBar(
                            query=when { inLive -> liveQuery; inCatalog -> catalogQuery; else -> query },
                            onQuery={ text ->
                                val value=text.take(100)
                                when { inLive -> liveQuery=value; inCatalog -> catalogQuery=value; else -> query=value }
                            },
                            searchMode=searchMode,
                            submit={
                                when {
                                    inLive -> { focusManager.clearFocus();keyboard?.hide() }
                                    inCatalog -> browseQuery(catalogQuery)
                                    else -> { model.recordSearch(query);search(query) }
                                }
                            },
                            clear={
                                when {
                                    inLive -> liveQuery=""
                                    inCatalog -> browseQuery("")
                                    else -> { query="";model.cancelSearch();submittedQuery="";focusSearch=true }
                                }
                            },
                            placeholder=when {
                                inLive -> "搜索当前来源的直播频道"
                                inCatalog -> if(s.catalogSourceKeys.isEmpty()) "搜索所有来源" else "搜索所选来源"
                                else -> "搜索影片、剧集或频道"
                            },
                            clearLabel=when { inLive -> "清空频道搜索"; inCatalog -> "清空分类关键词"; else -> "清除输入" },
                            allowEmpty=inCatalog || inLive,focusOnOpen=focusSearch,onFocused={focusSearch=false},
                            cancelSearch=if(searchMode && s.searching) model::cancelSearch else null)
                    }
                }
            }
            else Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars))
        },
        bottomBar = { if(showDock) Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=PageGutter,vertical=6.dp),
            contentAlignment=Alignment.Center) {
            Surface(modifier=Modifier.widthIn(max=280.dp).fillMaxWidth().testTag("mainNavigation"),shape=RoundedCornerShape(28.dp),
                color=MaterialTheme.colorScheme.surfaceContainerLow,shadowElevation=1.dp) {
                Row(Modifier.selectableGroup().padding(horizontal=4.dp,vertical=2.dp)) {
                    listOf(0,2,1,3).forEach { i ->
                        DockDestination(tab==i,destinations[i],{focusManager.clearFocus();keyboard?.hide();tab=i;model.closeDetail()},
                            Modifier.weight(1f).testTag("nav:$i"),icon={navIcon(i)})
                    }
                }
            }
        } },snackbarHost={SnackbarHost(snack)}
    ) { padding ->
        val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
        val viewport = if(showDock) PaddingValues(start=padding.calculateStartPadding(direction),end=padding.calculateEndPadding(direction),top=padding.calculateTopPadding()) else padding
        androidx.compose.runtime.CompositionLocalProvider(LocalDockInset provides if(showDock) padding.calculateBottomPadding() else 0.dp) {
        Row(Modifier.padding(viewport).consumeWindowInsets(padding).fillMaxSize().imePadding()) {
        if(wide) NavigationRail(containerColor=MaterialTheme.colorScheme.background,windowInsets=WindowInsets(0,0,0,0),modifier=Modifier.testTag("mainNavigation")) {
            Spacer(Modifier.height(24.dp))
            listOf(0,2,1,3).forEach { i -> PocketNavigationItem(tab==i,destinations[i],{focusManager.clearFocus();keyboard?.hide();tab=i;model.closeDetail()},
                Modifier.padding(horizontal=8.dp,vertical=4.dp).width(64.dp).testTag("nav:$i"),icon={navIcon(i)}) }
        }
        Box(Modifier.weight(1f).fillMaxSize()) {
            if(s.loadFailed) { Text("本地数据损坏，已停止读写以保留原文件。可在系统应用设置清除数据后重新导入。",Modifier.padding(24.dp)); return@Box }
            if(s.loading) { LoadingStatus("正在读取本地数据…",Modifier.align(Alignment.Center)); return@Box }
            val detail = s.detail
            val loadingVideo = s.detailLoadingVideo
            if(!inline && (detail != null || loadingVideo != null)) {
                val detailPane: @Composable () -> Unit = {
                    if(detail != null) DetailScreen(detail,model)
                    else if(loadingVideo != null) PendingDetail(loadingVideo,model) { motion.commit(model::closeDetail) }
                }
                if(wide && searchMode) Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(.38f)) { SearchResultsScreen(s,model,submittedQuery,compact,::closeSearch,::openSubscriptions) }
                    Box(Modifier.weight(.62f)) { detailPane() }
                } else detailPane()
            } else when(tab) {
                0 -> homeState.SaveableStateProvider(if(searchOpen) "search" else "recommendations") {
                    when {
                        searchMode -> key(submittedQuery) { SearchResultsScreen(s,model,submittedQuery,compact,::closeSearch,::openSubscriptions,onTop=resultScroll::reveal) }
                        searchOpen && !inLive && !inCatalog -> Box(Modifier.fillMaxSize().testTag("searchLanding")) {
                            if(query.isBlank() && s.saved.searchHistoryEnabled && s.saved.recentSearches.isNotEmpty()) {
                                androidx.compose.foundation.lazy.LazyRow(contentPadding=PaddingValues(horizontal=PageGutter),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                                    item { Text("最近搜索",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                                    items(s.saved.recentSearches,key={it}) { term ->
                                        SuggestionChip(onClick={model.recordSearch(term);search(term)},label={Text(term,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)},modifier=Modifier.widthIn(max=200.dp).testTag("recentSearch:$term"))
                                    }
                                }
                            }
                        }
                        else -> DiscoverScreen(s,model,::openSubscriptions,::search,recommendations,catalogQuery,liveTab,liveQuery,
                            onOpenSearch=::openSearch,showHeader=!searchOpen)
                    }
                }
                3 -> SettingsScreen(s,model,settingsPage,{settingsPage=it},{appearanceOpen=true},{updatesOpen=true;model.checkUpdates(force=true)},
                    { source -> ruleSource=source;ruleText=s.saved.adRules[source.key].orEmpty().joinToString("\n") })
                1 -> if (activeSubscription == null) {
                    SubscriptionList(s, subscriptionListState, onOpen = { subscriptionId = it.id },
                        onAdd = { addingSubscription = true }, onExport = { exportOpen = true },
                        onUpdate = ::updateSubscription, onCancel = model::cancelImport, onDelete = { deleteSubscriptionId = it.id })
                } else {
                    SubscriptionDetail(activeSubscription, s, sourceListState,
                        onBack = { motion.commit { subscriptionId = null } },
                        onUpdate = { updateSubscription(activeSubscription) }, onCancel = { model.cancelImport(activeSubscription.id) },
                        onDelete = { deleteSubscriptionId = activeSubscription.id }, onToggle = model::toggle)
                }
                2 -> historyState.SaveableStateProvider("history") { HistoryScreen(s.saved.history,model::resume,model::removeHistoryGroup,model::clearHistory,model,historySearch) }
            }
        }
    }
    }
    } // Bounded content
    } // Browsing surface
    if(inline && selected!=null) key(selected) {
        val isRecommendation=recommendation!=null
        val detail=s.detail
        val loadingVideo=s.detailLoadingVideo
        ExpandedPosterCard(selected,onClose={
            if(isRecommendation) model.closeRecommendation() else model.closeDetail()
        }) {
            when {
                isRecommendation -> RecommendationPreview(s,model,::searchRecommendation)
                detail!=null -> DetailScreen(detail,model)
                loadingVideo!=null -> PendingDetail(loadingVideo,model,checkNotNull(LocalPosterClose.current))
            }
        }
    }
    } // Poster expansion
    ruleSource?.let { source -> GlassAlertDialog(dismissOnOutside=false,onDismissRequest={ruleSource=null},title={Text("当前源的广告精确路径")},text={Column(Modifier.verticalScroll(rememberScrollState())) { Text("每行一个完整路径，不填域名或查询参数。补充自动识别规则；请仅添加你确认的广告路径。")
        OutlinedTextField(shape=MaterialTheme.shapes.medium,value=ruleText,onValueChange={ruleText=it.take(16416)},minLines=3,maxLines=7) }},confirmButton={GlassTextButton(onClick={model.adPaths(source,ruleText);ruleSource=null}) { Text("保存") }},dismissButton={GlassTextButton(onClick={ruleSource=null}) { Text("取消") }}) }
    deleteSubscriptionId?.let { id ->
        val target = s.saved.subscriptions.find { it.id == id }
        if (target != null) {
            GlassAlertDialog(onDismissRequest = { deleteSubscriptionId = null }, title = { Text("删除此订阅？") },
                text = { Text("将移除「${target.name}」及其来源。") },
                confirmButton = { GlassTextButton(onClick = { model.delete(id); deleteSubscriptionId = null }) { Text("删除") } },
                dismissButton = { GlassTextButton(onClick = { deleteSubscriptionId = null }) { Text("取消") } })
        } else if (!s.loading) {
            LaunchedEffect(id) { deleteSubscriptionId = null }
        }
    }
    } // CardRouteHost
}
