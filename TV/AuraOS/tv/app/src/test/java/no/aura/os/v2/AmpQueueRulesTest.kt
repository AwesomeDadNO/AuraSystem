package no.aura.os.v2

import no.aura.os.v2.amp.AmpQueueRules
import no.aura.os.v2.amp.AmpTrack
import org.junit.Assert.*
import org.junit.Test

class AmpQueueRulesTest {
    private fun track(id: Int) = AmpTrack(id.toString(), "Track $id", "Artist", 180)
    @Test fun selectedTrackConsumesOnlyEarlierItems() {
        val source = (0..4).map(::track)
        assertEquals(listOf(track(3), track(4)), AmpQueueRules.afterSelection(track(2), source))
        assertTrue(AmpQueueRules.afterSelection(track(4), source).isEmpty())
        assertEquals(5, source.size)
    }
    @Test fun standaloneSelectionKeepsOrderedBoundedUpcomingTracks() {
        val result = AmpQueueRules.afterSelection(track(-1), (0..300).map(::track))
        assertEquals(200, result.size)
        assertEquals(track(0), result.first())
        assertEquals(track(199), result.last())
    }
    @Test fun repeatRotatesCompletedTrackAndShuffleSelectionConsumesChosenOccurrence() {
        val result = AmpQueueRules.advance(listOf(track(1), track(2), track(1)), track(0), true, 1)!!
        assertEquals(track(2), result.first)
        assertEquals(listOf(track(1), track(1), track(0)), result.second)
        assertEquals(track(0), AmpQueueRules.advance(emptyList(), track(0), true, 0)!!.first)
        assertNull(AmpQueueRules.advance(emptyList(), track(0), false, 0))
    }
}
