// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object PublicNetwork {
    fun publicAddress(a: InetAddress): Boolean {
        if(a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
        val b = a.address.map { it.toInt() and 255 }
        if(b.size == 4) return !(
            (b[0] == 192 && b[1] == 0 && b[2] in listOf(0,2)) ||
            (b[0] == 192 && b[1] == 88 && b[2] == 99) ||
            (b[0] == 198 && b[1] == 51 && b[2] == 100) ||
            (b[0] == 203 && b[1] == 0 && b[2] == 113) || b[0] == 0 || b[0] == 127 || b[0] >= 224 || (b[0] == 100 && b[1] in 64..127) || (b[0] == 169 && b[1] == 254) || (b[0] == 198 && b[1] in 18..19))
        // Reject ULA, IPv4-compatible, NAT64 and transition addresses as well as reserved space.
        return b[0] in 0x20..0x3f && !(b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8) && !(b[0] == 0x20 && b[1] == 0x02) && !(b[0] == 0x20 && b[1] == 0x01 && b[2] == 0 && b[3] == 0)
    }
    val dns = object : Dns {
        override fun lookup(host: String): List<InetAddress> =
        Dns.SYSTEM.lookup(host).also { addresses ->
            if(addresses.isEmpty() || addresses.any { !publicAddress(it) }) throw IOException("Local network blocked")
        }
    }
}
class Network(private val allowHttp: () -> Boolean, dns: Dns = PublicNetwork.dns, private val cache: ResponseCache? = null) {
    val client: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply { maxRequests = 4; maxRequestsPerHost = 4 })
        .dns(dns).connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .addInterceptor { chain ->
            var request = chain.request(); var redirects = 0
            while(true) {
                val url = request.url
                if((url.host.contains(':') || url.host.matches(Regex("[0-9.]+"))) && !PublicNetwork.publicAddress(InetAddress.getByName(url.host))) throw IOException("Local network blocked")
                if(validUrl(url.toString()) == null || (!url.isHttps && !allowHttp())) throw IOException("URL policy")
                if(url.host == "movie.douban.com" || url.host.endsWith(".doubanio.com")) {
                    request = request.newBuilder().header("User-Agent", "Mozilla/5.0")
                        .header("Referer", "https://movie.douban.com/").build()
                }
                val response = chain.proceed(request)
                if(response.code !in listOf(301,302,303,307,308)) return@addInterceptor response
                val target = response.header("Location")?.let { url.resolve(it) }
                response.close()
                if(target == null || ++redirects > 3 || (url.isHttps && !target.isHttps)) throw IOException("Redirect policy")
                // Never copy cookies or authorization across hosts. App supplies neither.
                request = request.newBuilder().url(target).removeHeader("Authorization").removeHeader("Cookie").build()
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }.build()
    suspend fun text(url: String, cacheSearch: Boolean = false): String {
        val normalized = validUrl(url) ?: throw UserError("订阅或接口地址无效")
        if(!normalized.startsWith("https://",true) && !allowHttp()) throw UserError("HTTP 未启用")
        val key = hash(normalized)
        val owner = cache?.generation() ?: 0
        if(cacheSearch && cache != null) {
            try { withContext(Dispatchers.IO) { cache.get(key) }?.let { return it } }
            catch(e: CancellationException) { throw e } catch(_: IOException) { /* best-effort cache */ }
        }
        val result = download(normalized)
        if(cacheSearch && result.second > System.currentTimeMillis() && cache != null) {
            // Cache only structurally valid JSON. Subscription, detail and media never use this path.
            withContext(Dispatchers.Default) {
                val payload = JsonData.parse(result.first).obj() ?: throw UserError("搜索接口需要JSON对象")
                val list = payload.get("list")
                if(list != null && !list.isJsonNull && !list.isJsonArray) throw UserError("搜索接口list无效")
            }
            try { withContext(Dispatchers.IO) { cache.put(key,result.first,owner,(result.second-System.currentTimeMillis()).coerceAtLeast(0)) } }
            catch(e: CancellationException) { throw e } catch(_: IOException) { /* network result still usable */ }
        }
        return result.first
    }
    private suspend fun download(url: String): Pair<String, Long> {
        if(validUrl(url) == null) throw UserError("仅支持有效 HTTP(S) 地址，不支持用户名密码")
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(validUrl(url)!!).header("Accept", "application/json, application/x-mpegURL, text/plain").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if(continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val data = response.use {
                            if(!it.isSuccessful) throw UserError("服务器返回 HTTP ${it.code}")
                            val body = it.body ?: throw UserError("响应为空")
                            if(body.contentLength() > JsonData.LIMIT) throw UserError("下载超过 2 MiB 上限")
                            val buffer = java.io.ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val chunk = ByteArray(8192)
                                while(true) {
                                    val count = input.read(chunk); if(count < 0) break
                                    if(buffer.size() + count > JsonData.LIMIT) throw UserError("下载超过 2 MiB 上限")
                                    buffer.write(chunk,0,count)
                                }
                            }
                            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            val text = decoder.decode(java.nio.ByteBuffer.wrap(buffer.toByteArray())).toString()
                            val cc = it.cacheControl
                            val maxAge = cc.maxAgeSeconds
                            val age = it.header("Age")?.toLongOrNull()?.coerceAtLeast(0) ?: 0
                            val ttl = if(maxAge < 0) StorageBudget.RESPONSE_TTL else (maxAge.toLong()-age).coerceAtLeast(0).coerceAtMost(60)*1000
                            val cacheable = !cc.noStore && !cc.noCache && it.header("Set-Cookie") == null && it.header("Vary")?.trim() != "*"
                            text to if(cacheable && ttl > 0) it.receivedResponseAtMillis + ttl else 0L
                        }
                        if(continuation.isActive) continuation.resume(data)
                    } catch(e: Exception) { if(continuation.isActive) continuation.resumeWithException(e) }
                }
            })
        }
    }
    fun api(source: Source, key: String, value: String): String = source.url.toHttpUrl().newBuilder()
        .setQueryParameter("ac", if(key == "wd") "videolist" else "detail").setQueryParameter(key, value).build().toString()
    fun close() { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
}
