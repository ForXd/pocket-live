package org.streampocket
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
class RecommendationLiveDeviceTest {
    @Test fun publicMovieAndSeriesIntroAreAvailable()=runBlocking {
        val address=InstrumentationRegistry.getArguments().getString("doubanAddress")
        val dns=if(address==null) PublicNetwork.dns else object:okhttp3.Dns { override fun lookup(host:String):List<java.net.InetAddress> {
            return if(host!="m.douban.com") PublicNetwork.dns.lookup(host) else listOf(java.net.InetAddress.getByName(address)).also { assertTrue(it.all(PublicNetwork::publicAddress)) }
        }
        }
        val network=Network({false},dns)
        try {
            for(id in listOf("1292052","30313969")) {
                val summary=recommendationApiSynopsis(network.text("https://m.douban.com/rexxar/api/v2/movie/$id"),id)
                assertTrue("Public synopsis missing for $id",summary!=null && summary.length>40)
                InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply { putString("synopsis","$id: ${summary!!.length} characters") })
            }
        } finally { network.client.dispatcher.cancelAll();network.client.connectionPool.evictAll() }
    }
}
