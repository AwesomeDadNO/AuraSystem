package no.aura.os.v2.search

import no.aura.os.v2.apps.AppsService
import no.aura.os.v2.mediaserver.*
import no.aura.os.v2.profiles.ProfileService
import no.aura.os.v2.tmdb.*

fun searchSources(catalog: RealCatalogService?, profiles: ProfileService, apps: AppsService?, music: SearchSource?, servers: MediaServerService?): Map<SearchKind, SearchSource> {
    fun media(type: MediaType, kind: SearchKind) = SearchSource { request ->
        val profile = profiles.state.profiles.firstOrNull { it.id == request.profileId }
            ?: return@SearchSource SearchBatch(emptyList(), message = "Profile is unavailable.")
        val result = catalog?.search(type, request.query, request.page, profile)
        val publicHits = (result as? TmdbResult.Ok)?.value?.first.orEmpty().map {
            SearchHit(it.id, it.title, "TMDB", kind, it.id, it.subtitle, it)
        }
        SearchBatch(publicHits, (result as? TmdbResult.Ok)?.value?.second == true,
            if (result is TmdbResult.Failure) "TMDB: ${result.error.name.lowercase().replace('_', ' ')}" else null)
    }
    return buildMap {
        put(SearchKind.MOVIES, media(MediaType.MOVIE, SearchKind.MOVIES)); put(SearchKind.SERIES, media(MediaType.TV, SearchKind.SERIES))
        put(SearchKind.APPS, SearchSource { request ->
            if (request.isChild) SearchBatch(emptyList(), message = "App launching is restricted in this child profile.")
            else SearchBatch(apps?.launchableApps().orEmpty().filter { it.label.contains(request.query, true) || it.packageName.contains(request.query, true) }
                .distinctBy { it.packageName }.take(40).map { SearchHit(it.packageName, it.label, "Installed Android TV app", SearchKind.APPS, "app:${it.packageName}") },
                message = if (apps == null) "Installed app discovery is unavailable." else null)
        })
        if (music != null) put(SearchKind.MUSIC, music)
    }
}

fun serverSearchSource(servers: MediaServerService?, profiles: ProfileService) = SearchSource { request ->
    val profile = profiles.state.profiles.firstOrNull { it.id == request.profileId }
    if (servers == null || profile == null || profile.isChild || request.page != 1) SearchBatch(emptyList()) else {
        SearchBatch(servers.search(request.profileId, request.query).mapNotNull { (account, entry) ->
            val kind = when (entry.type) { EntryType.MOVIE -> SearchKind.MOVIES; EntryType.SERIES, EntryType.EPISODE -> SearchKind.SERIES; else -> return@mapNotNull null }
            SearchHit(entry.id, entry.title, account.kind.title + " · " + account.serverName, kind,
                "server:${account.key}|${entry.id}", entry.seriesTitle)
        })
    }
}
