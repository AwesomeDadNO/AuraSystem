package no.aura.os.v2.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import no.aura.os.v2.data.MediaItem

enum class SearchKind(val label: String) { MOVIES("Movies"), SERIES("TV Shows"), APPS("Apps"), MUSIC("Music"), SETTINGS("Settings") }
data class SearchRequest(val profileId: String, val query: String, val page: Int = 1, val isChild: Boolean = false)
data class SearchHit(val id: String, val title: String, val source: String, val kind: SearchKind, val target: String,
                     val subtitle: String? = null, val media: MediaItem? = null, val artistId: String? = null)
data class SearchBatch(val hits: List<SearchHit>, val hasMore: Boolean = false, val message: String? = null)
data class SearchResults(val query: String, val groups: Map<SearchKind, SearchBatch>)
fun interface SearchSource { suspend fun search(request: SearchRequest): SearchBatch }

/** No private result cache. Every request carries its profile; cancelling a query cancels all its sources. */
class GlobalSearchService(private val sources: Map<SearchKind, SearchSource>, private val libraries: SearchSource? = null) {
    suspend fun search(request: SearchRequest): SearchResults = supervisorScope {
        val query = request.query.trim().take(160)
        if (query.length < 2 || request.profileId.isBlank()) return@supervisorScope SearchResults(query, emptyMap())
        val bounded = request.copy(query = query, page = request.page.coerceIn(1, 20))
        val privateResults = async {
            try { libraries?.search(bounded) ?: SearchBatch(emptyList()) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { SearchBatch(emptyList(), message = "Connected libraries are unavailable.") }
        }
        val groups = SearchKind.values().map { kind -> async {
            val batch = try { (sources[kind] ?: if(kind==SearchKind.SETTINGS) SettingsSearchSource() else null)?.search(bounded) ?: SearchBatch(emptyList(), message = "No source connected.") }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { SearchBatch(emptyList(), message = "This source is unavailable. Other results are still shown.") }
            val privateBatch = privateResults.await()
            val combined = batch.hits + privateBatch.hits.filter { it.kind == kind }
            kind to batch.copy(hits = combined.filter { it.kind == kind }.distinctBy { it.target }.take(80),
                message = listOfNotNull(batch.message, privateBatch.message.takeIf { kind in setOf(SearchKind.MOVIES, SearchKind.SERIES) }).takeIf { it.isNotEmpty() }?.joinToString(" · "))
        } }.awaitAll().toMap()
        SearchResults(query, groups)
    }
}
