package org.streampocket

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory

class NetworkShutdownDeviceTest {
    @Test fun clearingModelClosesIdleSocketsOffMainThread() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val server=MockWebServer().apply { start();enqueue(MockResponse().setBody("ok")) }
        val closed=CountDownLatch(1)
        val onMain=AtomicBoolean(false)
        val store=ViewModelStore()
        lateinit var model:AppModel
        instrument.runOnMainSync {
            model=AppModel(instrument.targetContext.applicationContext as Application)
            store.put("model",model)
        }
        val factory=object:SocketFactory() {
            override fun createSocket():Socket=object:Socket() {
                override fun close() {
                    onMain.set(Looper.myLooper()==Looper.getMainLooper())
                    try { super.close() } finally { closed.countDown() }
                }
            }
            override fun createSocket(h:String,p:Int)=error("unused")
            override fun createSocket(h:String,p:Int,l:InetAddress,lp:Int)=error("unused")
            override fun createSocket(h:InetAddress,p:Int)=error("unused")
            override fun createSocket(h:InetAddress,p:Int,l:InetAddress,lp:Int)=error("unused")
        }
        // Share the actual model pool; the socket hook observes the same close path as TLS close_notify.
        val client=model.network.client.newBuilder().dns(okhttp3.Dns.SYSTEM).socketFactory(factory).apply { interceptors().clear() }.build()
        try {
            client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { assertEquals("ok",it.body!!.string()) }
            assertEquals(1,model.network.client.connectionPool.idleConnectionCount())
            instrument.runOnMainSync { store.clear() }
            assertTrue("Socket cleanup must finish",closed.await(10,TimeUnit.SECONDS))
            assertFalse("Activity destruction must not close sockets on the main thread",onMain.get())
        } finally { instrument.runOnMainSync { store.clear() };server.shutdown() }
    }
}
