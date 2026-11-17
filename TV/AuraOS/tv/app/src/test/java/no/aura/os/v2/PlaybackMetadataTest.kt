package no.aura.os.v2

import no.aura.os.v2.details.DetailsLogic
import no.aura.os.v2.playback.*
import no.aura.os.v2.tmdb.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackMetadataTest {
    private fun details(extra: String = ""): TmdbDetails = requireNotNull(TmdbParser.details(
        Json.parse("""{"id":1,"title":"Example"$extra}"""), TitleRef(MediaType.MOVIE, 1)))

    @Test fun languages_preserve_real_names_and_original_code() {
        val d = details(""","original_language":"ja","spoken_languages":[{"iso_639_1":"ja","english_name":"Japanese","name":"日本語"},{"iso_639_1":"en","name":"English"}]""")
        assertEquals("ja", d.originalLanguage)
        assertEquals("日本語", d.spokenLanguages.first().nativeName)
        val label = DetailsLogic.languageLabel(d)
        assertTrue(label.contains("Spoken: Japanese, English"))
        assertTrue(label.contains("Original: Japanese"))
        assertTrue(label.contains("selected source"))
    }

    @Test fun missing_and_malformed_languages_do_not_invent_english() {
        assertTrue(details().spokenLanguages.isEmpty())
        assertNull(details().originalLanguage)
        val d = details(""","original_language":123,"spoken_languages":[null,{}, {"iso_639_1":"bad-code"},{"iso_639_1":"no"},{"iso_639_1":"no"}]""")
        assertNull(d.originalLanguage)
        assertEquals(listOf("no"), d.spokenLanguages.map { it.code })
        assertTrue(DetailsLogic.languageLabel(details()).contains("unavailable"))
        assertEquals("Norwegian", DetailsLogic.languageName("no"))
    }

    @Test fun unknown_metadata_still_has_an_honest_visible_banner() {
        val banner = requireNotNull(PlaybackAdvisoryPolicy.banner(PlaybackMetadata()))
        assertEquals("Age rating unavailable\nDetailed content advisory unavailable", banner)
    }

    @Test fun unattributed_ratings_and_advice_are_never_presented_as_verified() {
        val rating = requireNotNull(PlaybackAdvisoryPolicy.banner(PlaybackMetadata(certification = "PG")))
        assertFalse(rating.contains("PG"))
        assertTrue(rating.contains("Age rating unavailable"))
        val advice = requireNotNull(PlaybackAdvisoryPolicy.banner(PlaybackMetadata(advisories = listOf(VerifiedContentAdvisory("Violence", "")))))
        assertFalse(advice.contains("Violence"))
        assertTrue(advice.contains("Detailed content advisory unavailable"))
    }

    @Test fun late_metadata_replaces_unknown_text_with_only_attributed_facts() {
        val unknown = PlaybackAdvisoryPolicy.banner(PlaybackMetadata())
        val resolved = requireNotNull(PlaybackAdvisoryPolicy.banner(PlaybackMetadata(certification = "12", ratingSource = "TMDB · NO")))
        assertNotEquals(unknown, resolved)
        assertTrue(resolved.contains("Rated 12 · TMDB · NO"))
        assertFalse(resolved.contains("Violence"))
        assertFalse(resolved.contains("Nudity"))
        assertFalse(resolved.contains("Drugs"))
    }

    @Test fun rating_does_not_imply_content_warnings() {
        val banner = requireNotNull(PlaybackAdvisoryPolicy.banner(PlaybackMetadata(certification = "PG-13", ratingSource = "TMDB")))
        assertTrue(banner.contains("PG-13"))
        assertTrue(banner.contains("advisory unavailable"))
        assertFalse(banner.contains("nudity", ignoreCase = true))
        assertFalse(banner.contains("violence", ignoreCase = true))
        assertEquals(8_000L, PlaybackAdvisoryPolicy.DISPLAY_MS)
    }

    @Test fun explicit_advice_requires_and_shows_source() {
        val banner = PlaybackAdvisoryPolicy.banner(PlaybackMetadata(advisories = listOf(VerifiedContentAdvisory("Strong language", "Verified provider"))))
        assertEquals("Age rating unavailable\nStrong language (Verified provider)", banner)
    }
}
