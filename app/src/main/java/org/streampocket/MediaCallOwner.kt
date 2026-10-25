// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Own only this playback's calls. Share the app connection pool and dispatcher;
 * never create an executor per player or cancel another session's requests. */
class MediaCallOwner(base:OkHttpClient):Call.Factory,AutoCloseable {
    private val calls=LinkedHashSet<Call>()
    private var closed=false
    private val client=base.newBuilder().callTimeout(0,TimeUnit.SECONDS).readTimeout(12,TimeUnit.SECONDS)
        .eventListener(object:EventListener() {
            override fun callEnd(call:Call) { finish(call) }
            override fun callFailed(call:Call,ioe:IOException) { finish(call) }
        }).build()
    @Synchronized private fun finish(call:Call) { if(calls.remove(call)) active.decrementAndGet() }
    @Synchronized override fun newCall(request:Request):Call = client.newCall(request).also {
        if(closed) it.cancel() else { calls.add(it);active.incrementAndGet() }
    }
    override fun close() {
        val pending=synchronized(this) { closed=true;calls.toList() }
        // Do not hold our monitor while invoking OkHttp cancellation/listeners.
        pending.forEach { it.cancel();finish(it) }
    }
    companion object { internal val active=java.util.concurrent.atomic.AtomicInteger() }
}
