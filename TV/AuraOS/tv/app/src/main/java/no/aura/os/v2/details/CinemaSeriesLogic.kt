package no.aura.os.v2.details

import no.aura.os.v2.tmdb.TmdbEpisode

object CinemaSeriesLogic {
    fun selected(episodes: List<TmdbEpisode>, focused: Int?, watched: (Int)->Boolean): TmdbEpisode? =
        episodes.firstOrNull { it.number == focused } ?: episodes.firstOrNull { !watched(it.number) } ?: episodes.firstOrNull()
    fun backdrop(episode: TmdbEpisode?, seriesBackdrop: String?, poster: String?): String? = episode?.stillPath?.takeIf { it.isNotBlank() } ?: seriesBackdrop?.takeIf { it.isNotBlank() } ?: poster
    fun progress(position: Long, duration: Long): Float? = if(position < 0 || duration <= 0) null else (position.toDouble()/duration).coerceIn(0.0,1.0).toFloat()
}
