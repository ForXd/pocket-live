// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

@Composable fun SettingsScreen(s:UiState,model:AppModel,appearance:()->Unit,updates:()->Unit,sourceRules:(Source)->Unit) {
    val transfer by model.updater.state.collectAsState()
    var rulesExpanded by rememberSaveable { mutableStateOf(false) }
    val sources=remember(s.saved.subscriptions) { s.saved.subscriptions.flatMap { it.sources }.filter { it.kind==SourceKind.VOD }.distinctBy { it.key } }
    LazyColumn(Modifier.fillMaxSize().testTag("settingsList"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { SettingsEntry("外观设置","跟随系统、日间或夜间模式",appearance,"settingsAppearance") }
        item { SettingsEntry("应用更新",when {
            transfer.phase==UpdatePhase.DOWNLOADING -> "正在下载 · ${transfer.received/1024} KiB"
            transfer.phase==UpdatePhase.VERIFYING -> "正在校验安装包"
            transfer.phase==UpdatePhase.READY -> "安装包已就绪 · 点击安装"
            transfer.phase==UpdatePhase.ERROR -> "下载未完成 · 点击重试"
            s.updateChecking -> "正在检查更新…"
            s.appRelease!=null -> "有新版 · 点击查看与安装"
            s.updateError!=null -> "检查失败 · 点击重试"
            else -> "当前版本 ${BuildConfig.VERSION_NAME} · 检查更新"
        },updates,"settingsUpdates") }
        item(key="networkSettings") { SettingsGroup("播放与网络","HTTP 访问 · 广告过滤") {
            SettingSwitch("允许未加密 HTTP","可能被监听或篡改，仅用于可信源",s.saved.allowHttp,model::allowHttp)
            ContentDivider()
            SettingSwitch("自动 HLS 广告过滤","默认关闭，仅识别明确广告路径；不按短片段猜测，复杂流保持原样。",s.saved.adFilter,model::filterAds)
        } }
        item(key="storageSettings") { SettingsGroup("存储与缓存","查看用量与清理缓存") {
            s.storageUsage?.let { u ->
                Text("用户数据：${u.userDataBytes/1024} KiB",style=MaterialTheme.typography.bodyMedium)
                Text("响应缓存：${u.responseBytes/1024} KiB / 8 MiB · ${u.entries}/64 条",style=MaterialTheme.typography.bodyMedium)
                Text("封面缓存：${u.posterBytes/1024} KiB / 20 MB",style=MaterialTheme.typography.bodyMedium)
                Text("响应内存：${u.memoryBytes/1024} KiB / 2 MiB",style=MaterialTheme.typography.bodyMedium)
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick={model.clearCache()},modifier=Modifier.heightIn(min=48.dp)) { Text("清缓存（保留配置）") }
                TextButton(onClick=model::refreshStorage,modifier=Modifier.heightIn(min=48.dp)) { Text("刷新用量") }
            }
        } }
        item { SettingsEntry("来源广告规则","为点播来源配置精确路径 · ${sources.size} 个来源",{rulesExpanded=!rulesExpanded},"settingsSourceRules",expanded=rulesExpanded) }
        if(rulesExpanded) {
            if(sources.isEmpty()) item { Text("在订阅页添加点播来源后，可在这里配置规则。") }
            items(sources,key={"rules:${it.key}"}) { source ->
                SettingsEntry(source.name,"精确路径规则",{sourceRules(source)},"settingsRule:${source.key}")
            }
        }
        item { Text("Pocket Live · AGPL-3.0-or-later\n参考 LibreTV deb72fa01f；完整源码和许可证随项目提供。",style=MaterialTheme.typography.bodySmall) }
    }
}
@Composable private fun SettingsEntry(title:String,summary:String,action:()->Unit,tag:String,expanded:Boolean?=null) {
    Surface(onClick=action,color=MaterialTheme.colorScheme.surfaceContainer,shape=MaterialTheme.shapes.medium,
        modifier=Modifier.fillMaxWidth().heightIn(min=72.dp).testTag(tag).semantics { contentDescription=title }) {
        Column {
            Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(title,style=MaterialTheme.typography.titleMedium)
                    Text(summary.replace(" · ","，"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if(expanded==null) Icons.AutoMirrored.Filled.KeyboardArrowRight else if(expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
