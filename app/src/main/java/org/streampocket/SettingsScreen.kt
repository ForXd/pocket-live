// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.foundation.layout.*
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
        item {
            Text("按你的习惯观看",style=MaterialTheme.typography.headlineSmall)
            Text("外观、播放与应用管理",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
        item { SettingsEntry("来源广告规则","为点播来源配置精确路径 · ${sources.size} 个来源",{rulesExpanded=!rulesExpanded},"settingsSourceRules") }
        if(rulesExpanded) {
            if(sources.isEmpty()) item { Text("在订阅页添加点播来源后，可在这里配置规则。") }
            items(sources,key={"rules:${it.key}"}) { source ->
                OutlinedButton(onClick={sourceRules(source)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("配置规则 · ${source.name}") }
            }
        }
        item { Text("Pocket Live · AGPL-3.0-or-later\n参考 LibreTV deb72fa01f；完整源码和许可证随项目提供。\nEPG 字段保留，当前不提供节目表界面。",style=MaterialTheme.typography.bodySmall) }
    }
}
@Composable private fun SettingsEntry(title:String,summary:String,action:()->Unit,tag:String) {
    ContentCard(onClick=action,modifier=Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription=title }) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(title,style=MaterialTheme.typography.titleMedium)
            Text(summary,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
