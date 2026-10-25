// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

/** Pure state transitions. Disk+memory commit remains serialized by AppModel's mutex. */
object StateRules {
    fun importSubscription(saved: Saved, sub: Subscription, replacing: Boolean): Saved {
        if(replacing && saved.subscriptions.none { it.id == sub.id }) throw UserError("订阅已删除")
        if(!replacing && saved.subscriptions.size >= 20) throw UserError("最多保留 20 个订阅")
        if(saved.subscriptions.any { it.id != sub.id && it.url == sub.url }) throw UserError("订阅已存在，请使用更新")
        val subscriptions = if(replacing) saved.subscriptions.map { if(it.id == sub.id) sub else it } else saved.subscriptions + sub
        if(subscriptions.sumOf { it.sources.size } > 300) throw UserError("总源数超过 300，请减少订阅")
        return withSources(saved, subscriptions)
    }
    fun remove(saved: Saved, id: String): Saved = withSources(saved, saved.subscriptions.filterNot { it.id == id })
    private fun withSources(saved: Saved, subscriptions: List<Subscription>): Saved {
        val keys = subscriptions.flatMap { it.sources }.map { it.key }.toSet()
        return saved.copy(subscriptions = subscriptions, disabled = saved.disabled.intersect(keys))
    }
    fun toggle(saved: Saved, source: Source): Saved {
        if(saved.subscriptions.none { sub -> sub.sources.any { it.key == source.key } }) return saved
        return saved.copy(disabled = if(source.key in saved.disabled) saved.disabled - source.key else saved.disabled + source.key)
    }
    fun checkpoint(saved: Saved, playback: Playback, now: Long): Saved {
        var bytes = 0L
        val history = (listOf(History(playback, now)) + saved.history.filterNot { it.playback.key == playback.key }).take(100)
            .takeWhile { bytes += StorageBudget.recordBytes(it.playback); bytes <= StorageBudget.HISTORY }
        return saved.copy(history = history)
    }
}
