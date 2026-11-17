package no.aura.os.v2

import kotlinx.coroutines.runBlocking
import no.aura.os.v2.details.*
import no.aura.os.v2.tmdb.*
import org.junit.Assert.*
import org.junit.Test

class CinemaLibraryTest {
    @Test fun tv_library_has_exact_requested_tabs_and_movies_keep_details() {
        assertEquals(listOf("Latest TV Shows","Aura Recommended","What's next?"),DetailsTab.forType(MediaType.TV).map { it.label })
        assertEquals(DetailsTab.DETAILS,DetailsTab.forType(MediaType.MOVIE).first())
        assertEquals(listOf(DetailsSection.RELATED),DetailsLayout.sections(MediaType.TV,DetailsTab.LATEST))
    }
    @Test fun latest_queries_real_premieres_excludes_future_and_unknown_dates_and_gates_children() = runBlocking {
        fun show(id:Int,date:String)=tvJson(id,"Show $id").replace("2023-01-0$id",date)
        val http=FakeHttp(mutableMapOf("/discover/tv" to page(show(1,"2025-01-01"),show(2,"2099-01-01"),show(3,"2026-11-12"),show(4,""),show(3,"2026-11-12"))))
        val service=RealCatalogService(repo(http))
        val adult=service.latestTv(ADULT,"2026-11-13") as TmdbResult.Ok
        assertEquals(listOf("tmdb:tv:3","tmdb:tv:1"),adult.value.map { it.id })
        assertTrue(http.seen.any { it.contains("sort_by=first_air_date.desc") && it.contains("first_air_date.lte=2026-11-13") && it.contains("include_null_first_air_dates=false") })
        val child=service.latestTv(KID,"2026-11-13") as TmdbResult.Ok
        assertTrue(child.value.isEmpty())
    }
    @Test fun latest_failures_remain_failures() = runBlocking {
        val service=RealCatalogService(repo(FakeHttp()))
        assertTrue(service.latestTv(ADULT) is TmdbResult.Failure)
    }
    @Test fun season_label_and_artwork_gloss_are_local_to_episode_card() {
        val page=java.io.File("src/main/java/no/aura/os/v2/details/CinemaSeriesPage.kt").readText()
        assertTrue(page.contains("Text(\"Seasons:\""))
        assertTrue(page.contains("width(2.dp).height(38.dp)"))
        assertTrue(page.contains("Box(Modifier.matchParentSize().auraEpisodeGloss(shape))"))
        assertTrue(page.indexOf("auraEpisodeGloss(shape)") < page.indexOf("Text(\"Episode "))
    }
}
