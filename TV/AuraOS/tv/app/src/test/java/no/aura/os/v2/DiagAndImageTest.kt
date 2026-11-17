package no.aura.os.v2

import kotlinx.coroutines.*
import no.aura.os.v2.diag.DiagLog
import no.aura.os.v2.diag.DiagRecorder
import no.aura.os.v2.diag.TraceSink
import no.aura.os.v2.images.DecodeSizing
import no.aura.os.v2.images.TieredFetch
import org.junit.Assert.*
import org.junit.Test

class DiagRecorderTest {
    private class Trace : TraceSink { val events = ArrayList<String>(); override fun begin(name: String) { events += "b:$name" }; override fun end() { events += "e" } }
    @Test fun disabledRecordsNothing() {
        val t = Trace(); val lines = ArrayList<String>()
        val d = DiagRecorder(trace = t, log = DiagLog { lines += it })
        assertEquals(5, d.section("x") { 5 }); d.count("c"); d.milestone("m")
        assertTrue(t.events.isEmpty()); assertTrue(lines.isEmpty()); assertEquals(0, d.counter("c")); assertEquals(0, d.timingCount("x"))
    }
    @Test fun enabledRecordsTimingTraceAndCounters() {
        var now = 0L; val t = Trace(); val lines = ArrayList<String>()
        val d = DiagRecorder(clockNs = { now }, trace = t, log = DiagLog { lines += it }); d.enabled = true
        assertEquals("v", d.section("stage") { now += 2_000_000; "v" })
        assertEquals(listOf("b:stage", "e"), t.events); assertEquals(1, d.timingCount("stage")); assertEquals(2_000_000, d.timingTotalNs("stage"))
        d.count("hits"); d.count("hits", 2); assertEquals(3, d.counter("hits"))
        assertTrue(d.summary().any { it.startsWith("hits = 3") }); assertTrue(d.summary().any { it.startsWith("stage n=1") })
    }
    @Test fun endsSectionEvenOnException() {
        val t = Trace(); val d = DiagRecorder(trace = t); d.enabled = true
        try { d.section("boom") { error("x") } } catch (_: IllegalStateException) {}
        assertEquals(listOf("b:boom", "e"), t.events)
    }
    @Test fun suspendTimedRecords() = runBlocking {
        var now = 0L; val d = DiagRecorder(clockNs = { now }); d.enabled = true
        d.timed("s") { delay(1); now += 5 }
        assertEquals(1, d.timingCount("s"))
    }
}

class TieredFetchTest {
    @Test fun slowNetworkDoesNotDelayDiskHits() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val f = TieredFetch(
            disk = { u -> if (u.startsWith("disk")) byteArrayOf(1) else null },
            network = { _ -> gate.await(); byteArrayOf(2) },
            networkPermits = 1, diskPermits = 1,
        )
        val slow = async(Dispatchers.Default) { f.fetch("net-1") }      // holds the only network permit
        val slow2 = async(Dispatchers.Default) { f.fetch("net-2") }     // queued behind it
        delay(50)
        val hit = withTimeout(1_000) { f.fetch("disk-1") }             // must not wait for the network gate
        assertEquals(TieredFetch.Source.DISK, hit!!.source)
        gate.complete(Unit)
        assertEquals(TieredFetch.Source.NETWORK, slow.await()!!.source); assertNotNull(slow2.await())
    }
    @Test fun networkBytesAreWrittenToDiskOnce() = runBlocking {
        val written = ArrayList<String>()
        val f = TieredFetch(disk = { null }, network = { byteArrayOf(9) }, onDiskWrite = { u, _ -> written += u })
        f.fetch("a"); assertEquals(listOf("a"), written)
    }
    @Test fun missingEverywhereIsNull() = runBlocking { assertNull(TieredFetch(disk = { null }, network = { null }).fetch("x")) }
    @Test fun cancellationReleasesPermit() = runBlocking {
        val f = TieredFetch(disk = { null }, network = { awaitCancellation() }, networkPermits = 1)
        val j = launch(Dispatchers.Default) { f.fetch("a") }
        delay(30); j.cancelAndJoin()
        val g = TieredFetch(disk = { null }, network = { byteArrayOf(1) }, networkPermits = 1)
        assertNotNull(withTimeout(1_000) { g.fetch("b") })
    }
}

class DecodeSizingTest {
    @Test fun noSubsampleWhenAtTarget() = assertEquals(1, DecodeSizing.sampleSize(500, 750, 500))
    @Test fun subsamplesOversizedSource() { assertEquals(2, DecodeSizing.sampleSize(1000, 1500, 500)); assertEquals(4, DecodeSizing.sampleSize(2000, 3000, 500)) }
    @Test fun neverBelowTarget() { assertEquals(1, DecodeSizing.sampleSize(700, 1000, 500)); assertEquals(1, DecodeSizing.sampleSize(900, 600, 500)) }
    @Test fun hugeImagesCappedByPixels() { assertTrue(DecodeSizing.sampleSize(8000, 6000, 8000) > 1) }
    @Test fun badInputIsOne() { assertEquals(1, DecodeSizing.sampleSize(0, 0, 500)); assertEquals(1, DecodeSizing.sampleSize(500, 500, 0)) }
}
