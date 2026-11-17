package no.aura.os.v2
import no.aura.os.v2.overlay.*
import org.junit.Assert.*
import org.junit.Test
class MiniPlayerReducerTest {
    private fun r(s: MiniPlayerState, a: PlayerAction, n: Int = 3) = MiniPlayerReducer.reduce(s, a, n)
    @Test fun collapse_expand_roundtrip() {
        val c = r(MiniPlayerState(), PlayerAction.Collapse); assertEquals(PlayerVisibility.COLLAPSED, c.visibility)
        assertEquals(PlayerVisibility.EXPANDED, r(c, PlayerAction.Expand).visibility)
    }
    @Test fun dismiss_hides_and_pauses() { val d = r(MiniPlayerState(), PlayerAction.Dismiss); assertEquals(PlayerVisibility.HIDDEN, d.visibility); assertFalse(d.playing) }
    @Test fun toggle_ignored_when_hidden() { val h = MiniPlayerState(PlayerVisibility.HIDDEN, false); assertEquals(h, r(h, PlayerAction.TogglePlay)) }
    @Test fun next_previous_wrap() {
        assertEquals(0, r(MiniPlayerState(trackIndex = 2), PlayerAction.Next).trackIndex)
        assertEquals(2, r(MiniPlayerState(trackIndex = 0), PlayerAction.Previous).trackIndex)
    }
    @Test fun empty_queue_is_safe() { val s = MiniPlayerState(); assertEquals(s, r(s, PlayerAction.Next, 0)); assertEquals(s, r(s, PlayerAction.Show, 0)) }
    @Test fun collapse_when_not_expanded_is_noop() { val c = MiniPlayerState(PlayerVisibility.COLLAPSED); assertEquals(c, r(c, PlayerAction.Collapse)) }
    @Test fun layers_are_ordered() { assertEquals(listOf("BASE_CONTENT","INTERACTIONS","COMPACT_PLAYER","DIALOGS","SYSTEM_OVERLAYS"), AuraLayer.values().map { it.name }) }
}
