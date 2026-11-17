package no.aura.os.v2
import no.aura.os.v2.theme.AuraPalette as P
import org.junit.Assert.*
import org.junit.Test
class PaletteTest {
    @Test fun tokens_match_contract() {
        assertEquals(0xFF1A1D24L, P.BACKGROUND); assertEquals(0xFF1A1D24L, P.BACKGROUND_DEEP); assertEquals(0xFF222630L, P.SURFACE)
        assertEquals(0xFF2B303DL, P.SURFACE_ELEVATED); assertEquals(0xFF3D4454L, P.BORDER); assertEquals(0xFFF5F7FAL, P.TEXT_PRIMARY)
        assertEquals(0xFFA5A6AFL, P.TEXT_SECONDARY); assertEquals(0xFF747782L, P.TEXT_MUTED); assertEquals(0xFF4FC3F7L, P.ACCENT_BLUE)
        assertEquals(0xFF38A8FFL, P.PROGRESS_BLUE); assertEquals(0xFF79D5FFL, P.FOCUS_BLUE); assertEquals(0xFFFF6B6BL, P.ALERT_RED); assertEquals(0xFF45C59AL, P.SUCCESS)
    }
    @Test fun primary_and_deep_backgrounds_share_the_owner_selected_base() {
        assertEquals(P.BACKGROUND_DEEP, P.BACKGROUND)
    }
    @Test fun mobile_accent_gradient_matches_owner_reference() { assertEquals(0xFFFFB856L, P.ACCENT_WARM); assertEquals(0xFFE75200L, P.BRAND_ORANGE) }
    @Test fun text_contrast() {
        assertTrue(P.contrast(P.TEXT_PRIMARY, P.BACKGROUND) >= 7.0); assertTrue(P.contrast(P.TEXT_SECONDARY, P.BACKGROUND) >= 4.5)
        assertTrue(P.contrast(P.FOCUS_BLUE, P.BACKGROUND) >= 3.0)
        assertTrue(P.contrast(P.BUTTON_INACTIVE_ICON, P.BUTTON_INACTIVE_BG) >= 4.5)
    }
}
