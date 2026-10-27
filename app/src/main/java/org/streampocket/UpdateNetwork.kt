// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fixed official HTTPS hosts only. System DNS supports VPN fake-IP; TLS verification stays enabled.
 * Never used for user subscriptions. Own dispatcher prevents media cancellation from killing updates. */
internal class UpdateNetwork {
    val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS)
        .callTimeout(3,TimeUnit.MINUTES).followRedirects(false).followSslRedirects(false)
        .addInterceptor { chain ->
            var request=chain.request();var redirects=0
            while(true) {
                requireOfficial(request.url)
                val response=chain.proceed(request.newBuilder().header("User-Agent","PocketLive/${BuildConfig.VERSION_NAME}").build())
                if(response.code !in listOf(301,302,303,307,308)) return@addInterceptor response
                val target=response.header("Location")?.let { request.url.resolve(it) }
                response.close()
                if(target==null || ++redirects>5) throw IOException("Update redirect limit")
                requireOfficial(target)
                request=request.newBuilder().url(target).removeHeader("Authorization").removeHeader("Cookie").build()
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }.build()
    suspend fun latest():String=latest(::document)
    private suspend fun document(url:String):String=suspendCancellableCoroutine { continuation ->
        val call=client.newCall(Request.Builder().url(url).header("Accept","application/json").build())
        call.timeout().timeout(20,TimeUnit.SECONDS)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException) { if(continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call:Call,response:Response) {
                try {
                    val text=response.use { r ->
                        if(!r.isSuccessful) throw UserError("更新服务返回 HTTP ${r.code}")
                        val body=r.body ?: throw UserError("更新清单为空")
                        if(body.contentLength()>JsonData.LIMIT) throw UserError("更新清单过大")
                        val out=java.io.ByteArrayOutputStream()
                        body.byteStream().use { input -> val buffer=ByteArray(8192)
                            while(true) { val n=input.read(buffer);if(n<0) break
                                if(out.size()+n>JsonData.LIMIT) throw UserError("更新清单过大")
                                out.write(buffer,0,n)
                            }
                        };out.toString("UTF-8")
                    }
                    if(continuation.isActive) continuation.resume(text)
                } catch(e:Exception) { if(continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
    fun close() { client.dispatcher.cancelAll();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown() }
    companion object {
        const val MANIFEST="https://github.com/ForXd/pocket-live/releases/latest/download/update.json"
        fun requireOfficial(url:HttpUrl) {
            if(!url.isHttps || url.port!=443 || url.username.isNotEmpty() || url.password.isNotEmpty() || url.host !in setOf("github.com","api.github.com","release-assets.githubusercontent.com","objects.githubusercontent.com"))
                throw IOException("Update URL is not official HTTPS")
        }
        suspend fun latest(read:suspend (String)->String):String {
            try { return read(AppUpdates.API) }
            catch(e:CancellationException) { throw e }
            catch(_:Exception) { return read(MANIFEST) }
        }
        fun error(e:Exception):String=when(e) {
            is java.net.UnknownHostException -> "更新域名解析失败，请检查网络或代理后重试"
            is javax.net.ssl.SSLException -> "更新连接的安全校验失败，请检查设备时间与网络"
            is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "更新连接超时，请检查网络后重试"
            is IOException -> "无法连接 GitHub 更新服务，请检查网络或代理后重试"
            else -> safeError(e)
        }
    }
}
