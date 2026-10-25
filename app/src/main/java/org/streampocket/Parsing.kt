// SPDX-License-Identifier: AGPL-3.0-or-later
// Protocol compatibility reference: LibreTV b08c8242, src/lib/{source-list,tvbox-parser,cms-parser}.ts.
package org.streampocket

import com.google.gson.*
import com.google.gson.stream.JsonReader
import java.io.StringReader
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object JsonData {
    const val LIMIT = 2 * 1024 * 1024
    fun parse(text: String, maxBytes: Int = LIMIT): JsonElement {
        if (text.length > maxBytes || text.toByteArray().size > maxBytes) throw UserError("内容超过 ${maxBytes / 1024 / 1024} MiB 上限")
        // Remove only comments outside strings. Match LibreTV's BOM/comment/trailing-comma support.
        val out = StringBuilder(); var i = 0; var quoted = false
        val t = text.removePrefix("\uFEFF")
        while (i < t.length) {
            val c = t[i]
            if (quoted) {
                if (c == '\\') { out.append(c); i++; if (i < t.length) out.append(t[i]); i++; continue }
                if (c == '"') quoted = false
                if (c.code < 32) out.append("\\u%04x".format(c.code)) else out.append(c)
            } else when {
                c == '"' -> { quoted = true; out.append(c) }
                c == '/' && t.getOrNull(i+1) == '/' -> { while(i < t.length && t[i] != '\n' && t[i] != '\r') i++; out.append('\n'); continue }
                c == '/' && t.getOrNull(i+1) == '*' -> {
                    val end = t.indexOf("*/", i+2); if(end < 0) throw UserError("JSON 注释未结束")
                    i = end + 2; continue
                }
                else -> out.append(c)
            }
            i++
        }
        val clean = StringBuilder(); quoted = false; i = 0; var depth = 0; var tokens = 0
        while(i < out.length) {
            val c = out[i]
            if(quoted) {
                clean.append(c)
                if(c == '\\') { i++; if(i < out.length) clean.append(out[i]) }
                else if(c == '"') quoted = false
            } else {
                if(c == '"') quoted = true
                if(c == '[' || c == '{' || c == ',' || c == ':') {
                    tokens++; if(tokens > 100_000) throw UserError("JSON 条目结构过多")
                }
                if(c == '[' || c == '{') { depth++; if(depth > 24) throw UserError("JSON 嵌套过深") }
                if(c == ']' || c == '}') { depth--; if(depth < 0) throw UserError("JSON 结构不匹配") }
                if(c == ',') {
                    var j = i+1; while(j < out.length && out[j].isWhitespace()) j++
                    if(j < out.length && (out[j] == '}' || out[j] == ']')) { i++; continue }
                }
                clean.append(c)
            }
            i++
        }
        return try {
            val reader = JsonReader(StringReader(clean.toString())).apply { strictness = Strictness.STRICT }
            val result = JsonParser.parseReader(reader)
            if(reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) throw UserError("JSON 尾部存在额外内容")
            result
        } catch(e: UserError) { throw e } catch(e: Exception) { throw UserError("订阅或接口内容不是合法 JSON") }
    }
}
internal fun JsonElement.obj(): JsonObject? = if(isJsonObject) asJsonObject else null
internal fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()?.takeIf { it.isNotEmpty() }
internal fun JsonObject.scalar(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
internal fun JsonObject.arr(key: String): List<JsonElement> = get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
internal fun JsonObject.bool(key: String): Boolean? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
// TVBOX uses JavaScript Number() for finite numeric strings, including hex/bin/oct.
internal fun JsonObject.numberLikeJs(key: String): Double? {
    val raw = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
    if(!raw.isString && !raw.isNumber) return null
    val value = raw.asString.trim(); if(value.isEmpty()) return null
    fun integer(radix: Int): Double? {
        val digits = value.drop(2).trimStart('0').ifEmpty { "0" }
        // Anything longer cannot represent a finite JS Number; avoid giant BigInteger work.
        if(digits.length > 1024 || !digits.all { it.digitToIntOrNull(radix) != null }) return null
        return try { java.math.BigInteger(digits, radix).toDouble() } catch(_:NumberFormatException) { null }
    }
    val prefixed = when {
        value.startsWith("0x", true) && value.length > 2 -> integer(16)
        value.startsWith("0b", true) && value.length > 2 -> integer(2)
        value.startsWith("0o", true) && value.length > 2 -> integer(8)
        Regex("^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?$").matches(value) -> value.toDoubleOrNull()
        else -> null
    }
    return prefixed?.takeIf { it.isFinite() }
}
fun validUrl(value: String?, trimSlash: Boolean = false): String? {
    val s = value?.trim()?.replace(Regex("[?#]+$"), "") ?: return null
    if(s.length > 4096 || !Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(s) || s.any { it.code < 32 || it.code == 127 }) return null
    val url = s.toHttpUrlOrNull() ?: return null
    if(url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    return if(trimSlash) s.trimEnd('/') else s
}
object SubscriptionParser {
    fun parse(text: String): ImportResult {
        val root = JsonData.parse(text); val obj = root.obj()
        val bare = if(root.isJsonArray) root.asJsonArray.toList() else null
        val catalogCount = bare?.count { it.obj()?.has("baseUrl") == true } ?: 0
        if(catalogCount > 0 && catalogCount != bare?.size) throw UserError("订阅混用 baseUrl 与 url，请统一格式")
        val tvbox = obj?.get("sites")?.isJsonArray == true || obj?.get("lives")?.isJsonArray == true
        val catalog = catalogCount > 0
        val vod = when { tvbox -> obj!!.arr("sites"); bare != null -> bare; else -> obj?.arr("sources").orEmpty() }
        val live = if(tvbox) obj!!.arr("lives") else obj?.arr("liveSources").orEmpty()
        if(vod.isEmpty() && live.isEmpty()) throw UserError("未知或空订阅：需要 sources/liveSources、sites/lives 或源数组")
        if(vod.size + live.size > 5000) throw UserError("订阅条目超过 5000 条")
        val sources = mutableListOf<Source>(); val seen = mutableSetOf<String>(); var truncated = 0
        val reasons = linkedMapOf<String, Int>()
        fun skip(reason: String) { reasons[reason] = (reasons[reason] ?: 0) + 1 }
        fun add(item: JsonElement, kind: SourceKind) {
            val s = item.obj(); if(s == null) { if(!tvbox) skip("地址不可用"); return }
            if(catalog && s.bool("enabled") == false) { skip("已停用"); return }
            val raw = s.str(if(tvbox && kind == SourceKind.VOD) "api" else if(catalog) "baseUrl" else "url")
            if(tvbox && kind == SourceKind.VOD) {
                val type = s.numberLikeJs("type")
                if(raw == null) { skip("地址不可用"); return }
                val script = Regex("^csp_|\\.(?:jar|js|py)(?:[?#].*)?$", RegexOption.IGNORE_CASE).containsMatchIn(raw)
                if(type == 3.0 || script || Regex("^\\.{0,2}/").containsMatchIn(raw)) { skip("Spider 引擎"); return }
                if(validUrl(raw) == null) { skip("地址不可用"); return }
                val searchable = s.get("searchable")
                val noSearch = searchable?.isJsonPrimitive == true && (
                    (searchable.asJsonPrimitive.isNumber && searchable.asDouble == 0.0) ||
                    (searchable.asJsonPrimitive.isString && searchable.asString == "0"))
                if(noSearch) { skip("不支持搜索"); return }
                val cms = Regex("/api\\.php/provide/vod(?![^?#]*/at/xml)(?:[/?#]|$)", RegexOption.IGNORE_CASE).containsMatchIn(raw)
                if(!(type == 1.0 || ((type == null || type == 0.0 || type == 4.0) && cms))) {
                    skip(if(type == 4.0) "Spider 引擎" else "XML 接口"); return
                }
            }
            val url = validUrl(raw, kind == SourceKind.VOD)
            if(url == null) { skip("地址不可用"); return }
            if(tvbox && kind == SourceKind.LIVE) {
                val type = s.numberLikeJs("type")
                if((type != null && type != 0.0) || Regex("\\.txt(?:[?#].*)?$", RegexOption.IGNORE_CASE).containsMatchIn(url)) { skip("非 M3U 直播"); return }
            }
            val key = "$kind|$url"; if(!seen.add(key)) return
            val limit = if(kind == SourceKind.VOD) 100 else 50
            if(sources.count { it.kind == kind } >= limit) { truncated++; return }
            sources += Source(s.str("name")?.take(120) ?: url.toHttpUrlOrNull()!!.host, url, kind,
                if(tvbox || catalog || kind != SourceKind.VOD) null else s.str("detail")?.take(4096),
                !tvbox && !catalog && kind == SourceKind.VOD && s.bool("isAdult") == true,
                if(kind == SourceKind.LIVE) validUrl(s.str("epg")) else null)
        }
        vod.forEach { add(it, SourceKind.VOD) }; live.forEach { add(it, SourceKind.LIVE) }
        val skipped = reasons.values.sum()
        if(sources.isEmpty()) throw UserError("没有可导入源：${reasons.entries.joinToString { it.key + ":" + it.value }}")
        return ImportResult(obj?.str("name")?.take(120), sources, skipped, truncated, if(tvbox) "TVBOX" else if(catalog) "source-catalog" else "LibreTV-SourceList", reasons.toMap())
    }
}
object CmsParser {
    fun search(text: String, source: Source): List<Video> {
        val root = JsonData.parse(text).obj() ?: throw UserError("接口需要 JSON 对象")
        val raw = root.get("list")
        if(raw != null && !raw.isJsonNull && !raw.isJsonArray) throw UserError("接口 list 不是数组")
        return root.arr("list").take(100).mapNotNull { it.obj() }.mapNotNull {
            val id = it.scalar("vod_id").take(256); val title = it.str("vod_name")?.take(200)
            if(id.isEmpty() || title == null) null else Video(source, id, title, listOf(it.scalar("vod_year"), it.scalar("vod_remarks")).filter { it.isNotBlank() }.joinToString(" · ").take(200), QualityRules.api(it), validUrl(it.str("vod_pic")))
        }
    }
    fun detail(text: String, video: Video): Detail {
        val item = JsonData.parse(text).obj()?.arr("list")?.firstOrNull()?.obj() ?: throw UserError("详情列表为空")
        val names = item.scalar("vod_play_from").split("$$$")
        var lines = item.scalar("vod_play_url").split("$$$", limit = 21).take(20).mapIndexedNotNull { index, group ->
            val episodes = group.split('#', limit = 501).take(500).mapNotNull { ep ->
                val split = ep.indexOf('$'); if(split < 0) return@mapNotNull null
                val url = validUrl(ep.substring(split+1)) ?: return@mapNotNull null
                Episode(ep.substring(0, split).take(100).ifEmpty { "播放" }, url, QualityRules.sourceLabel(ep.substring(0,split)))
            }
            if(episodes.isEmpty()) null else Line(names.getOrNull(index)?.take(100)?.ifEmpty { null } ?: "线路 ${index+1}", episodes)
        }
        if(lines.isEmpty()) {
            val fallback = Regex("""\${'$'}https?://[^"'\s]+?\.m3u8""").findAll(item.scalar("vod_content")).take(500)
                .mapNotNull { validUrl(it.value.drop(1)) }.distinct().mapIndexed { i, url -> Episode("第 ${i+1} 集", url) }.toList()
            if(fallback.isNotEmpty()) lines = listOf(Line("简介中的直连地址", fallback))
        }
        if(lines.isEmpty()) throw UserError("没有直连播放地址；不执行网页解析、脚本或 DRM 解锁")
        return Detail(if(video.quality.evidence==QualityEvidence.MEASURED) video else video.copy(quality=QualityRules.api(item)), item.scalar("vod_content").replace(Regex("<[^>]*>"), "").take(4000), lines)
    }
    fun playlist(text: String, source: Source): List<Video> {
        if(!text.trimStart('\uFEFF',' ','\n','\r').startsWith("#EXTM3U")) throw UserError("直播源不是 M3U 播放列表")
        if(text.contains("#EXT-X-TARGETDURATION") || text.contains("#EXT-X-STREAM-INF")) throw UserError("需要频道列表，不是 HLS 媒体清单")
        var name: String? = null; val result = mutableListOf<Video>()
        for(line in text.lineSequence()) {
            val s = line.trim()
            if(s.startsWith("#EXTINF:")) name = s.substringAfter(',', "频道").take(200)
            else if(s.isNotBlank() && !s.startsWith('#')) {
                val url = validUrl(s); if(url != null && name != null) result += Video(source, url, name)
                name = null
            }
            if(result.size >= 500) break
        }
        return result
    }
}
