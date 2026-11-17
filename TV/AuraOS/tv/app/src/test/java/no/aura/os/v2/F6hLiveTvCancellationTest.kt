package no.aura.os.v2

import kotlinx.coroutines.*
import no.aura.os.v2.livetv.*
import org.junit.Assert.*
import org.junit.Test
import java.net.URL
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

class F6hLiveTvCancellationTest {
    @Test fun cancellingPageStopsBeforeOtherPlaylistsAndGuide() = runBlocking {
        val started=CompletableDeferred<Unit>();var calls=0
        val loader=LiveTvLoader { _,_ -> calls++;started.complete(Unit);awaitCancellation() }
        val load=launch { loader.load(listOf(UserPlaylist("a","A","https://a.example/x"),UserPlaylist("b","B","https://b.example/x")),"https://guide.example/x") }
        withTimeout(3000){started.await()};load.cancelAndJoin()
        assertEquals(1,calls)
    }
    @Test fun cancellingBlockedReadDisconnectsTransport() = runBlocking {
        val started=CompletableDeferred<Unit>();val released=CountDownLatch(1);val disconnected=AtomicBoolean(false)
        val con=object:HttpsURLConnection(URL("https://example.com/")) {
            override fun connect() {}
            override fun usingProxy()=false
            override fun disconnect(){disconnected.set(true);released.countDown()}
            override fun getCipherSuite()="test"
            override fun getLocalCertificates():Array<java.security.cert.Certificate>?=null
            override fun getServerCertificates():Array<java.security.cert.Certificate> = emptyArray()
            override fun getResponseCode()=200
            override fun getContentLengthLong()=-1L
            override fun getInputStream()=object:InputStream(){
                override fun read():Int {started.complete(Unit);released.await(3,TimeUnit.SECONDS);throw IOException("closed")}
            }
        }
        val request=launch { LiveTvHttp.fetchBounded(1024) { con } }
        withTimeout(3000){started.await()};request.cancelAndJoin()
        assertTrue(disconnected.get())
    }
}
