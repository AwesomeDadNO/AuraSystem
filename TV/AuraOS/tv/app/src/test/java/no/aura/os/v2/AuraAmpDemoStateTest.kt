package no.aura.os.v2

import no.aura.os.v2.amp.*
import org.junit.Assert.*
import org.junit.Test

class AuraAmpDemoStateTest {
    @Test fun favouritesToggleWithoutChangingSelection() {
        val start = AmpDemoState()
        val liked = start.toggleFavourite("bloom")
        assertEquals(setOf("bloom"), liked.favourites)
        assertEquals(start.selectedId, liked.selectedId)
        assertTrue(liked.toggleFavourite("bloom").favourites.isEmpty())
    }
    @Test fun queueConsumesInOrderAndNeverDuplicates() {
        val state = AmpDemoState(queue = listOf("golden", "blue")).enqueue("golden")
        assertEquals(listOf("golden", "blue"), state.queue)
        assertEquals("golden", state.next().selectedId)
        assertEquals(listOf("blue"), state.next().queue)
    }
    @Test fun progressClampsAndRepeatRestarts() {
        val state = AmpDemoState(playing = true, seconds = 257, repeat = true)
        assertEquals(0, state.tick(4).seconds)
        assertEquals(0, state.seek(-100).seconds)
        assertEquals(258, state.seek(999).seconds)
        assertEquals(state.copy(playing = false).seconds, state.copy(playing = false).tick().seconds)
    }
    @Test fun filterMatchesArtistAndFavouritesAreHonest() {
        assertEquals(listOf("northern"), AmpDemoState().visibleTracks(AmpDemoTab.SEARCH, "arctic").map { it.id })
        assertTrue(AmpDemoState().visibleTracks(AmpDemoTab.FAVOURITES).isEmpty())
        assertEquals(1, AmpDemoState().toggleFavourite("blue").visibleTracks(AmpDemoTab.FAVOURITES).size)
    }
    @Test fun followingAndQueueTabsReflectTheirOwnState() {
        val state = AmpDemoState().toggleFollow("Arctic Echo")
        assertEquals(setOf("bloom", "northern"), state.visibleTracks(AmpDemoTab.FOLLOWING).map { it.id }.toSet())
        assertEquals(listOf("golden", "blue", "night", "bloom"), state.enqueue("bloom").visibleTracks(AmpDemoTab.PLAYLISTS).map { it.id })
        assertFalse(state.toggleFollow("Arctic Echo").followed.contains("Arctic Echo"))
    }
    @Test fun finishingMovesToQueueAndShuffleUsesAnotherQueuedTrack() {
        val state = AmpDemoState(playing = true, seconds = 257)
        assertEquals("golden", state.tick(2).selectedId)
        assertEquals("night", state.copy(shuffle = true).next().selectedId)
        val exhausted = state.copy(queue = emptyList()).tick(2)
        assertFalse(exhausted.playing)
        assertEquals(0, exhausted.seconds)
    }
    @Test fun invalidTrackCannotBecomePlaybackOrQueue() {
        val state = AmpDemoState()
        assertEquals(state, state.select("missing"))
        assertEquals(state, state.enqueue("missing"))
        assertEquals(state.selectedId, state.copy(queue = emptyList()).next().selectedId)
    }
    @Test fun trackSectionHasOnlyRecentAndQueueTabs() {
        assertEquals(listOf("Recently played", "Queue"), AmpDemoTrackTab.entries.map { it.label })
        assertEquals(listOf("Amp", "Albums", "Artists", "Playlists"), AmpDemoModule.entries.map { it.label })
        val state = AmpDemoState(queue = listOf("blue", "missing", "golden", "blue"))
        assertEquals(listOf("blue", "golden"), state.trackSection(AmpDemoTrackTab.QUEUE).map { it.id })
        assertEquals(listOf("bloom", "northern", "quiet", "golden"), state.trackSection(AmpDemoTrackTab.RECENTLY_PLAYED).map { it.id })
    }
    @Test fun selectingQueuedTrackConsumesOnlyThatTrack() {
        val state = AmpDemoState(queue = listOf("golden", "blue", "night"))
        val selected = state.playQueued("blue")
        assertEquals("blue", selected.selectedId)
        assertEquals(listOf("golden", "night"), selected.queue)
        assertTrue(selected.playing)
        assertEquals(0, selected.seconds)
        assertEquals(state, state.playQueued("bloom"))
        assertEquals(state, state.playQueued("missing"))
    }
}
