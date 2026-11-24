// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import okhttp3.*
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Share the connection pool and dispatcher, but cancel only this playback's calls. */
class MediaCallOwner(base: OkHttpClient) : Call.Factory, AutoCloseable {
    private val calls = LinkedHashSet<Call>()
    private val unclaimed = mutableMapOf<Call, ResponseBody>()
    private var closed = false

    private val client = base.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .eventListener(object : EventListener() {
            override fun callEnd(call: Call) = finish(call)
            override fun callFailed(call: Call, ioe: IOException) = finish(call)
            override fun canceled(call: Call) {
                finish(call)
                synchronized(this@MediaCallOwner) { unclaimed.remove(call) }?.close()
            }
        })
        .apply { interceptors().add(0, Interceptor { chain -> trackResponse(chain.call(), chain.proceed(chain.request())) }) }
        .build()

    @Synchronized private fun finish(call: Call) {
        if (calls.remove(call)) active.decrementAndGet()
    }

    @Synchronized override fun newCall(request: Request): Call = client.newCall(request).also {
        if (closed) it.cancel() else {
            calls.add(it)
            active.incrementAndGet()
        }
    }

    // Media3 can interrupt its loader after a response reaches its future but before
    // it acquires the stream. Keep ownership until the consumer actually takes it.
    private fun trackResponse(call: Call, response: Response): Response {
        val body = response.body ?: return response
        synchronized(this) {
            if (!closed && !call.isCanceled()) {
                unclaimed[call] = body
                val wrapper = object : ResponseBody() {
                    private var claimed = false
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source(): BufferedSource = synchronized(this@MediaCallOwner) {
                        if (!claimed) {
                            if (unclaimed.remove(call) !== body) throw IOException("Media call canceled")
                            claimed = true
                        }
                        body.source()
                    }

                    override fun close() {
                        val ownsBody = synchronized(this@MediaCallOwner) { claimed || unclaimed.remove(call) === body }
                        if (ownsBody) body.close()
                    }
                }
                return response.newBuilder().body(wrapper).build()
            }
        }
        response.close()
        throw IOException("Media call canceled")
    }

    override fun close() {
        val pending: List<Call>
        val bodies: List<ResponseBody>
        synchronized(this) {
            if (closed) return
            closed = true
            pending = calls.toList()
            bodies = unclaimed.values.toList()
            active.addAndGet(-calls.size)
            calls.clear()
            unclaimed.clear()
        }
        // Interrupt active readers; the data source closes its own stream. Bodies
        // that never reached a reader can now be closed without racing its reads.
        pending.forEach(Call::cancel)
        bodies.forEach(ResponseBody::close)
    }

    companion object { internal val active = AtomicInteger() }
}
