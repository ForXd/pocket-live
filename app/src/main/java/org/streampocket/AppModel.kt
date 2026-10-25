// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

data class UiState(val loading: Boolean = true, val loadFailed: Boolean = false, val saved: Saved = Saved(), val results: List<Video> = emptyList(),
    val searching: Boolean = false, val completed: Int = 0, val total: Int = 0, val failures: Int = 0,
    val detail: Detail? = null, val detailLoading: Boolean = false, val detailError:String? = null, val playback: Playback? = null,
    val catalogSource: Source? = null, val categories: List<Category> = emptyList(), val catalogVideos: List<Video> = emptyList(),
    val catalogCategory: String? = null, val catalogQuery: String = "", val catalogPage: Int = 0, val catalogMore: Boolean = false, val catalogLoading: Boolean = false, val catalogError: String? = null,
    val recommendations: List<Video> = emptyList(), val recommendType: String = "tv", val recommendTag: String = "热门",
    val recommendPage: Int = 0, val recommendLoading: Boolean = false, val recommendMore: Boolean = false, val recommendError: String? = null,
    val alternativeSession: String? = null, val alternatives: List<PlaybackAlternative> = emptyList(),
    val alternativesLoading: Boolean = false, val alternativesError: String? = null, val smoothPlayback: Boolean = false,
    val importing: Set<String> = emptySet(), val storageUsage: StorageUsage? = null, val message: String? = null)
