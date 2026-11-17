package no.aura.os.v2

import no.aura.os.v2.youtube.VideoId
import no.aura.os.v2.youtube.PublicYouTubeVideo
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** F6e guardrails: TV navigation, real watch records, private build credentials, and YouTube source safety. */
class F6eTest {
    private fun src(path: String) = File("src/main/java/no/aura/os/v2/$path").readText()

    @Test fun seasons_are_above_the_cinema_episode_chapters() {
        val s = src("details/CinemaSeriesPage.kt")
        assertTrue(s.contains("Text(\"Seasons:\""))
        assertTrue(s.indexOf("Text(\"Seasons:\"") < s.indexOf("items(episodes,"))
        assertTrue(s.contains("down=playFocus"))
        assertTrue(s.contains("down=relatedEntry"))
        assertFalse(src("overlay/TitleDetailsHost.kt").contains("private fun SeasonsSection("))
    }

    @Test fun episodes_have_one_border_and_ten_pixel_radius() {
        val cards = src("details/DetailsComponents.kt")
        assertTrue(cards.contains("RoundedCornerShape(10.dp)"))
        assertTrue(cards.contains("onClick = { onSelected(n); onPlay(n) }"))
        assertTrue(cards.contains("EpisodeMoreInfo(Modifier.weight(EpisodeActionGeometry.INFO_WEIGHT)"))
        assertTrue(cards.contains("infoOpen = true"))
        val focus = src("cards/FocusStyle.kt")
        assertTrue(focus.contains("showOutline: Boolean = true"))
    }

    @Test fun continue_watching_uses_real_playback_not_demo_fixture() {
        val p = src("watch/PlaybackProgress.kt")
        val player = src("overlay/VideoPlayerHost.kt")
        val main = src("MainActivity.kt")
        assertTrue(p.contains("positionMs >= 5_000L"))
        assertTrue(p.contains("fraction < 0.95"))
        assertFalse(p.contains("playUrl"))
        assertTrue(player.contains("progressSink?.invoke"))
        assertTrue(player.contains("resumeFrom"))
        assertTrue(main.contains("watch = no.aura.os.v2.integration.MergedWatchProgress(progress, mediaServers, profileService)"))
        assertTrue(src("tmdb/RealCatalog.kt").contains("watch.continueWatching(profile.id)"))
    }

    @Test fun real_library_and_favorites_are_scoped_to_active_profile() {
        val shell = src("shell/AuraShell.kt")
        assertTrue(shell.contains("tmdb?.savedTitles(watch.favorites(active.id), active)"))
        assertTrue(shell.contains("tmdb?.savedTitles(watch.watchlist(active.id), active)"))
        assertTrue(shell.contains("progress?.continueWatching(active.id)"))
        assertTrue(shell.contains("RealLibraryPage(route, savedTitles, watchlistTitles, watchingItems"))
        assertTrue(src("pages/RealLibraryPage.kt").contains("\"Favorites\" to favorites"))
        assertTrue(src("pages/RealLibraryPage.kt").contains("\"Watchlist\" to watchlist"))
    }

    @Test fun youtube_metadata_uses_official_api_and_official_player() {
        assertTrue(VideoId.isValid("dQw4w9WgXcQ"))
        assertFalse(VideoId.isValid("invalid/video"))
        val sample = PublicYouTubeVideo("dQw4w9WgXcQ", "Example", "Official")
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg", sample.artworkUrl)
        val youtube = src("youtube/YouTubePublicFeed.kt")
        assertTrue(youtube.contains("www.googleapis.com/youtube/v3/videos"))
        assertTrue(youtube.contains("chart=mostPopular"))
        assertFalse(youtube.contains("AIza"))
        assertTrue(src("shell/AuraShell.kt").contains("trailer?.invoke(id)"))
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("YOUTUBE_API_KEY"))
    }
}
