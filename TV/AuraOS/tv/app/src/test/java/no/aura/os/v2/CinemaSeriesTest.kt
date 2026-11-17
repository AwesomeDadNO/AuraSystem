package no.aura.os.v2
import no.aura.os.v2.details.CinemaSeriesLogic
import no.aura.os.v2.tmdb.TmdbEpisode
import org.junit.Assert.*
import org.junit.Test
class CinemaSeriesTest {
    private fun ep(n:Int, art:String?=null)=TmdbEpisode(n,"Episode $n","Story",null,50,art)
    @Test fun focus_wins_then_unwatched_then_first_and_empty_is_safe() {
        val episodes=listOf(ep(1),ep(2),ep(3))
        assertEquals(1,CinemaSeriesLogic.selected(episodes,1) { it==1 }?.number)
        assertEquals(2,CinemaSeriesLogic.selected(episodes,999) { it==1 }?.number)
        assertEquals(1,CinemaSeriesLogic.selected(episodes,null) { true }?.number)
        assertNull(CinemaSeriesLogic.selected(emptyList(),null) { false })
    }
    @Test fun marking_selected_episode_watched_keeps_its_context() {
        val episodes=listOf(ep(1),ep(2))
        val selected=CinemaSeriesLogic.selected(episodes,null) { false }!!
        val marked=setOf(selected.number)
        assertEquals(selected,CinemaSeriesLogic.selected(episodes,selected.number) { it in marked })
    }
    @Test fun backdrop_prefers_real_episode_art_and_falls_back_without_fabrication() {
        assertEquals("still",CinemaSeriesLogic.backdrop(ep(1,"still"),"series","poster"))
        assertEquals("series",CinemaSeriesLogic.backdrop(ep(1),"series","poster"))
        assertEquals("poster",CinemaSeriesLogic.backdrop(null,null,"poster"))
        assertNull(CinemaSeriesLogic.backdrop(null,null,null))
    }
    @Test fun progress_uses_actual_valid_duration_and_is_bounded() {
        assertNull(CinemaSeriesLogic.progress(-1,100))
        assertNull(CinemaSeriesLogic.progress(20,0))
        assertEquals(.25f,CinemaSeriesLogic.progress(25,100)!!,0f)
        assertEquals(1f,CinemaSeriesLogic.progress(120,100)!!,0f)
    }
    @Test fun tv_host_uses_cinema_and_keeps_guarded_playback_and_profile_state() {
        fun src(name:String)=java.io.File("src/main/java/no/aura/os/v2/$name.kt").readText()
        val host=src("overlay/TitleDetailsHost")
        assertTrue(host.contains("if (isTv) {\n                        val fractions"))
        assertTrue(host.contains("CinemaSeriesPage(det, season, eps"))
        assertTrue(host.contains("onPlay = { n -> start(null,season,n) }"))
        assertTrue(host.contains("DetailsLogic.isAired"))
        assertTrue(host.contains("episodeFractions(profile.id, cur, season)"))
        val page=src("details/CinemaSeriesPage")
        assertFalse(page.contains("EpisodeMoreInfo("))
        assertTrue(page.contains("scale=1f"))
        assertTrue(page.contains("onFocused={onSelect(ep.number)}"))
        assertTrue(page.contains("infoFocus.requestFocus()"))
        assertTrue(page.contains("toggleableState="))
        assertTrue(page.contains("onSelect(ep.number); onToggle(ep.number)"))
    }
}
