package no.aura.os.v2.cards

/** Art is decorative; mapping is by TMDB media type/genre ID, never localized label or remote path. */
object CategoryArtwork {
    val names = setOf("action", "adventure", "comedy", "drama", "thriller", "crime", "sci-fi", "fantasy", "horror", "romance", "animation", "documentary")
    private val movie = mapOf(28 to "action", 12 to "adventure", 35 to "comedy", 18 to "drama", 53 to "thriller", 80 to "crime", 878 to "sci-fi", 14 to "fantasy", 27 to "horror", 10749 to "romance", 16 to "animation", 99 to "documentary")
    private val tv = mapOf(10759 to "adventure", 35 to "comedy", 18 to "drama", 80 to "crime", 10765 to "sci-fi", 16 to "animation", 99 to "documentary", 9648 to "thriller")
    fun forId(id: String): String? {
        val match = Regex("genre:(movie|tv):([1-9][0-9]{0,5})").matchEntire(id) ?: return null
        val genre = match.groupValues[2].toIntOrNull() ?: return null
        return (if (match.groupValues[1] == "movie") movie else tv)[genre]
    }
}
