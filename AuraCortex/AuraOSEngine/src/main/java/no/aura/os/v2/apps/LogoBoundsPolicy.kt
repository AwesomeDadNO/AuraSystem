package no.aura.os.v2.apps

/** Pixel bounds use an exclusive right/bottom edge. No Android dependency. */
internal data class LogoPixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

internal object LogoBoundsPolicy {
    const val NOISE_ALPHA = 8
    const val BOUNDS_ALPHA = 32
    // Leave eight percent of each slot edge clear around a contained wordmark.
    const val CONTENT_FRACTION = 0.84f

    fun meaningfulBounds(pixels: IntArray, imageWidth: Int, cell: LogoPixelBounds): LogoPixelBounds? {
        var left = cell.right
        var top = cell.bottom
        var right = cell.left
        var bottom = cell.top
        for (y in cell.top until cell.bottom) for (x in cell.left until cell.right) {
            if ((pixels[y * imageWidth + x] ushr 24) > BOUNDS_ALPHA) {
                left = minOf(left, x); top = minOf(top, y)
                right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
            }
        }
        return if (left < right && top < bottom) LogoPixelBounds(left, top, right, bottom) else null
    }
}
