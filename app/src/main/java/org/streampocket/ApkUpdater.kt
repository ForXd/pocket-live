// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class UpdatePhase { IDLE, DOWNLOADING, VERIFYING, READY, ERROR }
data class UpdateTransfer(val phase:UpdatePhase=UpdatePhase.IDLE,val release:AppRelease?=null,val received:Long=0,val error:String?=null)

/** A bounded stream, never an APK-sized heap buffer. Also rejects truncated/chunked oversized bodies. */
internal fun copyUpdate(input:InputStream,output:OutputStream,expected:Long,progress:(Long)->Unit={}):String {
    if(expected !in 1..12_000_000L) throw UserError("安装包超过 12 MB 更新预算")
    val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(32*1024);var total=0L
    while(true) {
        val n=input.read(buffer);if(n<0) break
        if(total+n>expected) throw UserError("安装包大小与发布记录不符")
        output.write(buffer,0,n);digest.update(buffer,0,n);total+=n;progress(total)
    }
    if(total!=expected) throw UserError("下载不完整，请重试")
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal data class ApkIdentity(val packageName:String,val version:String,val code:Long,val signers:Set<String>)
internal fun validateUpdateIdentity(apk:ApkIdentity,installed:ApkIdentity,release:AppRelease) {
    if(apk.packageName!=installed.packageName || apk.version!=release.version || apk.code<=installed.code)
        throw UserError("安装包的应用或版本不匹配")
    if(apk.signers.isEmpty() || apk.signers!=installed.signers) throw UserError("安装包签名不匹配，已拒绝安装")
}
@Suppress("DEPRECATION")
internal fun apkIdentity(context:Context,file:File?=null):ApkIdentity {
    val pm=context.packageManager
    val flags=if(Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val info=if(file==null) pm.getPackageInfo(context.packageName,flags) else pm.getPackageArchiveInfo(file.path,flags)
    info ?: throw UserError("下载文件不是有效安装包")
    val signers=if(Build.VERSION.SDK_INT>=28) info.signingInfo?.apkContentsSigners else info.signatures
    return ApkIdentity(info.packageName,info.versionName.orEmpty(),if(Build.VERSION.SDK_INT>=28) info.longVersionCode else info.versionCode.toLong(),signers.orEmpty().map { sig ->
        MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
    }.toSet())
}

/** Owned by AppModel: application context only, one job and one bounded request. */
class ApkUpdater(private val app:Application,client:OkHttpClient,private val scope:CoroutineScope) {
    private val client=client.newBuilder().callTimeout(3,TimeUnit.MINUTES).readTimeout(25,TimeUnit.SECONDS).build()
    private val directory=File(app.cacheDir,"updates")
    private val ready=File(directory,"ready/update.apk")
    private val metadata=File(directory,"release.json")
    private val mutable=MutableStateFlow(UpdateTransfer())
    val state=mutable.asStateFlow()
    private var job:Job?=null
    init {
        job=scope.launch {
            withContext(Dispatchers.IO) {
                directory.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
                if(metadata.exists()) try {
                    val release=AppUpdates.restoreDownload(metadata.readText())
                    if(!AppUpdates.newer(release.version,BuildConfig.VERSION_NAME)) throw UserError("已安装")
                    verify(ready,release)
                    mutable.value=UpdateTransfer(UpdatePhase.READY,release,release.bytes)
                } catch(e:CancellationException) { throw e }
                catch(_:Exception) { ready.delete();metadata.delete() }
            }
        }
    }
    fun download(release:AppRelease) {
        if(job?.isCompleted==false) return
        job=scope.launch {
            val partial=File(directory,"${java.util.UUID.randomUUID()}.part")
            try {
                AppUpdates.validateDownload(release)
                mutable.value=UpdateTransfer(UpdatePhase.DOWNLOADING,release)
                withContext(Dispatchers.IO) {
                    ready.delete();metadata.delete();directory.mkdirs()
                    // Reserve the existing data/cache maxima plus 12 MB for a single installer.
                    if(release.bytes>12_000_000 || app.cacheDir.usableSpace<release.bytes+5_000_000) throw UserError("更新空间不足或安装包超过 12 MB，请清理缓存后重试")
                    val ownedBytes=(app.filesDir.walkTopDown()+app.cacheDir.walkTopDown()).filter { it.isFile }.sumOf { it.length() }
                    if(ownedBytes+release.bytes>50_000_000) throw UserError("应用文件将超过 50 MB，请清理缓存后重试")
                }
                fetch(release,partial)
                mutable.value=UpdateTransfer(UpdatePhase.VERIFYING,release,release.bytes)
                withContext(Dispatchers.IO) {
                    verify(partial,release)
                    currentCoroutineContext().ensureActive()
                    ready.parentFile!!.mkdirs()
                    if(!partial.renameTo(ready)) throw UserError("无法保存安装包")
                    val atomic=android.util.AtomicFile(metadata)
                    val stream=atomic.startWrite()
                    try { stream.write(AppUpdates.storeDownload(release).toByteArray());atomic.finishWrite(stream) }
                    catch(e:Exception) { atomic.failWrite(stream);throw e }
                }
                mutable.value=UpdateTransfer(UpdatePhase.READY,release,release.bytes)
            } catch(e:CancellationException) {
                mutable.value=UpdateTransfer()
                throw e
            } catch(e:Exception) {
                mutable.value=UpdateTransfer(UpdatePhase.ERROR,release,error=safeError(e))
            } finally {
                withContext(NonCancellable+Dispatchers.IO) {
                    partial.delete()
                    if(mutable.value.phase!=UpdatePhase.READY) { ready.delete();metadata.delete() }
                }
            }
        }
    }
    fun cancel() { job?.cancel() }
    fun message(message:String) { mutable.value=mutable.value.copy(error=message) }
    private fun verify(file:File,release:AppRelease) {
        AppUpdates.validateDownload(release)
        if(!file.isFile || file.length()!=release.bytes) throw UserError("安装包缺失或不完整，请重新下载")
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val chunk=ByteArray(32*1024);while(true) { val n=input.read(chunk);if(n<0) break;digest.update(chunk,0,n) } }
        if(digest.digest().joinToString("") { "%02x".format(it) }!=release.sha256) throw UserError("安装包校验失败，请重新下载")
        validateUpdateIdentity(apkIdentity(app,file),apkIdentity(app),release)
    }
    /** Revalidate after permission settings or process recreation, before granting a URI. */
    suspend fun installIntent():Intent = withContext(Dispatchers.IO) {
        val release=mutable.value.release ?: throw UserError("请先下载安装包")
        try { verify(ready,release) }
        catch(e:Exception) {
            if(e is CancellationException) throw e
            ready.delete();metadata.delete()
            mutable.value=UpdateTransfer(UpdatePhase.ERROR,release,error=safeError(e))
            throw e
        }
        Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(app,"${app.packageName}.updates",ready),"application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    private suspend fun fetch(release:AppRelease,file:File):Unit=suspendCancellableCoroutine { continuation ->
        val call=client.newCall(Request.Builder().url(release.url).header("Accept","application/octet-stream").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:java.io.IOException) { if(continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call:Call,response:Response) {
                try {
                    response.use {
                        if(!it.isSuccessful) throw UserError("下载失败：HTTP ${it.code}")
                        val body=it.body ?: throw UserError("下载内容为空")
                        if(body.contentLength()>0 && body.contentLength()!=release.bytes) throw UserError("安装包大小不符")
                        var previous=-1
                        val sha=file.outputStream().use { output -> body.byteStream().use { input -> copyUpdate(input,output,release.bytes) { bytes ->
                            if(!continuation.isActive) throw java.io.IOException("Cancelled")
                            val percent=(bytes*100/release.bytes).toInt()
                            if(percent!=previous) { previous=percent;mutable.value=UpdateTransfer(UpdatePhase.DOWNLOADING,release,bytes) }
                        } } }
                        if(sha!=release.sha256) throw UserError("安装包校验失败，请重新下载")
                    }
                    if(continuation.isActive) continuation.resume(Unit)
                } catch(e:Exception) { if(continuation.isActive) continuation.resumeWithException(e) }
                finally { if(continuation.isCancelled) file.delete() }
            }
        })
    }
}
