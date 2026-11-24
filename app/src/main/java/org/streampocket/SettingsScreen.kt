// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

internal enum class SettingsPage(val title:String) { NETWORK("播放与网络"), STORAGE("存储与缓存"), RULES("来源广告规则") }

@Composable internal fun SettingsScreen(s:UiState,model:AppModel,page:SettingsPage?,navigate:(SettingsPage?)->Unit,appearance:()->Unit,updates:()->Unit,sourceRules:(Source)->Unit) {
    val transfer by model.updater.state.collectAsStateWithLifecycle()

    val sources=remember(s.saved.subscriptions) { s.saved.subscriptions.flatMap { it.sources }.filter { it.kind==SourceKind.VOD }.distinctBy { it.key } }
    LazyColumn(Modifier.fillMaxSize().testTag("settingsList"),contentPadding=PaddingValues(start=PageGutter,end=PageGutter,bottom=20.dp+LocalDockInset.current),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(page!=null) {
            item {
                val motion=LocalCardReturn.current
                BackHeading(page.title,"返回设置",{ motion.commit { navigate(null) } },inset=0.dp)
            }
            when(page) {
                SettingsPage.NETWORK -> item { Column {
            SettingSwitch("允许未加密 HTTP","可能被监听或篡改，仅用于可信源",s.saved.allowHttp,model::allowHttp)
            ContentDivider()
            SettingSwitch("自动 HLS 广告过滤","默认关闭，仅识别明确广告路径；不按短片段猜测，复杂流保持原样。",s.saved.adFilter,model::filterAds)

                } }
                SettingsPage.STORAGE -> item { Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            s.storageUsage?.let { u ->
                Text("用户数据：${u.userDataBytes/1024} KiB",style=MaterialTheme.typography.bodyMedium)
                Text("响应缓存：${u.responseBytes/1024} KiB / 8 MiB · ${u.entries}/64 条",style=MaterialTheme.typography.bodyMedium)
                Text("封面缓存：${u.posterBytes/1024} KiB / 20 MB",style=MaterialTheme.typography.bodyMedium)
                Text("响应内存：${u.memoryBytes/1024} KiB / 2 MiB",style=MaterialTheme.typography.bodyMedium)
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                GlassButton(shape=MaterialTheme.shapes.medium,onClick={model.clearCache()},modifier=Modifier.heightIn(min=48.dp)) { Text("清缓存（保留配置）") }
                GlassTextButton(onClick=model::refreshStorage,modifier=Modifier.heightIn(min=48.dp)) { Text("刷新用量") }
            }

                } }
                SettingsPage.RULES -> {
                    if(sources.isEmpty()) item { Text("在订阅页添加点播来源后，可在这里配置规则。") }
                    items(sources,key={"rules:${it.key}"}) { source ->
                        SettingsEntry(source.name,"精确路径规则",{sourceRules(source)},"settingsRule:${source.key}")
                    }
                }
            }
            return@LazyColumn
        }
        item { PageHeading("设置",inset=0.dp) }
        item { Text("应用",style=MaterialTheme.typography.titleLarge) }
        item { ContentCard(Modifier.fillMaxWidth()) {
            SettingsEntry("外观设置",rememberThemeMode().title,appearance,"settingsAppearance",grouped=true)
            ContentDivider(Modifier.padding(horizontal=16.dp))
            SettingsEntry("应用更新",when {
            transfer.phase==UpdatePhase.DOWNLOADING -> "正在下载 · ${transfer.received/1024} KiB"
            transfer.phase==UpdatePhase.VERIFYING -> "正在校验安装包"
            transfer.phase==UpdatePhase.READY -> "可安装更新"
            transfer.phase==UpdatePhase.ERROR -> "下载未完成"
            s.updateChecking -> "正在检查更新…"
            s.appRelease!=null -> "新版 ${s.appRelease.version}"
            s.updateError!=null -> "检查失败"
            else -> "当前版本 ${BuildConfig.VERSION_NAME}"
        },updates,"settingsUpdates",grouped=true) } }
        item { Text("偏好与数据",style=MaterialTheme.typography.titleLarge) }
        item(key="searchHistory") {
            ContentCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal=16.dp,vertical=8.dp)) {
                    SettingSwitch("搜索历史","在本机保留最近 12 个搜索词，关闭时清空记录",s.saved.searchHistoryEnabled,model::setSearchHistory)
                    if(s.saved.recentSearches.isNotEmpty()) GlassTextButton(onClick=model::clearSearchHistory) { Text("清空搜索历史") }
                }
                SettingsPage.entries.forEach { destination ->
                    ContentDivider(Modifier.padding(horizontal=16.dp))
                    val (summary,tag)=when(destination) {
                        SettingsPage.NETWORK -> "HTTP 访问、广告过滤" to "settingsNetwork"
                        SettingsPage.STORAGE -> "查看用量与清理缓存" to "settingsStorage"
                        SettingsPage.RULES -> "按来源配置精确路径" to "settingsSourceRules"
                    }
                    SettingsEntry(destination.title,summary,{navigate(destination)},tag,grouped=true)
                }
            }
        }
    }
}
@Composable private fun SettingsEntry(title:String,summary:String,action:()->Unit,tag:String,grouped:Boolean=false) {
    val row: @Composable () -> Unit = {
        PreferenceRow(title,summary,action,Modifier.testTag(tag).semantics { contentDescription=title }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if(grouped) row() else ContentCard(Modifier.fillMaxWidth()) { row() }
}
