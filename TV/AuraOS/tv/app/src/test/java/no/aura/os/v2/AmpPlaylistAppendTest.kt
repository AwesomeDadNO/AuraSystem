package no.aura.os.v2
import no.aura.os.v2.amp.*
import org.junit.Assert.*
import org.junit.Test
class AmpPlaylistAppendTest {
    private fun track(id:String)=AmpTrack(id,id,"Artist",100)
    @Test fun append_keeps_existing_order_and_does_not_duplicate_album_tracks() {
        assertEquals(listOf("2","1","3"),AmpPlaylistAppend.merge(listOf(track("2"),track("1")),listOf(track("1"),track("3"),track("3"))).map{it.id})
    }
    @Test fun adding_same_album_twice_is_idempotent() {
        val album=listOf(track("1"),track("2"))
        assertEquals(album,AmpPlaylistAppend.merge(album,album))
    }
    @Test(expected=IllegalArgumentException::class) fun over_limit_is_rejected_without_silent_song_loss() {
        AmpPlaylistAppend.merge((0..1999).map{track("$it")},listOf(track("new")))
    }
}
