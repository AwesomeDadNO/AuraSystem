package no.aura.os.v2
import no.aura.os.v2.cards.CardSpec
import no.aura.os.v2.cards.splitPrefix
import no.aura.os.v2.theme.DesignCanvasMath
import org.junit.Assert.*
import org.junit.Test
class DesignCanvasTest {
    @Test fun density_maps_screen_width_to_1920_canvas() {
        assertEquals(1f, DesignCanvasMath.density(1920f), 0f); assertEquals(2f, DesignCanvasMath.density(3840f), 0f); assertEquals(0.6667f, DesignCanvasMath.density(1280f), 0.001f)
    }
    @Test fun invalid_width_falls_back_to_one() { assertEquals(1f, DesignCanvasMath.density(0f), 0f); assertEquals(1f, DesignCanvasMath.density(Float.NaN), 0f); assertEquals(1f, DesignCanvasMath.density(-5f), 0f) }
    @Test fun the_prefix_is_split() { assertEquals("The" to "Silver Realm", splitPrefix("The Silver Realm")); assertEquals(null to "Orbital", splitPrefix("Orbital")); assertEquals(null to "The", splitPrefix("The")) }
    @Test fun card_aspect_matches_mockup_and_row_fits_canvas() {
        val a = CardSpec.ContinueWidth.value / CardSpec.ContinueHeight.value; assertEquals(16f / 9f, a, 0.02f)
        assertEquals(2.24f, CardSpec.CompactWidth.value / CardSpec.CompactHeight.value, 0.02f) // compact demo card; TMDB shelves use PosterCard
        val cont = 137f + 5 * CardSpec.ContinueWidth.value + 4 * CardSpec.Gap.value; assertTrue(cont < 1920f - 40f)
        val compact = 137f + 6 * CardSpec.CompactWidth.value + 5 * CardSpec.Gap.value; assertTrue(compact < 1920f - 261f - 18f)  // stays clear of the player column
    }
}
