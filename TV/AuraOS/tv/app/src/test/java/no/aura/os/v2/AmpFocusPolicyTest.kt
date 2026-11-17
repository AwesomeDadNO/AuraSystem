package no.aura.os.v2
import no.aura.os.v2.amp.*
import org.junit.Assert.assertEquals
import org.junit.Test
class AmpFocusPolicyTest {
    @Test fun loading_detail_does_not_target_parent_catalogue() {
        assertEquals(AmpFocusDestination.DETAILS,AmpFocusPolicy.down(false,true,false,true,true))
    }
    @Test fun loaded_detail_enters_its_own_content() {
        assertEquals(AmpFocusDestination.BODY,AmpFocusPolicy.down(false,true,true,true,true))
    }
    @Test fun now_playing_keeps_the_player_as_entry_even_with_playlist_detail() {
        assertEquals(AmpFocusDestination.PLAYER,AmpFocusPolicy.down(true,true,true,false,true))
    }
    @Test fun empty_discover_still_has_a_mounted_fallback() {
        assertEquals(AmpFocusDestination.DETAILS,AmpFocusPolicy.down(false,false,false,false,false))
        assertEquals(AmpFocusDestination.HERO,AmpFocusPolicy.down(false,false,false,true,false))
    }
}
