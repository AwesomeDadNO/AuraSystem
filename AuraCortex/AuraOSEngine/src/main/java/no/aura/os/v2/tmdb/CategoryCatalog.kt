package no.aura.os.v2.tmdb

import no.aura.os.v2.data.ShelfPageContent

enum class CategoryFeed(val label: String) { TRENDING("Trending"), POPULAR("Popular"), NEW("New"), TOP_RATED("Top Rated") }

/** TMDB discover filters are distinct from its weekly trending feed. */
object CategoryQueries {
    fun discover(type: MediaType, genre: Int, feed: CategoryFeed, today: String): Map<String, String> {
        require(feed != CategoryFeed.TRENDING)
        val date = if (type == MediaType.MOVIE) "primary_release_date" else "first_air_date"
        return mapOf("with_genres" to genre.toString(), "sort_by" to when (feed) {
            CategoryFeed.NEW -> "$date.desc"
            CategoryFeed.TOP_RATED -> "vote_average.desc"
            else -> "popularity.desc"
        }) + when (feed) {
            CategoryFeed.NEW -> mapOf("$date.lte" to today)
            CategoryFeed.TOP_RATED -> mapOf("vote_count.gte" to "100")
            else -> emptyMap()
        }
    }
    fun accepts(title: TmdbTitle, type: MediaType, genre: Int, feed: CategoryFeed, today: String): Boolean =
        title.ref.type == type && genre in title.genreIds && when (feed) {
            CategoryFeed.NEW -> title.date?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull()?.toString()?.let { d -> d <= today } } == true
            CategoryFeed.TOP_RATED -> title.voteCount >= 100
            else -> true
        }
}

data class CategoryContent(
    val content: ShelfPageContent,
    val nextPage: Map<CategoryFeed, Int>,
    val more: Set<CategoryFeed>,
    val errors: Map<CategoryFeed, TmdbError> = emptyMap(),
)
