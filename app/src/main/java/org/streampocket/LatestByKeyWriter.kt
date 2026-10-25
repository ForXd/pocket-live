// SPDX-License-Identifier: AGPL-3.0-or-later
// Added 2026-10-24: bounded, coalesced progress persistence.
package org.streampocket

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Main-confined: one active write and at most capacity pending keys; newest value wins per key. */
internal class LatestByKeyWriter<T>(scope: CoroutineScope, private val capacity: Int,
    private val write: suspend (T) -> Unit, private val failed: (Exception) -> Unit) {
    private val pending=LinkedHashMap<String,T>()
    private val signal=Channel<Unit>(Channel.CONFLATED)
    private var closing=false
    val pendingCount: Int get()=pending.size
    init {
        require(capacity>0)
        scope.launch(start=CoroutineStart.UNDISPATCHED) {
            suspend fun drain() {
                while(pending.isNotEmpty()) {
                    val key=pending.keys.first(); val item=pending.remove(key)!!
                    try { withContext(NonCancellable) { write(item) } }
                    catch(e: CancellationException) { throw e }
                    catch(e: Exception) { failed(e) }
                }
            }
            try { for(ignored in signal) drain() }
            finally { closing=true; withContext(NonCancellable) { drain() }; signal.close() }
        }
    }

    fun submit(key: String, value: T) {
        if(closing) return
        pending.remove(key);pending[key]=value
        while(pending.size>capacity) pending.remove(pending.keys.first())
        signal.trySend(Unit)
    }
    fun remove(keys:Set<String>) { keys.forEach { pending.remove(it) } }
    fun clear() { pending.clear() }
}
