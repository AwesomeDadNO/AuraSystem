package no.aura.os.v2.youtube

/** Small bounded LRU cache with per-entry expiry. Thread-safe. Memory is capped by [maxEntries]. */
class TtlCache<K, V : Any>(private val maxEntries: Int, private val clock: () -> Long = System::currentTimeMillis) {
    private class Entry<V>(val value: V, val expiresAt: Long)
    private val map = object : LinkedHashMap<K, Entry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?) = size > maxEntries
    }

    @Synchronized fun get(key: K): V? {
        val e = map[key] ?: return null
        if (e.expiresAt <= clock()) { map.remove(key); return null }
        return e.value
    }
    @Synchronized fun put(key: K, value: V, ttlMs: Long) { map[key] = Entry(value, clock() + ttlMs) }
    @Synchronized fun clear() = map.clear()
    val size: Int @Synchronized get() = map.size
}
