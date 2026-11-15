// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun exportSources(sources:List<Source>):String = JsonObject().apply {
    addProperty("name","已验证片源")
    addProperty("format","pocket-live-sources-v1")
    for(kind in SourceKind.entries) add(if(kind==SourceKind.VOD) "sources" else "liveSources",JsonArray().apply {
        sources.filter { it.kind==kind }.distinctBy { it.key }.forEach { source -> add(JsonObject().apply {
            addProperty("name",source.name);addProperty("url",source.url)
            source.detail?.let { addProperty("detail",it) }
            if(source.adult) addProperty("isAdult",true)
            source.epg?.let { addProperty("epg",it) }
        }) }
    })
}.toString()

internal fun pasteLink(status:Int,response:String):String {
    if(status!=201) throw UserError(if(status==206) "分享内容超出 paste.rs 限制，请导出文件" else "发布失败（HTTP $status），可重试或导出文件")
    val url=response.trim().toHttpUrlOrNull()
    if(url==null || !url.isHttps || url.host!="paste.rs" || url.port!=443 || !Regex("/[A-Za-z0-9_-]+").matches(url.encodedPath) || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query!=null || url.fragment!=null) throw UserError("分享服务返回了无效链接")
    return url.toString()
}

internal suspend fun uploadSourceDocument(document:String,client:okhttp3.OkHttpClient):String =
    suspendCancellableCoroutine { continuation ->
        val request=Request.Builder().url("https://paste.rs/").post(document.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val call=client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException) { continuation.resumeWithException(e) }
            override fun onResponse(call:Call,response:Response) {
                val result=runCatching { response.use { pasteLink(it.code,it.peekBody(2048).string()) } }
                continuation.resumeWith(result)
            }
        })
    }

@Composable internal fun SourceExportSheet(model:AppModel,close:()->Unit) {
    val sources=remember(model) { model.verifiedSources() }
    val document=remember(sources) { exportSources(sources) }
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var link by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val writer=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri!=null) scope.launch {
            try { withContext(Dispatchers.IO) { val output=context.contentResolver.openOutputStream(uri,"wt") ?: throw UserError("文件无法写入");output.use { it.write(document.toByteArray()) } };status="已导出 JSON" }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { status=safeError(e) }
        }
    }
    HandleOnlySheet(close) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("分享已验证片源",style=MaterialTheme.typography.titleLarge)
            Text("共 ${sources.size} 个已启用来源。发布后，持有链接的人可读取片源地址及其参数。在「添加订阅」中粘贴链接或选择 JSON 文件即可导入。",style=MaterialTheme.typography.bodyMedium)
            if(link.isEmpty()) Button(enabled=sources.isNotEmpty() && !busy,onClick={
                busy=true;status=""
                scope.launch {
                    try {
                        link=uploadSourceDocument(document,model.network.client)
                    } catch(e:CancellationException) { throw e }
                    catch(e:Exception) { status=safeError(e) }
                    finally { busy=false }
                }
            }) { Text(if(busy) "正在发布…" else "发布到 paste.rs") }
            else {
                SelectionContainer { Text(link) }
                Button(onClick={
                    val intent=android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT,link)
                    context.startActivity(android.content.Intent.createChooser(intent,"分享订阅链接"))
                }) { Text("分享链接") }
            }
            TextButton(onClick={writer.launch("pocket-live-sources.json")},enabled=sources.isNotEmpty()) { Text("导出 JSON 文件") }
            if(status.isNotEmpty()) Text(status)
        }
    }
}
