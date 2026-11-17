package no.aura.os.v2
import no.aura.os.v2.hero.HeroRotation
import org.junit.Assert.*
import org.junit.Test
class HeroRotationTest {
    @Test fun wraps() { assertEquals(0, HeroRotation.next(4, 5)); assertEquals(4, HeroRotation.previous(0, 5)) }
    @Test fun single_or_empty() { assertEquals(0, HeroRotation.next(0, 1)); assertEquals(0, HeroRotation.clamp(3, 0)) }
    @Test fun clamp() { assertEquals(4, HeroRotation.clamp(9, 5)); assertEquals(0, HeroRotation.clamp(-1, 5)) }
    @Test fun rotates_only_while_hero_visible() {
        assertTrue(HeroRotation.shouldRotate(5, 0, 100, 800)); assertFalse(HeroRotation.shouldRotate(5, 0, 500, 800))
        assertFalse(HeroRotation.shouldRotate(5, 1, 0, 800)); assertFalse(HeroRotation.shouldRotate(1, 0, 0, 800))
    }
}
