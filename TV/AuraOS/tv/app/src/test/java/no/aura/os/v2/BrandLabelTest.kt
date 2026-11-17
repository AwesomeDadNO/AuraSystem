package no.aura.os.v2

import no.aura.os.v2.brand.BrandLabels
import no.aura.os.v2.demo.DemoRepositoryImpl
import no.aura.os.v2.theme.AuraPalette
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BrandLabelTest {
    @Test fun approved_label_texts() {
        assertEquals("AURA" to "RECOMMENDED", BrandLabels.RECOMMENDED.let { it.primary to it.accent })
        assertEquals("AURA" to "SPORTS CENTER", BrandLabels.SPORTS_CENTER.let { it.primary to it.accent })
        assertEquals("LIVE ON" to "AURA", BrandLabels.LIVE_ON_AURA.let { it.primary to it.accent })
        assertEquals("AURA" to "BOX", BrandLabels.AURABOX.let { it.primary to it.accent })
        assertEquals("AURA" to "AMP", BrandLabels.AURAAMP.let { it.primary to it.accent })
    }

    @Test fun every_page_uses_its_approved_label() {
        val c = runBlocking { DemoRepositoryImpl.load() }
        assertTrue(c.home.hero.all { it.sectionLabel == BrandLabels.RECOMMENDED })
        assertTrue(c.movies.hero.all { it.sectionLabel == BrandLabels.RECOMMENDED })
        assertTrue(c.tvShows.hero.all { it.sectionLabel == BrandLabels.RECOMMENDED })
        assertEquals(BrandLabels.SPORTS_CENTER, c.sports.hero.label)
        assertEquals(BrandLabels.LIVE_ON_AURA, c.liveTv.hero.sectionLabel)
        assertEquals(BrandLabels.AURAAMP, c.auraAmp.hero.sectionLabel)
        assertEquals(BrandLabels.AURABOX, c.auraBox.hero.sectionLabel)
    }

    @Test fun brand_orange_is_ff7a00_and_distinct_from_every_interaction_colour() {
        assertEquals(0xFFE75200L, AuraPalette.BRAND_ORANGE)
        listOf(AuraPalette.ACCENT_BLUE, AuraPalette.PROGRESS_BLUE, AuraPalette.FOCUS_BLUE).forEach { assertNotEquals(AuraPalette.BRAND_ORANGE, it) }
        assertTrue(AuraPalette.contrast(AuraPalette.BRAND_ORANGE, AuraPalette.BACKGROUND) >= 4.5)
    }

    @Test fun brand_assets_exist() {
        listOf("src/main/res/drawable-nodpi/auraos_wordmark.png", "src/main/res/drawable-nodpi/banner.png").forEach { assertTrue(it, File(it).isFile) }
    }
}
