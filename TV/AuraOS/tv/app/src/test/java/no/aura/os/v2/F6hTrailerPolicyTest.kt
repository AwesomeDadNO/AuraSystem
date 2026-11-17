package no.aura.os.v2

import no.aura.os.v2.tmdb.Json
import no.aura.os.v2.tmdb.TmdbParser
import org.junit.Assert.*
import org.junit.Test

class F6hTrailerPolicyTest {
    @Test fun unofficial_upload_is_not_advertised_as_official() {
        assertNull(TmdbParser.trailer(Json.parse("""{"results":[{"site":"YouTube","type":"Trailer","official":false,"key":"abcdefghijk"}]}""")))
    }
    @Test fun current_titles_official_well_formed_youtube_trailer_is_selected() {
        assertEquals("0123456789a", TmdbParser.trailer(Json.parse("""{"results":[{"site":"YouTube","type":"Trailer","official":false,"key":"abcdefghijk"},{"site":"YouTube","type":"Trailer","official":true,"key":"0123456789a"}]}""")))
    }
}
