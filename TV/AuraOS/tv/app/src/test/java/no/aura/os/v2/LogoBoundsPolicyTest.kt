package no.aura.os.v2

import no.aura.os.v2.apps.LogoBoundsPolicy
import no.aura.os.v2.apps.LogoPixelBounds
import org.junit.Assert.*
import org.junit.Test

class LogoBoundsPolicyTest {
    @Test fun transparentAndLowAlphaSpecklesDoNotExpandWordmarkBounds() {
        val pixels = IntArray(48)
        pixels[0] = 0x20000000
        pixels[47] = 0x08000000
        pixels[2 * 8 + 3] = 0xFFFFFFFF.toInt()
        pixels[3 * 8 + 5] = 0xFFFF6600.toInt()
        assertEquals(LogoPixelBounds(3, 2, 6, 4),
            LogoBoundsPolicy.meaningfulBounds(pixels, 8, LogoPixelBounds(0, 0, 8, 6)))
    }

    @Test fun adjacentAtlasCellsCannotContributePixelsAndEmptyCellFallsBack() {
        val pixels = IntArray(24)
        pixels[1 * 8 + 1] = 0xFFFFFFFF.toInt()
        assertNull(LogoBoundsPolicy.meaningfulBounds(pixels, 8, LogoPixelBounds(4, 0, 8, 3)))
        pixels[2 * 8 + 7] = 0x21000000
        assertEquals(LogoPixelBounds(7, 2, 8, 3),
            LogoBoundsPolicy.meaningfulBounds(pixels, 8, LogoPixelBounds(4, 0, 8, 3)))
    }
}
