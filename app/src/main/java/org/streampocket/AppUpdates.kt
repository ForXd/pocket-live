// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.compose.material3.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

data class AppRelease(val version:String,val url:String,val page:String,val notes:String,val bytes:Long,val sha256:String?=null)
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
        val digest=asset.get("digest")?.takeUnless { it.isJsonNull }?.asString?.removePrefix("sha256:")?.lowercase()
        return AppRelease(number,expected,page,obj.get("body")?.takeUnless { it.isJsonNull }?.asString.orEmpty().take(2000),size,digest)
    }
    fun validateDownload(release:AppRelease) {
        if(version(release.version)==null || release.url!="$ROOT/download/v${release.version}/pocket-live-${release.version}-release.apk" || release.page!="$ROOT/tag/v${release.version}") throw UserError("更新地址不属于本项目")
        if(release.bytes !in 1..12_000_000L) throw UserError("安装包超过 12 MB 更新预算")
        if(release.sha256?.matches(Regex("[0-9a-f]{64}"))!=true) throw UserError("发布记录缺少 SHA-256，请重新检查更新")
    }
    fun storeDownload(release:AppRelease):String=com.google.gson.JsonObject().apply {
        addProperty("version",release.version);addProperty("url",release.url);addProperty("page",release.page)
        addProperty("bytes",release.bytes);addProperty("sha256",release.sha256)
    }.toString()
    fun restoreDownload(json:String):AppRelease {
        val obj=JsonData.parse(json).asJsonObject
        return AppRelease(obj.get("version").asString,obj.get("url").asString,obj.get("page").asString,"",obj.get("bytes").asLong,obj.get("sha256").asString).also(::validateDownload)
    }

}

@Composable fun UpdateDialog(state:UiState,model:AppModel,updater:ApkUpdater=model.updater,close:()->Unit) {
    val context=LocalContext.current
    val transfer by updater.state.collectAsState()
    val scope=rememberCoroutineScope()
    var installWhenReady by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    fun install() { scope.launch {
        try { context.startActivity(updater.installIntent()) }
        catch(e:Exception) { if(e is kotlinx.coroutines.CancellationException) throw e;updater.message(if(e is android.content.ActivityNotFoundException) "设备没有可用的 APK 安装程序" else safeError(e)) }
    } }
    val permission=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        if(context.packageManager.canRequestPackageInstalls()) install() else updater.message("尚未允许安装。可点击“安装更新”再次授权，已下载的文件会保留。")
    }
    fun requestInstall() {
        if(context.packageManager.canRequestPackageInstalls()) install()
        else try { permission.launch(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,android.net.Uri.parse("package:${context.packageName}"))) }
        catch(_:android.content.ActivityNotFoundException) { updater.message("请在系统设置中允许此应用安装未知应用，然后返回安装") }
    }
    LaunchedEffect(transfer.phase,lifecycleState) {
        if(transfer.phase==UpdatePhase.READY && installWhenReady && lifecycleState==androidx.lifecycle.Lifecycle.State.RESUMED) {
            installWhenReady=false;requestInstall()
        }
    }
    val busy=transfer.phase==UpdatePhase.DOWNLOADING || transfer.phase==UpdatePhase.VERIFYING
    val release=if(busy || transfer.phase==UpdatePhase.READY) transfer.release else state.appRelease ?: transfer.release
    AlertDialog(onDismissRequest=close,title={Text("应用更新")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Pocket Live · ${BuildConfig.VERSION_NAME}",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
        if(state.updateChecking) { LinearProgressIndicator(Modifier.fillMaxWidth());Text("正在检查新版…") }
        state.updateError?.let { Text("检查失败：$it") }
        release?.let {
            Text("新版 ${it.version} · ${"%.1f".format(it.bytes/1_000_000.0)} MB",style=MaterialTheme.typography.titleMedium)
            if(it.notes.isNotBlank()) Text(it.notes,style=MaterialTheme.typography.bodySmall)
        }
        when(transfer.phase) {
            UpdatePhase.DOWNLOADING -> {
                LinearProgressIndicator(progress={transfer.received.toFloat()/(transfer.release?.bytes ?: 1).coerceAtLeast(1)},modifier=Modifier.fillMaxWidth())
                Text("正在下载 ${(transfer.received*100/(transfer.release?.bytes ?: 1).coerceAtLeast(1))}%")
                TextButton(onClick={installWhenReady=false;updater.cancel()}) { Text("取消下载") }
            }
            UpdatePhase.VERIFYING -> { LinearProgressIndicator(Modifier.fillMaxWidth());Text("正在校验安装包和签名…") }
            UpdatePhase.READY -> Text("下载及签名校验完成，可直接安装。")
            else -> Unit
        }
        transfer.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        if(release==null && state.updateChecked && !state.updateChecking && state.updateError==null) Text("已是最新正式版")
        if(!busy) TextButton(enabled=!state.updateChecking,onClick={model.checkUpdates(true)}) { Text("重新检查") }
        Text("应用内下载，无需浏览器。安装由 Android 确认，订阅和历史保留。首次安装需允许本应用安装更新。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("关闭弹窗后下载继续；若进程被系统结束，重新打开后可重试。完成的安装包会保留，更新后自动清理。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }},confirmButton={
        if(transfer.phase==UpdatePhase.READY) TextButton(onClick=::requestInstall) { Text("安装更新") }
        else if(release!=null) TextButton(enabled=!busy,onClick={installWhenReady=true;updater.download(release)}) { Text(if(transfer.phase==UpdatePhase.ERROR) "重新下载" else "下载并安装") }
        else TextButton(enabled=!state.updateChecking,onClick={model.checkUpdates(true)}) { Text("检查更新") }
    },dismissButton={TextButton(onClick=close) { Text("关闭") }})
}
