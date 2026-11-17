package no.aura.os.v2
import no.aura.os.v2.connect.ampPlaylistDurationMs
import no.aura.os.v2.connect.ampPlaylistDurationSeconds
import org.junit.Assert.assertEquals
import org.junit.Test
class AmpPlaylistDurationTest {
    @Test fun library_seconds_are_converted_to_wire_milliseconds() {
        assertEquals(258000L,ampPlaylistDurationMs(258))
    }
    @Test fun invalid_negative_duration_cannot_enter_sync() {
        assertEquals(0L,ampPlaylistDurationMs(-1))
    }
    @Test fun duration_uses_the_library_day_limit_without_integer_overflow() {
        assertEquals(86400000L,ampPlaylistDurationMs(Int.MAX_VALUE))
    }
    @Test fun phone_push_and_tv_pull_preserve_normal_song_duration() {
        assertEquals(258,ampPlaylistDurationSeconds(258000L))
        assertEquals(258000L,ampPlaylistDurationMs(ampPlaylistDurationSeconds(258000L)))
    }
    @Test fun incoming_duration_is_bounded_before_narrowing_to_int() {
        assertEquals(86400,ampPlaylistDurationSeconds(Long.MAX_VALUE))
        assertEquals(0,ampPlaylistDurationSeconds(-1000L))
    }
}
