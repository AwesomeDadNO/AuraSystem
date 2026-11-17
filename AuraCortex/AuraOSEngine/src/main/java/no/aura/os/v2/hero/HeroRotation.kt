package no.aura.os.v2.hero

/** Pure carousel arithmetic for the hero (unit-tested). */
object HeroRotation {
    const val INTERVAL_MS = 8_000L
    fun next(index: Int, size: Int): Int = if (size <= 1) 0 else (index + 1) % size
    fun previous(index: Int, size: Int): Int = if (size <= 1) 0 else (index - 1 + size) % size
    fun clamp(index: Int, size: Int): Int = if (size <= 0) 0 else index.coerceIn(0, size - 1)
    /** The rotation only runs while the hero is mostly on screen. */
    fun shouldRotate(size: Int, firstVisibleItem: Int, scrollOffsetPx: Int, heroHeightPx: Int): Boolean =
        size > 1 && firstVisibleItem == 0 && scrollOffsetPx < heroHeightPx / 2
}
