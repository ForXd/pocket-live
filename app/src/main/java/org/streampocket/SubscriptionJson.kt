// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

private val localJsonIdentity=Regex("local-json:[0-9a-f]{64}")
internal val Subscription.isLocalJson:Boolean get()=localJsonIdentity.matches(url)

/** The raw document and its URI are never persisted. Keep local identities out of HTTP paths. */
internal fun localSubscription(parsed:ImportResult, text:String, fileName:String, existing:Subscription?):Subscription {
    if(existing!=null && !existing.isLocalJson) throw UserError("链接订阅请使用在线更新")
    return Subscription(existing?.id ?: java.util.UUID.randomUUID().toString(),
        parsed.name ?: existing?.name ?: fileName.take(120).ifBlank { "本地 JSON" },
        "local-json:${hash(text)}",System.currentTimeMillis(),parsed.sources)
}

internal fun readSubscriptionJson(input:InputStream):String {
    val output=java.io.ByteArrayOutputStream()
    val buffer=ByteArray(8192)
    while(true) {
        if(Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
        val count=input.read(buffer)
        if(count<0) break
        if(output.size()+count>JsonData.LIMIT) throw UserError("JSON 文件超过 2 MiB 上限")
        output.write(buffer,0,count)
    }
    if(output.size()==0) throw UserError("JSON 文件为空")
    return try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
    } catch(_:java.nio.charset.CharacterCodingException) { throw UserError("请将 JSON 文件保存为 UTF-8 编码") }
}
