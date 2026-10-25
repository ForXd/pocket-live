package org.streampocket

import org.junit.Assert.*
import org.junit.Test

class StateRulesTest {
    private val shared = Source("shared","https://example.org/api")
    private val other = Source("other","https://example.net/api")
    private fun sub(id: String, sources: List<Source>) = Subscription(id,id,"https://example.org/$id.json",1,sources)
    @Test fun deletingOneOwnerPreservesSharedSourceDisableAndHistory() {
        val p = Playback(Video(shared,"1","movie"),Episode("1","https://example.org/1.mp4"))
        val saved = Saved(listOf(sub("a",listOf(shared)),sub("b",listOf(shared,other))),setOf(shared.key),listOf(History(p,1)))
        val result = StateRules.remove(saved,"a")
        assertEquals(listOf("b"),result.subscriptions.map { it.id })
        assertEquals(setOf(shared.key),result.disabled)
        assertEquals(saved.history,result.history)
        assertEquals(2,saved.subscriptions.size) // Original snapshot remains immutable.
    }
    @Test fun refreshKeepsOrderAndDisableByUrl() {
        val saved = Saved(listOf(sub("a",listOf(shared)),sub("b",listOf(other))),setOf(shared.key))
        val result = StateRules.importSubscription(saved,sub("a",listOf(shared.copy(name="renamed"))),true)
        assertEquals(listOf("a","b"),result.subscriptions.map { it.id })
        assertEquals(setOf(shared.key),result.disabled)
    }
    @Test fun deletedSubscriptionCannotBeResurrectedAndDuplicateCommitRejected() {
        val saved = Saved(listOf(sub("a",listOf(shared))))
        try { StateRules.importSubscription(saved,sub("b",listOf(other)),true); fail() } catch(_:UserError) {}
        try { StateRules.importSubscription(saved,sub("b",listOf(other)).copy(url=saved.subscriptions[0].url),false); fail() } catch(_:UserError) {}
        assertEquals(1,saved.subscriptions.size)
    }
    @Test fun historyIsBoundedDeduplicatedAndNewestFirst() {
        var saved=Saved()
        repeat(101) { i -> saved=StateRules.checkpoint(saved,Playback(Video(shared,"$i","movie"),Episode("episode","https://example.org/$i.mp4")),i.toLong()) }
        assertEquals(100,saved.history.size)
        assertEquals("100",saved.history.first().playback.video.id)
        assertFalse(saved.history.any { it.playback.video.id=="0" })
        val replay=saved.history.last().playback.copy(position=2500,sessionId="new-session")
        val result=StateRules.checkpoint(saved,replay,200)
        assertEquals(100,result.history.size)
        assertEquals(2500L,result.history.first().playback.position)
    }
    @Test fun episodeKeyStableButSessionOwnerChanges() {
        val first=Playback(Video(shared,"1","movie"),Episode("ep","https://example.org/1.mp4"))
        val next=first.copy(sessionId="another-session",position=500)
        assertEquals(first.key,next.key)
        assertNotEquals(first.sessionId,next.sessionId)
        assertNotEquals(first.key,first.copy(episode=Episode("ep2","https://example.org/2.mp4")).key)
    }
    @Test fun historyHasAByteBudgetAsWellAsCountLimit() {
        var saved=Saved()
        repeat(100) { i ->
            val source=shared.copy(url="https://example.org/api?token="+"x".repeat(3900))
            val p=Playback(Video(source,"$i","movie"),Episode("episode","https://example.org/$i.mp4?token="+"y".repeat(3900)))
            saved=StateRules.checkpoint(saved,p,i.toLong())
        }
        assertTrue(saved.history.size<100)
        assertTrue(saved.history.sumOf { StorageBudget.recordBytes(it.playback) } <= StorageBudget.HISTORY)
        assertEquals("99",saved.history.first().playback.video.id)
    }
}
