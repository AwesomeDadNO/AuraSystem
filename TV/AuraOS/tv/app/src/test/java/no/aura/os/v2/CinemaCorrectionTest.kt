package no.aura.os.v2
import no.aura.os.v2.pages.LibrarySections
import no.aura.os.v2.data.*
import no.aura.os.v2.tmdb.MediaType
import org.junit.Assert.*
import org.junit.Test
class CinemaCorrectionTest {
    private fun src(name:String)=java.io.File("src/main/java/no/aura/os/v2/$name.kt").readText()
    @Test fun saved_titles_are_split_by_actual_media_type_and_sorted() {
        val items=listOf(MediaItem("tmdb:tv:2","Zulu",Artwork()),MediaItem("tmdb:movie:1","Alpha",Artwork()),MediaItem("tmdb:tv:3","Beta",Artwork()))
        assertEquals(listOf("Beta","Zulu"),LibrarySections.titles(items,MediaType.TV).map { it.title })
        assertEquals(listOf("Alpha"),LibrarySections.titles(items,MediaType.MOVIE).map { it.title })
    }
    @Test fun television_has_only_inline_cinema_library_and_popup_has_no_legacy_link() {
        val host=src("overlay/TitleDetailsHost")
        assertFalse(host.contains("cinemaMode")); assertFalse(host.contains("SeasonsSection("))
        assertTrue(host.contains("belowEpisodes = {"))
        assertTrue(host.contains("initialEpisode?.takeIf { cur == ref }"))
        val page=src("details/CinemaSeriesPage")
        assertTrue(page.contains("item(\"cast-related\")")); assertTrue(page.contains("belowEpisodes()"))
        assertFalse(page.contains("onLibrary")); assertFalse(page.contains("HeroButton(\"Cast & related\""))
    }
    @Test fun system_cards_keep_artwork_and_saved_page_always_has_watching_rows() {
        assertTrue(src("shell/AuraShell").contains("Artwork(imageUrl = row.thumbnailArtUri ?: row.posterArtUri)"))
        assertFalse(src("pages/RealLibraryPage").contains("route == Route.LIBRARY"))
        assertTrue(src("pages/RealLibraryPage").contains("systemStatus.ifBlank"))
        val live=src("pages/LiveHomePage")
        assertFalse(live.contains("Text(app.label, style = AuraTypography.CardTitle, maxLines = 1)"))
    }
    @Test fun selected_navigation_keeps_white_text_and_icons() {
        assertTrue(src("shell/AuraTopNavigation").contains("val tint = AuraColors.TextPrimary"))
        assertTrue(src("shell/AuraSideRail").contains("if(selected || interaction.focused) AuraColors.Focus"))
        assertFalse(src("shell/NavigationFocus").contains("AuraColors.NavPill"))
    }
}