class AppModel @JvmOverloads constructor(application: Application, dns: okhttp3.Dns = PublicNetwork.dns,
    private val recommendationUrl: (String,String,Int)->String = Recommendations::url) : AndroidViewModel(application) {
    private val responseCache = ResponseCache(java.io.File(application.cacheDir,"responses-v1"))
    val posters = PosterStore(java.io.File(application.cacheDir,"posters-v1"))
    private val store = Storage(application, purgeCache={ responseCache.clear(); posters.clear() })
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private val lock = Mutex()
    val network = Network({ _state.value.saved.allowHttp }, dns=dns, cache = responseCache)
    private var storageRefreshJob: Job?=null
    private var cacheClearJob: Job?=null
    private var memoryTrimJob: Job?=null
    private val historyOwners=LinkedHashMap<String,String>()
    private val measuredSessions=MeasuredQualityBuffer()
    private var qualityFlushJob:Job?=null
    private var qualityCatalogJob:Job?=null
    private fun own(p: Playback) {
        refreshMeasuredCatalog(state.value.playback)
        qualityFlushJob?.cancel()
        historyOwners.remove(p.key);historyOwners[p.key]=p.sessionId
        while(historyOwners.size>100) historyOwners.remove(historyOwners.keys.first())
    }
    private var alternativesJob: Job? = null
    private var alternativesOwner=0L
    private var recommendJob: Job? = null
    private var recommendOwner = 0L
    private var catalogJob: Job? = null
    private var catalogOwner=0L
    private var searchJob: Job? = null
    private var searchOrderPinned=false
    fun pinSearchOrder() { searchOrderPinned=true }
    fun sortByQuality() { searchOrderPinned=false;_state.update { it.copy(results=QualityRules.sorted(it.results)) } }
    private var detailJob: Job? = null
    private var detailOwner:String?=null
    private var detailSession:String?=null
    private val importJobs = mutableMapOf<String, Job>()
    private val importOwners = mutableMapOf<String, String>()
    private val historyWriter=LatestByKeyWriter<Playback>(viewModelScope,100,
        write={ playback ->
            lock.withLock {
                if(historyOwners[playback.key]?.let { it!=playback.sessionId } == true) return@withLock
                val saved=_state.value.saved
                val old=saved.history.firstOrNull { it.playback.key==playback.key }
                if(old == null || old.playback.position != playback.position || old.playback.video.quality != playback.video.quality || old.playback.episode.quality != playback.episode.quality) {
                    val next=StateRules.checkpoint(saved,playback,System.currentTimeMillis())
                    withContext(NonCancellable) { store.writeHistory(next); _state.update { it.copy(saved=next) } }
                }
            }
        }, failed={ message(safeError(it)) })
    init { viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) { try { responseCache.maintain() } catch(_: java.io.IOException) { /* Disposable cache must not block user data. */ } }
            val saved = store.read(); _state.update { it.copy(loading = false, saved = saved) }
            refreshStorage()
        }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { _state.update { it.copy(loading = false, loadFailed = true, message = safeError(e)) } }
    } }
    fun message(value: String?) { _state.update { it.copy(message = value) } }
    private suspend fun mutate(block: (Saved) -> Saved) = lock.withLock {
        currentCoroutineContext().ensureActive()
        val next = block(_state.value.saved)
        // Complete disk+memory transaction even if screen/navigation cancellation arrives during write.
        withContext(NonCancellable) { store.write(next); _state.update { it.copy(saved = next) } }
    }
    private fun change(clearSourceResults: Boolean = false, block: (Saved) -> Saved) {
        if(state.value.loading || state.value.loadFailed) return
        viewModelScope.launch { try { mutate(block); if(clearSourceResults) invalidateSources() } catch(e: CancellationException) { throw e } catch(e: Exception) { message(safeError(e)) } }
    }
    private fun invalidateSources() {
        closeAlternatives(); cancelSearch(); cancelCatalog(); closeDetail(); clearCache(silent = true)
        _state.update { it.copy(catalogSource=null,catalogVideos=emptyList(),categories=emptyList(),catalogMore=false) }
        _state.update { it.copy(results = emptyList(), completed = 0, total = 0, failures = 0) }
    }
    fun allowHttp(enabled: Boolean) {
        if(!enabled) { network.client.dispatcher.cancelAll(); invalidateSources(); stopPlayback() }
        change(clearSourceResults = true) { it.copy(allowHttp = enabled) }
    }
    fun toggle(source: Source) {
        change(clearSourceResults = true) { StateRules.toggle(it, source) }
    }
    fun clearHistory() {
        historyWriter.clear()
        historyOwners.keys.toList().forEach { historyOwners[it]="deleted" }
        change { it.copy(history = emptyList()) }
    }
    fun removeHistoryGroup(key:HistoryGroupKey) {
        val keys=state.value.saved.history.filter { HistoryGroups.key(it.playback.video)==key }.map { it.playback.key }.toMutableSet()
        state.value.playback?.takeIf { HistoryGroups.key(it.video)==key }?.let { keys.add(it.key) }
        historyWriter.remove(keys)
        keys.filter { it in historyOwners }.forEach { historyOwners[it]="deleted" }
        change { HistoryGroups.remove(it,key) }
    }
    fun delete(id: String) {
        cancelImport(id)
        change(clearSourceResults = true) { StateRules.remove(it, id) }
    }
    fun importSubscription(url: String, existing: Subscription? = null) {
        if(state.value.loading || state.value.loadFailed) return
        val normalized = validUrl(url, true) ?: return message("请输入有效的 HTTP(S) 订阅链接")
        if(!normalized.startsWith("https://", true) && !state.value.saved.allowHttp) return message("HTTP 未加密，请先在订阅页明确启用")
        if(existing == null && state.value.saved.subscriptions.any { it.url == normalized }) return message("订阅已存在，请使用更新")
        val key = existing?.id ?: "new"
        val owner = UUID.randomUUID().toString()
        importOwners[key] = owner
        val previous = importJobs[key]
        previous?.cancel()
        importJobs[key] = viewModelScope.launch {
            try {
                previous?.join()
                _state.update { it.copy(importing = it.importing + key) }
                val parsed = withContext(Dispatchers.Default) { SubscriptionParser.parse(network.text(normalized)) }
                ensureActive()
                mutate { saved ->
                    val sub = Subscription(existing?.id ?: UUID.randomUUID().toString(),parsed.name ?: existing?.name ?: "我的订阅",normalized,System.currentTimeMillis(),parsed.sources)
                    StateRules.importSubscription(saved, sub, replacing = existing != null)
                }
                ensureActive()
                invalidateSources()
                message("${parsed.format}：导入 ${parsed.sources.size} 条，跳过 ${parsed.skipped}，超限 ${parsed.truncated}；${parsed.skippedByReason.entries.joinToString { it.key + ":" + it.value }}")
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { message(safeError(e)) }
            finally {
                if(importOwners[key] == owner) {
                    importOwners.remove(key)
                    if(importJobs[key] === currentCoroutineContext()[Job]) importJobs.remove(key)
                    _state.update { it.copy(importing = it.importing - key) }
                }
            }
        }
    }
    fun cancelImport(key: String) { importJobs[key]?.cancel() }
    fun cancelSearch() { searchJob?.cancel(); searchJob = null; _state.update { it.copy(searching = false) } }
    fun search(query: String) {
        cancelSearch()
        searchOrderPinned=false
        val q = query.trim().take(100); if(q.isEmpty()) return
        val sources = state.value.saved.subscriptions.flatMap { it.sources }.distinctBy { it.key }.filter { it.key !in state.value.saved.disabled }
        if(sources.isEmpty()) return message("请先添加订阅并启用至少一个源")
        _state.update { it.copy(results = emptyList(), searching = true, completed = 0, total = sources.size, failures = 0) }
        searchJob = viewModelScope.launch {
            val semaphore = Semaphore(4)
            try { withTimeout(45_000) { supervisorScope { sources.forEach { source -> launch {
                semaphore.withPermit {
                    var failed = false
                    try {
                        val list = withTimeout(22_000) { withContext(Dispatchers.Default) {
                            if(source.kind == SourceKind.LIVE) CmsParser.playlist(network.text(source.url),source).filter { it.title.contains(q,true) }
                            else CmsParser.search(network.text(network.api(source,"wd",q), cacheSearch = true),source)
                        } }
                        ensureActive()
                        val known=state.value.saved.history.map { it.playback.video }.filter { it.quality.evidence==QualityEvidence.MEASURED }.asReversed().associateBy { it.source.key+":"+it.id }
                        val enriched=list.map { v -> known[v.source.key+":"+v.id]?.let { v.copy(quality=it.quality) } ?: v }
                        _state.update { it.copy(results = QualityRules.merge(it.results,enriched)) }
                    } catch(e: TimeoutCancellationException) { failed = true }
                    catch(e: CancellationException) { throw e }
                    catch(e: Exception) { failed = true }
                    ensureActive()
                    _state.update { it.copy(completed = it.completed + 1, failures = it.failures + if(failed) 1 else 0) }
                }
            } } } } } catch(_: TimeoutCancellationException) { message("搜索达到45秒总时限，已保留返回结果；可换源或重试") } finally { if(currentCoroutineContext().isActive) _state.update { it.copy(searching = false,results=if(searchOrderPinned) it.results else QualityRules.sorted(it.results)) } }
        }
    }
    fun filterAds(enabled: Boolean) = change { it.copy(adFilter=enabled) }
    fun adPaths(source: Source, text: String) {
        val paths=text.lines().map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if(paths.size>32 || paths.any { !it.startsWith("/") || it.length>512 || '?' in it || '#' in it || '*' in it }) {
            message("每行填写一个精确路径，最多32条；不接受域名、查询参数或通配符");return
        }
        change { it.copy(adRules=it.adRules + (source.key to paths)) }
    }
    fun cancelCatalog() { catalogOwner++;catalogJob?.cancel();catalogJob=null;_state.update { it.copy(catalogLoading=false) } }
    fun browse(source: Source, category: String? = null, query: String = "", more: Boolean = false) {
        val before=state.value
        if(source.kind!=SourceKind.VOD || source.key in before.saved.disabled || before.saved.subscriptions.none { sub -> sub.sources.any { it.key==source.key } }) return
        if(more && (before.catalogLoading || !before.catalogMore || before.catalogSource?.key!=source.key || before.catalogCategory!=category || before.catalogQuery!=query)) return
        cancelCatalog();val owner=catalogOwner
        val page=if(more) before.catalogPage+1 else 1
        if(page>Catalog.MAX_PAGES) return
        _state.update { it.copy(catalogSource=source,catalogCategory=category,catalogQuery=query,catalogLoading=true,catalogError=null,
            catalogVideos=if(more) it.catalogVideos else emptyList(),categories=if(it.catalogSource?.key==source.key) it.categories else emptyList(),catalogMore=false) }
        catalogJob=viewModelScope.launch {
            try {
                val result=withTimeout(22_000) { withContext(Dispatchers.Default) { Catalog.parse(network.text(Catalog.url(source,category,query,page),true),source,page) } }
                ensureActive()
                if(owner==catalogOwner) _state.update { it.copy(catalogVideos=(it.catalogVideos+result.videos).distinctBy { v -> v.source.key+":"+v.id }.take(1000),
                    categories=if(result.categories.isEmpty()) it.categories else result.categories,catalogLoading=false,catalogPage=page,catalogMore=result.hasMore) }
            } catch(e: CancellationException) { if(e is TimeoutCancellationException && owner==catalogOwner) _state.update { it.copy(catalogLoading=false,catalogError="请求超时，请重试") } else throw e }
            catch(e: Exception) { if(owner==catalogOwner) _state.update { it.copy(catalogLoading=false,catalogError=safeError(e)) } }
        }
    }
    fun recommend(type: String = state.value.recommendType, tag: String = if(type == state.value.recommendType) state.value.recommendTag else "热门", more: Boolean = false) {
        val before = state.value
        if(type !in listOf("tv", "movie") || tag !in Recommendations.tags(type)) return
        if(more && (before.recommendLoading || !before.recommendMore || type != before.recommendType || tag != before.recommendTag)) return
        val page = if(more) before.recommendPage + 1 else 1
        if(page > Recommendations.MAX_PAGES) return
        recommendJob?.cancel(); val owner = ++recommendOwner
        _state.update { it.copy(recommendType=type, recommendTag=tag, recommendLoading=true, recommendError=null,
            recommendations=if(more) it.recommendations else emptyList()) }
        recommendJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) { Recommendations.parse(network.text(recommendationUrl(type, tag, page), true), page) }
                ensureActive()
                if(owner == recommendOwner) _state.update { it.copy(recommendations=(it.recommendations+result.videos).distinctBy { v -> v.id },
                    recommendLoading=false, recommendPage=page, recommendMore=result.more) }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(owner == recommendOwner) _state.update { it.copy(recommendLoading=false, recommendError=safeError(e)) } }
        }
    }
    fun open(video: Video) = loadDetail(video)
    private fun cancelDetailRequest() {
        detailOwner=null;detailSession=null;detailJob?.cancel();detailJob=null
    }
    private fun loadDetail(video:Video,sessionId:String?=null) {
        cancelDetailRequest()
        val owner=UUID.randomUUID().toString();detailOwner=owner;detailSession=sessionId
        _state.update { it.copy(detail=null,detailLoading=true,detailError=null) }
        fun isCurrent()=detailOwner==owner && (sessionId==null || state.value.playback?.sessionId==sessionId)
        detailJob=viewModelScope.launch {
            try {
                val detail=if(video.source.kind==SourceKind.LIVE) Detail(video,"直播频道",listOf(Line("直播",listOf(Episode(video.title,video.id)))))
                else withContext(Dispatchers.Default) { CmsParser.detail(network.text(network.api(video.source,"ids",video.id)),video) }
                ensureActive()
                if(isCurrent()) _state.update { it.copy(detail=detail,detailLoading=false,detailError=null) }
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) {
                if(isCurrent()) _state.update { it.copy(detailLoading=false,detailError=safeError(e),message=if(sessionId==null) safeError(e) else it.message) }
            }
        }
    }
    fun smoothPlayback(enabled:Boolean) { _state.update { it.copy(smoothPlayback=enabled) } }
    fun closeAlternatives() {
        alternativesOwner++; alternativesJob?.cancel();alternativesJob=null
        _state.update { it.copy(alternativeSession=null,alternatives=emptyList(),alternativesLoading=false,alternativesError=null) }
    }
    fun openAlternatives(sessionId:String) {
        val current=state.value.playback?.takeIf { it.sessionId==sessionId && it.video.source.kind==SourceKind.VOD } ?: return
        closeAlternatives(); val owner=alternativesOwner
        val snapshot=state.value
        val detail=snapshot.detail?.takeIf { it.video.source.key==current.video.source.key && it.video.id==current.video.id }
        val local=detail?.let { PlaybackAlternatives.from(it,current) }.orEmpty()
        _state.update { it.copy(alternativeSession=sessionId,alternatives=local,alternativesLoading=true) }
        fun active()=alternativesOwner==owner && state.value.playback?.sessionId==sessionId
        fun add(options:List<PlaybackAlternative>) { if(active()) _state.update { it.copy(alternatives=PlaybackAlternatives.merge(it.alternatives,options)) } }
        alternativesJob=viewModelScope.launch {
            var failures=0
            try {
                withTimeout(18_000) {
                    supervisorScope {
                        if(detail==null) launch {
                            try {
                                val d=withContext(Dispatchers.Default) { CmsParser.detail(network.text(network.api(current.video.source,"ids",current.video.id)),current.video) }
                                ensureActive();add(PlaybackAlternatives.from(d,current))
                            } catch(e:CancellationException) { throw e } catch(_:Exception) { failures++ }
                        }
                        val slots=Semaphore(3)
                        snapshot.saved.subscriptions.flatMap { it.sources }.distinctBy { it.key }
                            .filter { it.kind==SourceKind.VOD && it.key !in snapshot.saved.disabled }
                            .take(PlaybackAlternatives.MAX_SOURCES).forEach { source -> launch {
                                slots.withPermit {
                                    try {
                                        withTimeout(12_000) {
                                            val known=snapshot.results.filter { it.source.key==source.key && PlaybackAlternatives.title(it.title)==PlaybackAlternatives.title(current.video.title) }
                                            val videos=if(known.isNotEmpty()) known else withContext(Dispatchers.Default) {
                                                CmsParser.search(network.text(network.api(source,"wd",current.video.title),true),source)
                                            }
                                            for(video in videos.filter { PlaybackAlternatives.title(it.title)==PlaybackAlternatives.title(current.video.title) }
                                                .filterNot { it.source.key==current.video.source.key && it.id==current.video.id }.take(2)) {
                                                val d=withContext(Dispatchers.Default) { CmsParser.detail(network.text(network.api(source,"ids",video.id)),video) }
                                                ensureActive();add(PlaybackAlternatives.from(d,current))
                                            }
                                        }
                                    } catch(e:TimeoutCancellationException) { failures++ }
                                    catch(e:CancellationException) { throw e }
                                    catch(_:Exception) { failures++ }
                                }
                            } }
                    }
                }
            } catch(_:TimeoutCancellationException) { failures++ }
            catch(e:CancellationException) { throw e }
            finally {
                if(active()) _state.update { it.copy(alternativesLoading=false,alternativesError=
                    if(failures>0) "部分来源暂不可用，已保留可用结果" else null) }
            }
        }
    }
    fun chooseAlternative(option:PlaybackAlternative,keepPosition:Boolean) {
        val current=state.value.playback ?: return
        if(state.value.alternativeSession!=current.sessionId || option !in state.value.alternatives) return
        val matching=state.value.detail?.takeIf { it.video.source.key==option.video.source.key && it.video.id==option.video.id }
        closeAlternatives();cancelDetailRequest()
        val next=Playback(option.video,option.episode,if(keepPosition) current.position else 0)
        own(next)
        _state.update { it.copy(playback=next,detail=matching,detailLoading=false,detailError=null) }
        if(matching==null) loadDetail(next.video,next.sessionId)
    }
    fun retryPlaybackDetail() { state.value.playback?.let { loadDetail(it.video,it.sessionId) } }
    fun closeDetail() { cancelDetailRequest();_state.update { it.copy(detail=null,detailLoading=false,detailError=null) } }
    fun play(video: Video, episode: Episode) {
        closeAlternatives()
        if(detailSession!=null) { cancelDetailRequest();_state.update { it.copy(detailLoading=false) } }
        val p = Playback(video,episode)
        own(p)
        val old = state.value.saved.history.firstOrNull { it.playback.key == p.key }
        _state.update { it.copy(playback = p.copy(position = old?.playback?.position ?: 0)) }
    }
    fun resume(history: History) {
        closeAlternatives()
        val p=HistoryGroups.resume(history);own(p)
        _state.update { it.copy(playback=p) } // Playback never waits for the catalog request.
        loadDetail(p.video,p.sessionId)
    }
    fun checkpoint(p: Playback, position: Long, autoPlay: Boolean = false) {
        if(historyOwners[p.key]?.let { it != p.sessionId } == true) return
        val currentSession=state.value.playback?.takeIf { it.sessionId==p.sessionId } ?: p
        val measured=measuredSessions[p.sessionId]
        val current=if(measured==null) currentSession else currentSession.copy(video=currentSession.video.copy(quality=measured),episode=currentSession.episode.copy(quality=measured))
        val next = current.copy(position = if(p.video.source.kind == SourceKind.LIVE) 0 else position.coerceAtLeast(0), autoPlay = autoPlay)
        if(state.value.playback?.sessionId == p.sessionId) _state.update { it.copy(playback = next) }
        historyWriter.submit(next.key,next)
    }
    fun measuredQuality(sessionId:String,width:Int,height:Int) {
        val p=state.value.playback?.takeIf { it.sessionId==sessionId } ?: return
        val q=QualityRules.measured(width,height)
        if(!measuredSessions.offer(sessionId,q)) return
        // Sample bursts at most once per 750ms. Final checkpoints read the latest
        // buffered size immediately, even when this UI update has not run yet.
        if(qualityFlushJob?.isActive==true) return
        qualityFlushJob=viewModelScope.launch {
            delay(750)
            val current=state.value.playback?.takeIf { it.sessionId==p.sessionId } ?: return@launch
            val latest=measuredSessions[p.sessionId] ?: return@launch
            if(current.episode.quality==latest) return@launch
            val next=current.copy(video=current.video.copy(quality=latest),episode=current.episode.copy(quality=latest))
            _state.update { it.copy(playback=next) }
            historyWriter.submit(next.key,next)
        }
    }
    private fun refreshMeasuredCatalog(p:Playback?) {
        if(p==null) return
        val q=measuredSessions[p.sessionId] ?: return
        qualityCatalogJob?.cancel()
        val snapshot=state.value
        qualityCatalogJob=viewModelScope.launch {
            // Results and potentially large episode lists are copied once when
            // leaving a session, off the main thread, never for each ABR event.
            val refreshed=withContext(Dispatchers.Default) {
                val results=snapshot.results.map { if(it.source.key==p.video.source.key && it.id==p.video.id) it.copy(quality=q) else it }
                val detail=snapshot.detail?.let { d -> if(d.video.source.key==p.video.source.key && d.video.id==p.video.id)
                    d.copy(video=d.video.copy(quality=q),lines=d.lines.map { line -> line.copy(episodes=line.episodes.map { if(it.url==p.episode.url) it.copy(quality=q) else it }) }) else d }
                results to detail
            }
            _state.update { current -> current.copy(
                results=if(current.results===snapshot.results) refreshed.first else current.results,
                detail=if(current.detail===snapshot.detail) refreshed.second else current.detail) }
        }
    }
    fun playbackIntent(sessionId: String, play: Boolean) {
        _state.update { s -> s.copy(playback = s.playback?.let { if(it.sessionId == sessionId) it.copy(autoPlay = play) else it }) }
    }
    fun stopPlayback() {
        closeAlternatives()
        refreshMeasuredCatalog(state.value.playback);qualityFlushJob?.cancel()
        if(detailSession!=null) cancelDetailRequest()
        _state.update { it.copy(playback=null,detailLoading=false) }
    }
    fun refreshStorage() {
        if(storageRefreshJob?.isActive == true) return
        storageRefreshJob=viewModelScope.launch {
            try {
                val cache = withContext(Dispatchers.IO) { responseCache.usage() }
                val posterBytes=withContext(Dispatchers.IO) { posters.usage() }
                val stateBytes = lock.withLock { store.usage() }
                _state.update { it.copy(storageUsage = StorageUsage(cache.memoryBytes,cache.diskBytes,cache.entries,stateBytes,posterBytes)) }
            } catch(e: CancellationException) { throw e } catch(_: Exception) { message("无法读取存储用量") }
        }
    }
    fun clearCache(silent: Boolean = false) {
        responseCache.invalidate(); posters.invalidate()
        if(cacheClearJob?.isActive == true) return
        cacheClearJob=viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { responseCache.clear(); posters.clear() }
                if(!silent) message("响应与封面缓存已清除；订阅、源开关及历史保留")
                refreshStorage()
            } catch(e: CancellationException) { throw e } catch(_: Exception) { message("缓存清理失败，请检查存储") }
        }
    }
    fun trimMemory() { if(memoryTrimJob?.isActive != true) memoryTrimJob=viewModelScope.launch(Dispatchers.IO) { responseCache.trimMemory();posters.trimMemory() } }
    override fun onCleared() { network.close(); super.onCleared() }
}
