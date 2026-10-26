// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun Pocket(model: AppModel) {
    val s by model.state.collectAsStateWithLifecycle()
    val compact=LocalConfiguration.current.screenHeightDp<500
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
        topBar = { TopAppBar(title = { Text(if(s.detail != null) "详情与选集" else "Pocket Live") }, actions = {
            TextButton(onClick={updatesOpen=true;model.checkUpdates()}) { Text(if(s.appRelease!=null) "有新版" else "更新") }
            if(s.detail != null || s.detailLoading) TextButton(onClick = model::closeDetail) { Text("返回") }
        }) },
        bottomBar = { NavigationBar {
            listOf("搜索","订阅","历史","发现").forEachIndexed { i, title ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i; model.closeDetail() }, icon = { Icon(painterResource(listOf(R.drawable.ic_search,R.drawable.ic_library,R.drawable.ic_history,R.drawable.ic_library)[i]),contentDescription=null) }, label = { Text(title) })
            }
        } }, snackbarHost = { SnackbarHost(snack) }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if(s.loadFailed) { Text("本地数据损坏，已停止读写以保留原文件。可在系统应用设置清除数据后重新导入。",Modifier.padding(24.dp)); return@Box }
            if(s.loading) { Column(Modifier.padding(24.dp)) { CircularProgressIndicator(); Text("正在读取本地数据…") }; return@Box }
            if(s.detailLoading) { Column(Modifier.padding(24.dp)) { CircularProgressIndicator(); TextButton(onClick=model::closeDetail) { Text("取消详情请求") } }; return@Box }
            val detail = s.detail
            if(detail != null) {
                LazyColumn(Modifier.fillMaxSize(),contentPadding = PaddingValues(20.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text(detail.video.source.name,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                        Text(detail.video.title,style=MaterialTheme.typography.headlineSmall)
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
                0 -> Column(Modifier.fillMaxSize().padding(horizontal=20.dp)) {
                    if(!compact) {
                        Text("搜你想看的", style=MaterialTheme.typography.headlineMedium,modifier=Modifier.padding(top=12.dp))
                        Text("聚合你的片源，找到更清晰的版本",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=6.dp))
                    }
                    Spacer(Modifier.height(if(compact) 8.dp else 16.dp))
                    OutlinedTextField(query,{query=it.take(100)},modifier=Modifier.fillMaxWidth().testTag("searchInput"),singleLine=true,label={Text("影片或频道名称")},shape=RoundedCornerShape(18.dp),leadingIcon={Icon(painterResource(R.drawable.ic_search),null)})
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Button(onClick={focusManager.clearFocus(); keyboard?.hide(); model.search(query)},enabled=query.isNotBlank()) { Text("开始搜索") }
                        if(s.searching) OutlinedButton(onClick=model::cancelSearch) { Text("取消搜索") }
                    }
                    if(s.searching) LinearProgressIndicator(progress={if(s.total==0) 0f else s.completed.toFloat()/s.total},modifier=Modifier.fillMaxWidth().padding(vertical=8.dp))
                    if(s.results.isNotEmpty()) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                        Text("可用资源",style=MaterialTheme.typography.titleMedium)
                        TextButton(onClick=model::sortByQuality) { Text("高清优先 ↓") }
                    }
                    if(s.total > 0) Text("已完成 ${s.completed}/${s.total} 个源 · ${s.failures} 个失败 · ${s.results.size} 条结果（最多 1000）",style=MaterialTheme.typography.bodySmall)
                    if(s.results.isEmpty()) EmptyPanel(if(s.searching) "正在寻找内容" else if(s.total>0) "暂未找到结果" else "从你的片源开始",
                        if(s.searching) "结果会陆续显示，你可以随时取消。" else if(s.failures>0) "部分片源未响应，可换个关键词或检查订阅。" else "添加合法订阅，搜索影片或直播频道。",R.drawable.ic_search)
                    LazyColumn(contentPadding=PaddingValues(top=12.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.pointerInput(model) {
                        awaitPointerEventScope { while(true) { if(awaitPointerEvent().type==PointerEventType.Press) model.pinSearchOrder() } }
                    }) {
                        items(s.results,key={it.source.key+":"+it.id}) { v ->
                            Card(onClick={model.open(v)},shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth().animateItem(fadeInSpec=tween(140),placementSpec=null,fadeOutSpec=null)) {
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
                1 -> LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    item { Text("管理订阅",style=MaterialTheme.typography.headlineMedium); Text("支持 LibreTV JSON、接口目录和 TVBOX 直连源。链接仅存本机。") }
                    item { OutlinedTextField(url,{url=it.take(4096)},modifier=Modifier.fillMaxWidth().testTag("subscriptionInput"),label={Text("粘贴订阅链接")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri)) }
                    item { Row {
                        Button(onClick={model.importSubscription(url)},enabled=url.isNotBlank() && "new" !in s.importing) { Text("添加订阅") }
                        if("new" in s.importing) TextButton(onClick={model.cancelImport("new")}) { Text("取消导入") }
                    } }
                    item { Row { Switch(checked=s.saved.allowHttp,onCheckedChange=model::allowHttp); Text("允许未加密 HTTP\n可能被监听或篡改，仅用于可信源",Modifier.padding(start=12.dp),style=MaterialTheme.typography.bodySmall) } }
                    item { Text("当前仅访问公网地址；局域网、脚本、XML 与多仓配置不受支持。订阅 2 MiB / 20 个，总源数 300。",style=MaterialTheme.typography.bodySmall) }
                    if(s.saved.subscriptions.isEmpty()) item { EmptyPanel("还没有订阅","粘贴订阅链接，点播和直播资源将在这里统一管理。",R.drawable.ic_library) }
                    item { Row(verticalAlignment=Alignment.CenterVertically) {
                        Switch(checked=s.saved.adFilter,onCheckedChange=model::filterAds)
                        Text("自动 HLS 广告过滤（默认关闭）\n识别广告路径、片头及短中插段；可能误判，可随时关闭。复杂流保持原样，下次播放生效",Modifier.padding(start=12.dp),style=MaterialTheme.typography.bodySmall)
                    } }
                    s.saved.subscriptions.forEach { sub ->
                        item(key=sub.id) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                            Text(sub.name,style=MaterialTheme.typography.titleMedium)
                            Text("${sub.sources.size} 个源 · 更新于 ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(sub.updated))}",style=MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick={model.importSubscription(sub.url,sub)},enabled=sub.id !in s.importing) { Text("更新") }
                                TextButton(onClick={delete=sub}) { Text("删除") }
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
                    item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                        Text("存储与缓存",style=MaterialTheme.typography.titleMedium)
                        s.storageUsage?.let { u ->
                            Text("用户数据（含原子写文件）：${u.userDataBytes/1024} KiB / 4 MiB 正式文件")
                            Text("响应磁盘：${u.responseBytes/1024} KiB / 8 MiB · ${u.entries}/64 条")
                            Text("封面磁盘：${u.posterBytes/1024} KiB / 20 MB")
                            Text("响应内存计量：${u.memoryBytes/1024} KiB / 2 MiB")
                        }
                        Text("应用自写文件总预算50,000,000字节，含临时文件与余量。响应60秒、封面24小时过期；视频不落盘。APK、系统编译缓存另计。封面内存6MB、单图解码12.8万像素、下载1MB，并发2。",style=MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick=model::refreshStorage) { Text("刷新用量") }
                            TextButton(onClick={model.clearCache()}) { Text("清缓存（保留配置）") }
                        }
                    } } }
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
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xff8ce0c5),onPrimary=Color(0xff00382b),primaryContainer=Color(0xff1b493e),
                secondary=Color(0xffb4c5f5),background = Color(0xff0d141b),surface = Color(0xff141e28),
                surfaceVariant=Color(0xff23313e),onSurface=Color(0xffe5edf4),onSurfaceVariant=Color(0xffadbdca),outline=Color(0xff40505e))) { content() }
}
