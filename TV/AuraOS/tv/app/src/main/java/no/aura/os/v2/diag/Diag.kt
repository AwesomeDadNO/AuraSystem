package no.aura.os.v2.diag

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Where trace sections go (android.os.Trace in the app; a fake in tests). */
interface TraceSink { fun begin(name: String); fun end() }

/** Where timings go. The app logs "name: N ms" under tag AuraDiag; names are fixed constants, never URLs, codes, tokens or profile data. */
fun interface DiagLog { fun line(text: String) }

/**
 * Lightweight diagnostics, **off unless [enabled]** (set from BuildConfig.DIAG: debug and the private perf build): a disabled call costs one volatile read. Timings also open a
 * trace section so an owner-captured Perfetto/systrace shows the same names. Only constant stage names are recorded.
 */
class DiagRecorder(
    private val clockNs: () -> Long = System::nanoTime, private val trace: TraceSink? = null, private val log: DiagLog? = null,
) {
    @Volatile var enabled = false
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val timings = ConcurrentHashMap<String, LongArray>()      // [count, totalNs, maxNs]
    private val t0 = clockNs()

    fun count(name: String, by: Long = 1) { if (enabled) counters.getOrPut(name) { AtomicLong() }.addAndGet(by) }
    fun counter(name: String): Long = counters[name]?.get() ?: 0
    /** Records the latest value of a measurement that is not a duration or an event count (e.g. a viewport size in px). */
    fun gauge(name: String, value: Long) { if (enabled) counters.getOrPut(name) { AtomicLong() }.set(value) }

    /** Times [block] (and wraps it in a trace section). Same thread begin/end, so only for non-suspending code. */
    inline fun <T> section(name: String, block: () -> T): T {
        if (!enabled) return block()
        beginSection(name); val s = now()
        try { return block() } finally { endSection(); record(name, now() - s) }
    }

    /** Times a suspending block without a trace section (a section cannot span a suspension point). */
    suspend inline fun <T> timed(name: String, block: suspend () -> T): T {
        if (!enabled) return block()
        val s = now()
        try { return block() } finally { record(name, now() - s) }
    }

    /** Records "time since this recorder was created" (process-ish start) as a one-off milestone, e.g. the first Compose frame. */
    fun milestone(name: String) { if (enabled) record("milestone.$name", now() - t0) }

    @PublishedApi internal fun now() = clockNs()
    @PublishedApi internal fun beginSection(name: String) { trace?.begin(name) }
    @PublishedApi internal fun endSection() { trace?.end() }
    @PublishedApi internal fun record(name: String, ns: Long) {
        val a = timings.getOrPut(name) { LongArray(3) }
        synchronized(a) { a[0]++; a[1] += ns; if (ns > a[2]) a[2] = ns }
        log?.line("$name: ${ns / 1_000} us")
    }

    fun timingCount(name: String): Long = timings[name]?.let { synchronized(it) { it[0] } } ?: 0
    fun timingTotalNs(name: String): Long = timings[name]?.let { synchronized(it) { it[1] } } ?: 0

    /** One line per counter and timing (count, mean, max) for a single logcat dump. */
    fun summary(): List<String> =
        counters.entries.sortedBy { it.key }.map { "${it.key} = ${it.value.get()}" } +
            timings.entries.sortedBy { it.key }.map { (k, a) -> synchronized(a) { "$k n=${a[0]} mean=${if (a[0] > 0) a[1] / a[0] / 1_000 else 0}us max=${a[2] / 1_000}us" } }
}

/** Process-wide recorder. [Diag.enabled] stays false in release builds. */
val Diag = DiagRecorder(trace = object : TraceSink {
    override fun begin(name: String) { android.os.Trace.beginSection(name.take(127)) }
    override fun end() { android.os.Trace.endSection() }
}, log = DiagLog { android.util.Log.d("AuraDiag", it) })
