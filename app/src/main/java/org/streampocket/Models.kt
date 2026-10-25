// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import java.security.MessageDigest

enum class SourceKind { VOD, LIVE }
data class Source(val name: String, val url: String, val kind: SourceKind = SourceKind.VOD,
    val detail: String? = null, val adult: Boolean = false, val epg: String? = null) {
    val key: String = hash("${kind.name}|$url")
}
data class Subscription(val id: String, val name: String, val url: String, val updated: Long, val sources: List<Source>)
data class ImportResult(val name: String?, val sources: List<Source>, val skipped: Int, val truncated: Int, val format: String, val skippedByReason: Map<String, Int> = emptyMap())
data class Video(val source: Source, val id: String, val title: String, val subtitle: String = "", val quality: VideoQuality = VideoQuality(), val poster: String? = null)
data class Episode(val name: String, val url: String, val quality: VideoQuality = VideoQuality())
data class Line(val name: String, val episodes: List<Episode>)
data class Detail(val video: Video, val description: String, val lines: List<Line>)
data class Playback(val video: Video, val episode: Episode, val position: Long = 0,
    val autoPlay: Boolean = true, val sessionId: String = java.util.UUID.randomUUID().toString()) {
    val key: String = hash("${video.source.key}|${video.id}|${episode.url}")
}
data class History(val playback: Playback, val updated: Long)
data class Saved(val subscriptions: List<Subscription> = emptyList(), val disabled: Set<String> = emptySet(), val history: List<History> = emptyList(), val allowHttp: Boolean = false, val adFilter: Boolean = false, val adRules: Map<String,List<String>> = emptyMap())
class UserError(message: String) : Exception(message)
fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
fun safeError(e: Throwable): String = when(e) {
    is UserError -> e.message ?: "数据无效"
    is java.net.SocketTimeoutException, is kotlinx.coroutines.TimeoutCancellationException -> "请求超时，可重试或换源"
    is java.io.IOException -> "网络失败，请检查连接或换源"
    else -> "数据格式错误或操作失败"
}

data class StorageUsage(val memoryBytes: Long, val responseBytes: Long, val entries: Int, val userDataBytes: Long, val posterBytes: Long = 0)
