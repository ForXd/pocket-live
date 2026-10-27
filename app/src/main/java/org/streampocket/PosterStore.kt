// SPDX-License-Identifier: AGPL-3.0-or-later
package org.streampocket

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object PosterLimits {
    const val DOWNLOAD=1_000_000
    const val SOURCE_PIXELS=16_000_000L
    const val DECODE_PIXELS=128_000L
    const val TTL=24*60*60*1000L
    fun sample(width: Int,height: Int): Int? {
        if(width<=0 || height<=0 || width.toLong()*height>SOURCE_PIXELS) return null
        var sample=1
        while(((width+sample-1)/sample).toLong()*((height+sample-1)/sample)>DECODE_PIXELS) sample*=2
        return sample
    }
}
class PosterStore(private val directory: File) {
    private val slots=Semaphore(2)
    private val epoch=java.util.concurrent.atomic.AtomicLong()
    private val memory=object:LruCache<String,Bitmap>(StorageBudget.POSTER_MEMORY) {
        override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount
    }
    fun invalidate() { epoch.incrementAndGet() }
    @Synchronized fun trimMemory() { memory.evictAll() }
    @Synchronized fun clear() { invalidate();memory.evictAll();directory.listFiles()?.forEach { if(!it.delete()) throw IOException("Cache cleanup failed") } }
    @Synchronized private fun maintain(incoming:Long=0) {
        directory.mkdirs(); val now=System.currentTimeMillis()
        directory.listFiles()?.filter { !it.name.matches(Regex("[a-f0-9]{64}\\.img")) || it.length()>PosterLimits.DOWNLOAD || now-it.lastModified() !in 0..PosterLimits.TTL }?.forEach { if(!it.delete()) throw IOException("Cache cleanup failed") }
        val files=directory.listFiles().orEmpty().sortedBy { it.lastModified() }.toMutableList()
        var total=files.sumOf { it.length() }
        while(total+incoming>StorageBudget.POSTERS || files.size>=100) {
            val oldest=files.removeFirstOrNull() ?: break;total-=oldest.length()
            if(!oldest.delete()) throw IOException("Cache cleanup failed")
        }
    }
    @Synchronized fun usage():Long { maintain();return directory.listFiles().orEmpty().sumOf { it.length() } }
    @Synchronized private fun read(key:String):ByteArray? {
        maintain();val file=File(directory,"$key.img")
        return if(file.exists()) file.readBytes().also { file.setLastModified(System.currentTimeMillis()) } else null
    }
    @Synchronized private fun save(key:String,bytes:ByteArray,owner:Long) {
        if(owner!=epoch.get()) return
        maintain(bytes.size.toLong())
        if(directory.usableSpace<StorageBudget.FREE_RESERVE+bytes.size) return
        val target=File(directory,"$key.img");val temp=File(directory,"$key.tmp")
        // Reserve both the existing file and the in-progress file before the write.
        try { temp.writeBytes(bytes);if(!temp.renameTo(target)) throw IOException("Cache commit failed") }
        finally { temp.delete() }
    }
    suspend fun load(url:String?,network:Network):Bitmap? {
        val valid=validUrl(url) ?: return null
        val key=hash(valid);val owner=epoch.get()
        return slots.withPermit { withContext(Dispatchers.IO) {
            memory.get(key)?.let { return@withContext it }
            try {
                val bytes=read(key) ?: download(valid,network).also { ensureActive() }
                ensureActive()
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                val sample=PosterLimits.sample(bounds.outWidth,bounds.outHeight) ?: return@withContext null
                val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888 }) ?: return@withContext null
                ensureActive()
                if(bitmap.width.toLong()*bitmap.height>PosterLimits.DECODE_PIXELS) return@withContext null
                if(owner==epoch.get()) { memory.put(key,bitmap);save(key,bytes,owner) }
                bitmap
            } catch(e:CancellationException) { throw e } catch(_:Exception) { null }
        } }
    }
    private suspend fun download(url:String,network:Network):ByteArray=suspendCancellableCoroutine { continuation ->
        val call=network.client.newCall(Request.Builder().url(url).header("Accept","image/*").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException) { if(continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call:Call,response:Response) {
                try {
                    val bytes=response.use { r ->
                        if(!r.isSuccessful) throw IOException("Image unavailable")
                        val body=r.body ?: throw IOException("Empty image")
                        if(body.contentLength()>PosterLimits.DOWNLOAD) throw IOException("Image too large")
                        val out=java.io.ByteArrayOutputStream()
                        body.byteStream().use { input -> val block=ByteArray(8192)
                            while(true) { val n=input.read(block);if(n<0) break
                                if(out.size()+n>PosterLimits.DOWNLOAD) throw IOException("Image too large")
                                out.write(block,0,n) }
                        };out.toByteArray()
                    }
                    if(continuation.isActive) continuation.resume(bytes)
                } catch(e:Exception) { if(continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
}
@Composable fun Poster(video:Video,model:AppModel,modifier:Modifier=Modifier.size(72.dp,104.dp),shape:androidx.compose.ui.graphics.Shape=MaterialTheme.shapes.small,containerColor:androidx.compose.ui.graphics.Color=MaterialTheme.colorScheme.surfaceContainerHighest) {
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val refresh by remember(model) { model.state.map { it.posterRefresh }.distinctUntilChanged() }.collectAsStateWithLifecycle(initialValue=model.state.value.posterRefresh)
    val bitmap by produceState<Bitmap?>(null,video.poster,model,refresh,lifecycle) {
        value=null // A changed URL must never reuse the previous poster bitmap.
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            // A stopped screen cancels its calls; foreground entry retries a failed image.
            if(value==null) value=model.posters.load(video.poster,model.network)
        }
    }
    Surface(modifier,shape=shape,color=containerColor) {
        bitmap?.let { Image(it.asImageBitmap(),"${video.title} 封面",contentScale=ContentScale.Crop) }
            ?: Box(Modifier.fillMaxSize(),contentAlignment=androidx.compose.ui.Alignment.Center) { Icon(painterResource(R.drawable.ic_play),"暂无封面",Modifier.size(32.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
