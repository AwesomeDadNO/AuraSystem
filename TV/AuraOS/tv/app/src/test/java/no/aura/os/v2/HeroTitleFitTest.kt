package no.aura.os.v2
import no.aura.os.v2.hero.HeroTitleFit
import org.junit.Assert.*
import org.junit.Test
class HeroTitleFitTest {
    @Test fun short_title_keeps_maximum_and_long_title_shrinks_without_changing_slot() {
        assertEquals(86,HeroTitleFit.largest(24,86) { true })
        assertEquals(44,HeroTitleFit.largest(24,86) { it<=44 })
        assertEquals(24,HeroTitleFit.largest(24,86) { false })
    }
    @Test fun fitting_search_is_bounded_and_returns_largest_candidate() {
        var measures=0
        assertEquals(58,HeroTitleFit.largest(18,86) { measures++; it<=58 })
        assertTrue(measures<=8)
    }
}
