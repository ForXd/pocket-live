// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.tween
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
    val rail=LocalConfiguration.current.screenWidthDp>=600 && LocalConfiguration.current.screenHeightDp>=480
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
    var updatesOpen by rememberSaveable { mutableStateOf(false) }
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    // Do not put token-bearing input into saved-instance-state or screen titles.
    var url by remember { mutableStateOf("") }
    var addingSubscription by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<Subscription?>(null) }
    var expandedSubscription by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(s.loading,s.saved.subscriptions.map { it.id }) {
        if(!s.loading && s.saved.subscriptions.none { it.id==expandedSubscription }) expandedSubscription=null
    }
    var ruleSource by remember { mutableStateOf<Source?>(null) }
    var ruleText by remember { mutableStateOf("") }
    LaunchedEffect(tab,s.saved,s.playback?.sessionId) { if((tab == 1 || tab == 2) && s.playback == null) model.refreshStorage() }
    LaunchedEffect(s.saved.subscriptions) {
        if(url.isNotBlank() && s.saved.subscriptions.any { it.url == validUrl(url, true) }) { url = "";addingSubscription=false }
    }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(s.message) { s.message?.let { snack.showSnackbar(it); model.message(null) } }
    val playback = s.playback
    if(playback != null) { PlaybackScreen(playback, model); return }
    if(appearanceOpen) AppearanceDialog { appearanceOpen=false }
    if(updatesOpen) UpdateDialog(s,model) { updatesOpen=false }
    BackHandler(s.detail != null || s.detailLoading) { model.closeDetail() }
    val destinations=listOf("搜索","订阅","历史","发现")
    val navIcon:@Composable (Int)->Unit = { i -> if(i==3) Icon(Icons.Default.Star,null) else Icon(painterResource(listOf(R.drawable.ic_search,R.drawable.ic_library,R.drawable.ic_history)[i]),null) }
    Row(Modifier.fillMaxSize()) {
    if(rail) NavigationRail(modifier=Modifier.fillMaxHeight().testTag("navigationRail"),containerColor=MaterialTheme.colorScheme.surfaceContainerLow) {
        Spacer(Modifier.height(16.dp))
        destinations.forEachIndexed { i,title -> NavigationRailItem(selected=tab==i,onClick={tab=i;model.closeDetail()},icon={navIcon(i)},label={Text(title)}) }
    }
    Scaffold(modifier=Modifier.weight(1f),
        topBar = { TopAppBar(colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background),title = {
            if(s.detail!=null) Text("详情与选集",style=MaterialTheme.typography.titleMedium)
            else Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Image(painterResource(R.drawable.pocket_logo),null,Modifier.size(40.dp))
                Text("Pocket Live",style=MaterialTheme.typography.titleMedium)
            }
        }, navigationIcon={ if(s.detail!=null || s.detailLoading) IconButton(onClick=model::closeDetail) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回") } }, actions = {
            IconButton(onClick={appearanceOpen=true},modifier=Modifier.size(48.dp)) { Icon(Icons.Default.Settings,"外观设置") }
            TextButton(onClick={updatesOpen=true;model.checkUpdates()}) { Text(if(s.appRelease!=null) "有新版" else "更新") }
        }) },
        bottomBar = { if(!rail) NavigationBar(containerColor=MaterialTheme.colorScheme.surfaceContainer,tonalElevation=0.dp) {
            listOf("搜索","订阅","历史","发现").forEachIndexed { i, title ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i; model.closeDetail() }, icon = { if(i==3) Icon(Icons.Default.Star,contentDescription=null) else Icon(painterResource(listOf(R.drawable.ic_search,R.drawable.ic_library,R.drawable.ic_history)[i]),contentDescription=null) }, label = { Text(title) })
            }
        } }, snackbarHost = { SnackbarHost(snack) }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().imePadding(),contentAlignment=Alignment.TopCenter) {
        Box(Modifier.widthIn(max=1100.dp).fillMaxSize()) {
            if(s.loadFailed) { Text("本地数据损坏，已停止读写以保留原文件。可在系统应用设置清除数据后重新导入。",Modifier.padding(24.dp)); return@Box }
            if(s.loading) { Column(Modifier.padding(24.dp)) { CircularProgressIndicator(); Text("正在读取本地数据…") }; return@Box }
            if(s.detailLoading) { Column(Modifier.padding(24.dp)) { CircularProgressIndicator(); TextButton(onClick=model::closeDetail) { Text("取消详情请求") } }; return@Box }
            val detail = s.detail
            if(detail != null) {
                DetailScreen(detail,model) { query=detail.video.title;model.closeDetail();tab=0;model.search(query) }
            } else when(tab) {
                0 -> Column(Modifier.fillMaxSize().padding(horizontal=16.dp)) {
                    if(!compact && s.results.isEmpty()) {
                        Text("搜索片库",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.padding(top=16.dp))
                        Text("在你的订阅中寻找下一部好片",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    TextField(query,{query=it.take(100)},modifier=Modifier.fillMaxWidth().testTag("searchInput").semantics { contentDescription="影片或频道名称" },singleLine=true,
                        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={if(query.isNotBlank()) { focusManager.clearFocus();keyboard?.hide();model.search(query) }}),
                        placeholder={Text("影片或频道名称")},shape=RoundedCornerShape(28.dp),
                        colors=TextFieldDefaults.colors(focusedIndicatorColor=androidx.compose.ui.graphics.Color.Transparent,unfocusedIndicatorColor=androidx.compose.ui.graphics.Color.Transparent),
                        leadingIcon={Icon(Icons.Default.Search,null)},trailingIcon={
                            FilledIconButton(onClick={focusManager.clearFocus();keyboard?.hide();model.search(query)},enabled=query.isNotBlank(),modifier=Modifier.padding(4.dp).size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowForward,"开始搜索") }
                        })
                    if(s.searching) TextButton(onClick=model::cancelSearch) { Text("取消搜索") }
                    if(s.searching) LinearProgressIndicator(progress={if(s.total==0) 0f else s.completed.toFloat()/s.total},modifier=Modifier.fillMaxWidth().padding(vertical=8.dp))
                    if(s.results.isNotEmpty()) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                        Text("可用资源",style=MaterialTheme.typography.titleMedium)
                        TextButton(onClick=model::sortByQuality) { Text("高清优先 ↓") }
                    }
                    if(s.total > 0) Text("已完成 ${s.completed}/${s.total} 个源 · ${s.failures} 个失败 · ${s.results.size} 条结果（最多 1000）",style=MaterialTheme.typography.bodySmall)
                    if(s.results.isEmpty() && !compact) EmptyPanel(if(s.searching) "正在寻找内容" else if(s.total>0) "暂未找到结果" else "你的片库，由你定义",
                        if(s.searching) "结果会陆续显示，你可以随时取消。" else if(s.failures>0) "部分片源未响应，可换个关键词或检查订阅。" else "在订阅页添加来源，即可开始搜索。",R.drawable.ic_search)
                    LazyVerticalGrid(columns=GridCells.Adaptive(320.dp),contentPadding=PaddingValues(top=12.dp,bottom=16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.weight(1f).pointerInput(model) {
                        awaitPointerEventScope { while(true) { if(awaitPointerEvent().type==PointerEventType.Press) model.pinSearchOrder() } }
                    }) {
                        items(s.results,key={it.source.key+":"+it.id}) { v ->
                            Card(onClick={model.open(v)},colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer),shape=MaterialTheme.shapes.large,modifier=Modifier.fillMaxWidth().animateItem(fadeInSpec=tween(140),placementSpec=null,fadeOutSpec=null)) {
                                Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                                    Poster(v,model,Modifier.size(76.dp,110.dp))
                                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                        Text(v.title,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                                        Text("${v.source.name} · ${v.subtitle}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                                        QualityBadge(v.quality,lastPlayback=true)
                                    }
                                }
                            }
                        }
                    }
                }
                3 -> DiscoverScreen(s,model,{tab=1}) { title -> query=title;tab=0;model.search(title) }
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
                            if("new" in s.importing) TextButton(onClick={model.cancelImport("new")}) { Text("取消导入") }
                            Text("支持 LibreTV JSON、接口目录和 TVBOX 直连源。链接仅存本机。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } }
                    if(s.saved.subscriptions.isEmpty()) item { EmptyPanel("还没有订阅","添加订阅后，在搜索或发现页浏览内容。",R.drawable.ic_library) }
                    s.saved.subscriptions.forEach { sub ->
                        item(key="subscription:${sub.id}") {
                            SubscriptionHeader(sub,expandedSubscription==sub.id,sub.sources.count { it.key !in s.saved.disabled },sub.id in s.importing,
                                onExpand={expandedSubscription=if(expandedSubscription==sub.id) null else sub.id},
                                onUpdate={model.importSubscription(sub.url,sub)},onCancel={model.cancelImport(sub.id)},onDelete={delete=sub})
                        }
                        // Keep source rows lazy: even a 300-source subscription composes only the viewport.
                        if(expandedSubscription==sub.id) items(sub.sources,key={"source:${sub.id}:${it.key}"}) { source ->
                            SubscriptionSourceRow(source,source.key !in s.saved.disabled,onToggle={model.toggle(source)},onRules={ruleSource=source;ruleText=s.saved.adRules[source.key].orEmpty().joinToString("\n")})
                        }
                    }
                    item(key="networkSettings") { SettingsGroup("播放与网络","HTTP 访问 · 广告过滤") {
                        SettingSwitch("允许未加密 HTTP","可能被监听或篡改，仅用于可信源",s.saved.allowHttp,model::allowHttp)
                        HorizontalDivider()
                        SettingSwitch("自动 HLS 广告过滤","默认关闭。可能误判；复杂流保持原样，下次播放生效。",s.saved.adFilter,model::filterAds)
                        Text("当前仅访问公网地址；局域网、脚本、XML 与多仓配置不受支持。订阅 2 MiB / 20 个，总源数 300。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                    item(key="storageSettings") { SettingsGroup("存储与缓存","50 MB 应用文件预算 · 保留配置清理缓存") {
                        s.storageUsage?.let { u ->
                            Text("用户数据：${u.userDataBytes/1024} KiB",style=MaterialTheme.typography.bodyMedium)
                            Text("响应缓存：${u.responseBytes/1024} KiB / 8 MiB · ${u.entries}/64 条",style=MaterialTheme.typography.bodyMedium)
                            Text("封面缓存：${u.posterBytes/1024} KiB / 20 MB",style=MaterialTheme.typography.bodyMedium)
                            Text("响应内存：${u.memoryBytes/1024} KiB / 2 MiB",style=MaterialTheme.typography.bodyMedium)
                        }
                        Text("总预算含临时文件与余量。响应60秒、封面24小时过期；视频不落盘。已安装APK和系统缓存另计；下载中的更新包计入预算。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick={model.clearCache()},modifier=Modifier.heightIn(min=48.dp)) { Text("清缓存（保留配置）") }
                            TextButton(onClick=model::refreshStorage,modifier=Modifier.heightIn(min=48.dp)) { Text("刷新用量") }
                        }
                    } }
                    item { Text("Pocket Live · AGPL-3.0-or-later\n参考 LibreTV deb72fa01f；完整源码和许可证随项目提供。\nEPG 字段保留，当前不提供节目表界面。",style=MaterialTheme.typography.bodySmall) }
                }
                2 -> HistoryScreen(s.saved.history,model::resume,model::removeHistoryGroup,model::clearHistory)
            }
        }
    }
    } // Bounded content
    } // Navigation rail and content
    ruleSource?.let { source -> AlertDialog(onDismissRequest={ruleSource=null},title={Text("当前源的广告精确路径")},text={Column { Text("每行一个完整路径，不填域名或查询参数。补充自动识别规则；请仅添加你确认的广告路径。")
        OutlinedTextField(ruleText,{ruleText=it.take(16416)},minLines=3,maxLines=7) }},confirmButton={TextButton(onClick={model.adPaths(source,ruleText);ruleSource=null}) { Text("保存") }},dismissButton={TextButton(onClick={ruleSource=null}) { Text("取消") }}) }
    delete?.let { sub -> AlertDialog(onDismissRequest={delete=null},title={Text("删除此订阅？")},text={Text("移除该订阅的源。其他订阅和历史记录保留。")},confirmButton={TextButton(onClick={model.delete(sub.id);delete=null}){Text("删除")}},dismissButton={TextButton(onClick={delete=null}){Text("取消")}}) }
}
