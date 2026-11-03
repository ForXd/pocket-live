// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
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
    val compact=LocalConfiguration.current.screenHeightDp<500 || WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)>0
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext
    DisposableEffect(app) {
        val callbacks = object : android.content.ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = Unit
            override fun onLowMemory() { model.trimMemory() }
            override fun onTrimMemory(level: Int) { if(level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) model.trimMemory() }
        }
        app.registerComponentCallbacks(callbacks)
        onDispose { app.unregisterComponentCallbacks(callbacks) }
    }
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle,model) {
        val observer=androidx.lifecycle.LifecycleEventObserver { _,event ->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_START && !BuildConfig.DEBUG) model.checkUpdates()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val homeState=rememberSaveableStateHolder()
    var updatesOpen by rememberSaveable { mutableStateOf(false) }
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchMode by rememberSaveable { mutableStateOf(false) }
    var submittedQuery by rememberSaveable { mutableStateOf("") }
    fun search(text:String) {
        if(text.isBlank()) return
        query=text.take(100);submittedQuery=query;searchMode=true;tab=0
        focusManager.clearFocus();keyboard?.hide();model.search(query)
    }
    fun showRecommendations() {
        model.cancelSearch();searchMode=false;query="";submittedQuery=""
        focusManager.clearFocus();keyboard?.hide()
    }
    // Do not put token-bearing input into saved-instance-state or screen titles.
    var url by remember { mutableStateOf("") }
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
    if(jsonDialog) AlertDialog(onDismissRequest={model.cancelImport("new");jsonDialog=false;jsonText=""},title={Text("粘贴 JSON")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("粘贴订阅配置，支持与链接导入相同的格式。最多 2 MiB。")
            OutlinedTextField(jsonText,{ if(it.length<=JsonData.LIMIT) jsonText=it else model.message("JSON 内容超过 2 MiB 上限") },
                modifier=Modifier.fillMaxWidth().heightIn(max=280.dp).testTag("subscriptionJsonInput"),label={Text("JSON 内容")},minLines=5,maxLines=10,enabled="new" !in s.importing)
            if("new" in s.importing) Text("正在导入…") else s.message?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
        }
    },confirmButton={TextButton(enabled=jsonText.isNotBlank() && "new" !in s.importing,onClick={model.message(null);model.importJson(jsonText,onImported={jsonDialog=false;jsonText=""})}) { Text("导入") }},
        dismissButton={TextButton(onClick={model.cancelImport("new");jsonDialog=false;jsonText=""}) { Text("取消") }})
    var delete by remember { mutableStateOf<Subscription?>(null) }
    var expandedSubscription by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(s.loading,s.saved.subscriptions.map { it.id }) {
        if(!s.loading && s.saved.subscriptions.none { it.id==expandedSubscription }) expandedSubscription=null
    }
    var ruleSource by remember { mutableStateOf<Source?>(null) }
    var ruleText by remember { mutableStateOf("") }
    LaunchedEffect(tab,s.saved,s.playback?.sessionId) { if(tab == 3 && s.playback == null) model.refreshStorage() }
    LaunchedEffect(s.saved.subscriptions) {
        if(url.isNotBlank() && s.saved.subscriptions.any { it.url == validUrl(url, true) }) { url = "";addingSubscription=false }
    }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(s.message) { s.message?.let { snack.showSnackbar(it); model.message(null) } }
    val playback = s.playback
    if(playback != null) { PlaybackScreen(playback, model); return }
    if(appearanceOpen) AppearanceDialog { appearanceOpen=false }
    if(updatesOpen) UpdateDialog(s,model) { updatesOpen=false }
    BackHandler(s.detail != null || s.detailLoading || searchMode || tab!=0) {
        when {
            s.detail!=null || s.detailLoading -> model.closeDetail()
            tab!=0 -> tab=0
            else -> showRecommendations()
        }
    }
    val destinations=listOf("首页","订阅","历史","设置")
    val navIcon:@Composable (Int)->Unit = { i -> if(i==3) Icon(Icons.Default.Settings,null) else Icon(painterResource(listOf(R.drawable.ic_search,R.drawable.ic_library,R.drawable.ic_history)[i]),null) }
    Scaffold(modifier=Modifier.fillMaxSize(),
        topBar = {
            if(tab==0 && s.detail==null && !s.detailLoading) HomeSearchBar(query,{query=it.take(100)},searchMode,{search(query)},::showRecommendations)
            else TopAppBar(colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background),title={
                Text(if(s.detail!=null || s.detailLoading) "详情与选集" else when(tab) { 1 -> "订阅管理";2 -> "观看历史";else -> "设置" },style=MaterialTheme.typography.titleLarge)
            },navigationIcon={if(s.detail!=null || s.detailLoading) IconButton(onClick=model::closeDetail) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回") }})
        },
        bottomBar = { NavigationBar(containerColor=MaterialTheme.colorScheme.surfaceContainer,tonalElevation=0.dp,modifier=Modifier.testTag("mainNavigation")) {
            destinations.forEachIndexed { i,title ->
                NavigationBarItem(selected=tab==i,onClick={focusManager.clearFocus();keyboard?.hide();tab=i;model.closeDetail()},
                    modifier=Modifier.testTag("nav:$i"),icon={navIcon(i)},label={Text(title)})
            }
        } },snackbarHost={SnackbarHost(snack)}
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().imePadding(),contentAlignment=Alignment.TopCenter) {
        val destination=when {
            s.detailLoading -> "detailLoading"
            s.detail!=null -> "detail"
            tab==0 && searchMode -> "search"
            else -> "tab:$tab"
        }
        Box(Modifier.widthIn(max=1100.dp).fillMaxSize().destinationArrival(destination)) {
            if(s.loadFailed) { Text("本地数据损坏，已停止读写以保留原文件。可在系统应用设置清除数据后重新导入。",Modifier.padding(24.dp)); return@Box }
            if(s.loading) { Column(Modifier.padding(24.dp)) { CircularProgressIndicator(); Text("正在读取本地数据…") }; return@Box }
            if(s.detailLoading) { Column(Modifier.padding(24.dp)) { CircularProgressIndicator(); TextButton(onClick=model::closeDetail) { Text("取消详情请求") } }; return@Box }
            val detail = s.detail
            if(detail != null) {
                DetailScreen(detail,model) { model.closeDetail();search(detail.video.title) }
            } else when(tab) {
                0 -> homeState.SaveableStateProvider(if(searchMode) "results" else "recommendations") {
                    if(searchMode) key(submittedQuery) { SearchResultsScreen(s,model,submittedQuery,compact,::showRecommendations,{tab=1}) }
                    else DiscoverScreen(s,model,{tab=1},::search)
                }
                3 -> SettingsScreen(s,model,{appearanceOpen=true},{updatesOpen=true;model.checkUpdates()},
                    { source -> ruleSource=source;ruleText=s.saved.adRules[source.key].orEmpty().joinToString("\n") })
                1 -> LazyColumn(modifier=Modifier.fillMaxSize().testTag("subscriptionsList"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    item { Row(verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text("管理订阅",style=MaterialTheme.typography.headlineSmall)
                            Text("${s.saved.subscriptions.size} 个订阅 · ${s.saved.subscriptions.sumOf { it.sources.size }} 个来源",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if(s.saved.subscriptions.isNotEmpty()) FilledTonalIconButton(onClick={addingSubscription=!addingSubscription},modifier=Modifier.size(48.dp)) { Icon(Icons.Default.Add,if(addingSubscription) "收起添加订阅" else "添加订阅") }
                    } }
                    if(s.saved.subscriptions.isEmpty() || addingSubscription || url.isNotEmpty()) item { Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(url,{url=it.take(4096)},modifier=Modifier.fillMaxWidth().testTag("subscriptionInput"),label={Text("粘贴订阅链接")},singleLine=true,shape=MaterialTheme.shapes.medium,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri))
                            Button(shape=MaterialTheme.shapes.medium,onClick={model.importSubscription(url)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=url.isNotBlank() && "new" !in s.importing) {
                                Icon(Icons.Default.Add,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("添加订阅")
                            }
                            OutlinedButton(onClick={chooseJson()},enabled="new" !in s.importing,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("选择 JSON 文件") }
                            TextButton(onClick={model.message(null);jsonDialog=true},enabled="new" !in s.importing,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("粘贴 JSON") }
                            if("new" in s.importing) TextButton(onClick={model.cancelImport("new")}) { Text("取消导入") }
                            Text("支持 LibreTV JSON、接口目录和 TVBOX 直连源。文件使用 UTF-8，最多 2 MiB；本地订阅可重新选择文件更新。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } }
                    if(s.saved.subscriptions.isEmpty()) item { EmptyPanel("还没有订阅","添加订阅后，在首页搜索或浏览内容。",R.drawable.ic_library) }
                    s.saved.subscriptions.forEach { sub ->
                        item(key="subscription:${sub.id}") {
                            SubscriptionHeader(sub,expandedSubscription==sub.id,sub.sources.count { it.key !in s.saved.disabled },sub.id in s.importing,
                                onExpand={expandedSubscription=if(expandedSubscription==sub.id) null else sub.id},
                                onUpdate={if(sub.isLocalJson) chooseJson(sub) else model.importSubscription(sub.url,sub)},onCancel={model.cancelImport(sub.id)},onDelete={delete=sub})
                        }
                        // Keep source rows lazy: even a 300-source subscription composes only the viewport.
                        if(expandedSubscription==sub.id) items(sub.sources,key={"source:${sub.id}:${it.key}"}) { source ->
                            SubscriptionSourceRow(source,source.key !in s.saved.disabled,onToggle={model.toggle(source)})
                        }
                    }

                }
                2 -> HistoryScreen(s.saved.history,model::resume,model::removeHistoryGroup,model::clearHistory,model)
            }
        }
    }
    } // Bounded content
    ruleSource?.let { source -> AlertDialog(onDismissRequest={ruleSource=null},title={Text("当前源的广告精确路径")},text={Column { Text("每行一个完整路径，不填域名或查询参数。补充自动识别规则；请仅添加你确认的广告路径。")
        OutlinedTextField(ruleText,{ruleText=it.take(16416)},minLines=3,maxLines=7) }},confirmButton={TextButton(onClick={model.adPaths(source,ruleText);ruleSource=null}) { Text("保存") }},dismissButton={TextButton(onClick={ruleSource=null}) { Text("取消") }}) }
    delete?.let { sub -> AlertDialog(onDismissRequest={delete=null},title={Text("删除此订阅？")},text={Text("移除该订阅的源。其他订阅和历史记录保留。")},confirmButton={TextButton(onClick={model.delete(sub.id);delete=null}){Text("删除")}},dismissButton={TextButton(onClick={delete=null}){Text("取消")}}) }
}
