package no.aura.os.v2
import kotlinx.coroutines.runBlocking
import no.aura.os.v2.search.*
import org.junit.Assert.*
import org.junit.Test
class SettingsSearchTest {
    @Test fun settings_are_local_search_results_even_without_network_sources()=runBlocking {
        val result=GlobalSearchService(emptyMap()).search(SearchRequest("p","android tv"))
        val hits=result.groups[SearchKind.SETTINGS]!!.hits
        assertTrue(hits.any { it.target=="settings:general.android_tv" })
        assertTrue(hits.all { it.kind==SearchKind.SETTINGS && it.media==null })
    }
    @Test fun norwegian_queries_and_simkl_sync_find_safe_setting_ids()=runBlocking {
        val source=SettingsSearchSource()
        assertTrue(source.search(SearchRequest("p","undertekst")).hits.any { it.target.endsWith("playback.sublang") })
        assertTrue(source.search(SearchRequest("p","simkl autosync")).hits.any { it.target=="settings:simkl.auto_sync" })
    }
}
