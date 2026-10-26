// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Add
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
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(0xff101918.toInt()))
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
    var updatesOpen by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    // Do not put token-bearing input into saved-instance-state or screen titles.
    var url by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf<Subscription?>(null) }
    var ruleSource by remember { mutableStateOf<Source?>(null) }
    var ruleText by remember { mutableStateOf("") }
    LaunchedEffect(tab,s.saved,s.playback?.sessionId) { if((tab == 1 || tab == 2) && s.playback == null) model.refreshStorage() }
    LaunchedEffect(s.saved.subscriptions) {
        if(url.isNotBlank() && s.saved.subscriptions.any { it.url == validUrl(url, true) }) url = ""
    }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(s.message) { s.message?.let { snack.showSnackbar(it); model.message(null) } }
    val playback = s.playback
    if(playback != null) { PlaybackScreen(playback, model); return }
    if(updatesOpen) UpdateDialog(s,model) { updatesOpen=false }
    BackHandler(s.detail != null || s.detailLoading) { model.closeDetail() }
    Scaffold(
        topBar = { TopAppBar(colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background),title = { Text(if(s.detail != null) "详情与选集" else "Pocket Live") }, actions = {
            TextButton(onClick={updatesOpen=true;model.checkUpdates()}) { Text(if(s.appRelease!=null) "有新版" else "更新") }
            if(s.detail != null || s.detailLoading) TextButton(onClick = model::closeDetail) { Text("返回") }
        }) },
        bottomBar = { NavigationBar(containerColor=MaterialTheme.colorScheme.surfaceContainer) {
            listOf("搜索","订阅","历史","发现").forEachIndexed { i, title ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i; model.closeDetail() }, icon = { if(i==3) Icon(Icons.Default.Star,contentDescription=null) else Icon(painterResource(listOf(R.drawable.ic_search,R.drawable.ic_library,R.drawable.ic_history)[i]),contentDescription=null) }, label = { Text(title) })
            }
        } }, snackbarHost = { SnackbarHost(snack) }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if(s.loadFailed) { Text("本地数据损坏，已停止读写以保留原文件。可在系统应用设置清除数据后重新导入。",Modifier.padding(24.dp)); return@Box }
            if(s.loading) { Column(Modifier.padding(24.dp)) { CircularProgressIndicator(); Text("正在读取本地数据…") }; return@Box }
            if(s.detailLoading) { Column(Modifier.padding(24.dp)) { CircularProgressIndicator(); TextButton(onClick=model::closeDetail) { Text("取消详情请求") } }; return@Box }
            val detail = s.detail
            if(detail != null) {
                LazyColumn(Modifier.fillMaxSize(),contentPadding = PaddingValues(16.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text(detail.video.source.name,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                        Text(detail.video.title,style=MaterialTheme.typography.titleLarge)
                        Poster(detail.video,model)
                        QualityBadge(detail.video.quality,lastPlayback=true)
                        if(detail.description.isNotBlank()) Text(detail.description,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                    item { OutlinedButton(onClick={ query=detail.video.title; model.closeDetail(); tab=0; model.search(query) }) { Text("搜索同名 · 换源") } }
                    detail.lines.forEach { line ->
                        item { Text("${line.name} · ${line.episodes.size} 集",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=12.dp)) }
                        items(line.episodes) { episode -> OutlinedCard(onClick={model.play(detail.video,episode)},modifier=Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(14.dp).heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                Icon(painterResource(R.drawable.ic_play),null,tint=MaterialTheme.colorScheme.primary)
                                Text(episode.name,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                                QualityBadge(episode.quality)
                            }
                        } }
                    }
                    item { Text("网页解析、脚本源和 DRM 解锁不受支持。", style=MaterialTheme.typography.bodySmall) }
                }
            } else when(tab) {
                0 -> Column(Modifier.fillMaxSize().padding(horizontal=16.dp)) {
                    if(!compact) {
                        Text("搜你想看的", style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(top=12.dp))
                        Text("聚合你的片源，找到更清晰的版本",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp))
                    }
                    Spacer(Modifier.height(if(compact) 8.dp else 16.dp))
                    OutlinedTextField(query,{query=it.take(100)},modifier=Modifier.fillMaxWidth().testTag("searchInput"),singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={focusManager.clearFocus();keyboard?.hide();model.search(query)}),label={Text("影片或频道名称")},shape=MaterialTheme.shapes.large,leadingIcon={Icon(painterResource(R.drawable.ic_search),null)})
                    Row(Modifier.padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(modifier=Modifier.weight(1f).heightIn(min=48.dp),onClick={focusManager.clearFocus(); keyboard?.hide(); model.search(query)},enabled=query.isNotBlank()) { Icon(Icons.Default.Search,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("开始搜索") }
                        if(s.searching) OutlinedButton(onClick=model::cancelSearch) { Text("取消搜索") }
                    }
                    if(s.searching) LinearProgressIndicator(progress={if(s.total==0) 0f else s.completed.toFloat()/s.total},modifier=Modifier.fillMaxWidth().padding(vertical=8.dp))
                    if(s.results.isNotEmpty()) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                        Text("可用资源",style=MaterialTheme.typography.titleMedium)
                        TextButton(onClick=model::sortByQuality) { Text("高清优先 ↓") }
                    }
                    if(s.total > 0) Text("已完成 ${s.completed}/${s.total} 个源 · ${s.failures} 个失败 · ${s.results.size} 条结果（最多 1000）",style=MaterialTheme.typography.bodySmall)
                    if(s.results.isEmpty() && !compact) EmptyPanel(if(s.searching) "正在寻找内容" else if(s.total>0) "暂未找到结果" else "从你的片源开始",
                        if(s.searching) "结果会陆续显示，你可以随时取消。" else if(s.failures>0) "部分片源未响应，可换个关键词或检查订阅。" else "添加合法订阅，搜索影片或直播频道。",R.drawable.ic_search)
                    LazyColumn(contentPadding=PaddingValues(top=12.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.pointerInput(model) {
                        awaitPointerEventScope { while(true) { if(awaitPointerEvent().type==PointerEventType.Press) model.pinSearchOrder() } }
                    }) {
                        items(s.results,key={it.source.key+":"+it.id}) { v ->
                            Card(onClick={model.open(v)},colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer),shape=MaterialTheme.shapes.large,modifier=Modifier.fillMaxWidth().animateItem(fadeInSpec=tween(140),placementSpec=null,fadeOutSpec=null)) {
                                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                        Poster(v,model)
                                        Text(v.title,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                                    }
                                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                        Text("${v.source.name} · ${v.subtitle}",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                                        QualityBadge(v.quality,lastPlayback=true)
                                    }
                                }
                            }
                        }
                    }
                }
                3 -> DiscoverScreen(s,model,{tab=1}) { title -> query=title;tab=0;model.search(title) }
                1 -> LazyColumn(modifier=Modifier.fillMaxSize().testTag("subscriptionsList"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    item { Text("管理订阅",style=MaterialTheme.typography.titleLarge); Text("添加来源，管理你的片库",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(url,{url=it.take(4096)},modifier=Modifier.fillMaxWidth().testTag("subscriptionInput"),label={Text("粘贴订阅链接")},singleLine=true,shape=MaterialTheme.shapes.medium,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri))
                            Button(onClick={model.importSubscription(url)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=url.isNotBlank() && "new" !in s.importing) {
                                Icon(Icons.Default.Add,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("添加订阅")
                            }
                            if("new" in s.importing) TextButton(onClick={model.cancelImport("new")}) { Text("取消导入") }
                            Text("支持 LibreTV JSON、接口目录和 TVBOX 直连源。链接仅存本机。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } }
                    if(s.saved.subscriptions.isEmpty()) item { EmptyPanel("还没有订阅","添加订阅后，在搜索或发现页浏览内容。",R.drawable.ic_library) }
                    s.saved.subscriptions.forEach { sub ->
                        item(key=sub.id) { Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer)) { Column(Modifier.padding(16.dp)) {
                            Text(sub.name,style=MaterialTheme.typography.titleMedium)
                            Text("${sub.sources.size} 个源 · 更新于 ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(sub.updated))}",style=MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick={model.importSubscription(sub.url,sub)},enabled=sub.id !in s.importing) { Text("更新") }
                                TextButton(onClick={delete=sub},colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) { Text("删除") }
                                if(sub.id in s.importing) TextButton(onClick={model.cancelImport(sub.id)}) { Text("取消") }
                            }
                        } } }
                        items(sub.sources,key={sub.id+it.key}) { source ->
                            Row(Modifier.fillMaxWidth().padding(start=12.dp)) {
                                Column(Modifier.weight(1f)) { Text(source.name); Text(if(source.kind==SourceKind.LIVE) "直播 M3U" else "点播 API",style=MaterialTheme.typography.bodySmall)
                                    if(source.kind==SourceKind.VOD) TextButton(onClick={ruleSource=source;ruleText=s.saved.adRules[source.key].orEmpty().joinToString("\n")}) { Text("广告精确路径规则") } }
                                Switch(modifier=Modifier.semantics { contentDescription="启用或停用 ${source.name}" },checked=source.key !in s.saved.disabled,onCheckedChange={model.toggle(source)})
                            }
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
                        Text("总预算含临时文件与余量。响应60秒、封面24小时过期；视频不落盘。APK和系统缓存另计。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
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
    ruleSource?.let { source -> AlertDialog(onDismissRequest={ruleSource=null},title={Text("当前源的广告精确路径")},text={Column { Text("每行一个完整路径，不填域名或查询参数。补充自动识别规则；请仅添加你确认的广告路径。")
        OutlinedTextField(ruleText,{ruleText=it.take(16416)},minLines=3,maxLines=7) }},confirmButton={TextButton(onClick={model.adPaths(source,ruleText);ruleSource=null}) { Text("保存") }},dismissButton={TextButton(onClick={ruleSource=null}) { Text("取消") }}) }
    delete?.let { sub -> AlertDialog(onDismissRequest={delete=null},title={Text("删除此订阅？")},text={Text("移除该订阅的源。其他订阅和历史记录保留。")},confirmButton={TextButton(onClick={model.delete(sub.id);delete=null}){Text("删除")}},dismissButton={TextButton(onClick={delete=null}){Text("取消")}}) }
}

@Composable fun PocketTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary=Color(0xff8ce0c5),onPrimary=Color(0xff00382b),primaryContainer=Color(0xff1b493e),onPrimaryContainer=Color(0xffb0f4dc),
        secondary=Color(0xffb4ccc2),onSecondary=Color(0xff20362e),secondaryContainer=Color(0xff344c43),onSecondaryContainer=Color(0xffd0e9dd),
        tertiary=Color(0xffb4c5f5),onTertiary=Color(0xff20304f),tertiaryContainer=Color(0xff374767),onTertiaryContainer=Color(0xffdae2ff),
        background=Color(0xff0d1513),onBackground=Color(0xffe0e5e0),surface=Color(0xff0d1513),onSurface=Color(0xffe0e5e0),
        surfaceVariant=Color(0xff3f4944),onSurfaceVariant=Color(0xffbfc9c2),surfaceTint=Color(0xff8ce0c5),
        surfaceContainerLowest=Color(0xff08100e),surfaceContainerLow=Color(0xff151d1a),surfaceContainer=Color(0xff19221f),
        surfaceContainerHigh=Color(0xff242c29),surfaceContainerHighest=Color(0xff2e3733),surfaceDim=Color(0xff0d1513),surfaceBright=Color(0xff333c37),
        outline=Color(0xff89938d),outlineVariant=Color(0xff3f4944)),
        shapes=Shapes(extraSmall=RoundedCornerShape(4.dp),small=RoundedCornerShape(8.dp),medium=RoundedCornerShape(12.dp),large=RoundedCornerShape(16.dp),extraLarge=RoundedCornerShape(28.dp)),content=content)
}
