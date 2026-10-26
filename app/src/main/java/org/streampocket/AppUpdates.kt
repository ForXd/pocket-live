// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

data class AppRelease(val version:String,val url:String,val page:String,val notes:String,val bytes:Long)
object AppUpdates {
    const val API="https://api.github.com/repos/ForXd/pocket-live/releases/latest"
    private const val ROOT="https://github.com/ForXd/pocket-live/releases"
    private fun version(raw:String):List<Int>? = Regex("v?(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})")
        .matchEntire(raw)?.groupValues?.drop(1)?.map { it.toInt() }
    fun newer(candidate:String,current:String):Boolean {
        val a=version(candidate) ?: return false;val b=version(current) ?: return false
        for(i in a.indices) if(a[i]!=b[i]) return a[i]>b[i]
        return false
    }
    fun parse(text:String,current:String):AppRelease? {
        val obj=JsonData.parse(text).asJsonObject
        if(obj.get("draft")?.asBoolean!=false || obj.get("prerelease")?.asBoolean!=false) return null
        val tag=obj.get("tag_name")?.asString ?: return null
        if(!newer(tag,current)) return null
        val number=tag.removePrefix("v")
        val page="$ROOT/tag/$tag"
        if(obj.get("html_url")?.asString!=page) throw UserError("更新页面不属于本项目")
        val name="pocket-live-$number-release.apk"
        val expected="$ROOT/download/$tag/$name"
        val asset=obj.getAsJsonArray("assets")?.map { it.asJsonObject }?.singleOrNull {
            it.get("name")?.asString==name && it.get("state")?.asString=="uploaded"
        } ?: throw UserError("新版安装包尚未准备好，请稍后重试")
        if(asset.get("browser_download_url")?.asString!=expected) throw UserError("更新下载地址无效")
        val size=asset.get("size")?.asLong ?: 0
        if(size !in 1..50_000_000L) throw UserError("更新包大小不符合预期")
        return AppRelease(number,expected,page,obj.get("body")?.takeUnless { it.isJsonNull }?.asString.orEmpty().take(2000),size)
    }
}

@Composable fun UpdateDialog(state:UiState,model:AppModel,close:()->Unit) {
    val context=LocalContext.current
    fun open(url:String) {
        try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(url))) }
        catch(_:android.content.ActivityNotFoundException) { model.message("没有可用浏览器，请安装浏览器后重试") }
    }
    AlertDialog(onDismissRequest=close,title={Text("应用更新")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("当前版本 ${BuildConfig.VERSION_NAME}")
        if(state.updateChecking) { LinearProgressIndicator(Modifier.fillMaxWidth());Text("正在检查 GitHub Release…") }
        state.updateError?.let { Text("检查失败：$it") }
        state.appRelease?.let { release ->
            Text("发现新版 ${release.version} · ${release.bytes/1024} KiB")
            Text("从 ForXd/pocket-live 官方 Release 下载，完成后打开 APK，由 Android 确认安装。订阅和历史会保留。")
            TextButton(onClick={open(release.page)}) { Text("查看更新说明") }
            TextButton(enabled=!state.updateChecking,onClick={model.checkUpdates(true)}) { Text("重新检查") }
        } ?: if(state.updateChecked && !state.updateChecking && state.updateError==null) Text("已是最新正式版") else Unit
        Text("启动或回到前台自动检查，每次运行最多每24小时一次。不会自动下载；检查失败不影响播放。",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={
        state.appRelease?.let { release -> TextButton(onClick={open(release.url)}) { Text("下载更新") } }
            ?: TextButton(enabled=!state.updateChecking,onClick={model.checkUpdates(true)}) { Text("检查更新") }
    },dismissButton={TextButton(onClick=close) { Text("关闭") }})
}
