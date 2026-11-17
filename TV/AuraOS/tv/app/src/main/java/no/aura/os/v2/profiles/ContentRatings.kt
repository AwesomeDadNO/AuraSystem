package no.aura.os.v2.profiles

/**
 * Maps a certification string (TMDB: US MPAA / US TV / Norway style) to a minimum age. Unknown or unrated content returns null,
 * which child profiles treat as blocked (conservative policy).
 */
object ContentRatings {
    private val table = mapOf(
        // US movies
        "G" to 0, "PG" to 8, "PG-13" to 13, "R" to 17, "NC-17" to 18,
        // US TV
        "TV-Y" to 0, "TV-G" to 0, "TV-Y7" to 7, "TV-PG" to 8, "TV-14" to 14, "TV-MA" to 17,
        // Norway / generic numeric
        "A" to 0, "ALL" to 0, "0" to 0, "6" to 6, "7" to 7, "9" to 9, "11" to 11, "12" to 12, "13" to 13, "14" to 14, "15" to 15, "16" to 16, "18" to 18,
    )
    fun minimumAge(certification: String?): Int? = certification?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { table[it] }
}

/** Repository-level child filtering: apply to every catalogue/library result before it reaches the UI, not just to hide buttons. */
object ChildPolicy {
    fun allows(profile: Profile, certification: String?): Boolean {
        if (profile.kind != ProfileKind.CHILD) return true
        val age = ContentRatings.minimumAge(certification) ?: return false   // unknown / unrated: blocked for children
        return age <= (profile.maxAge ?: 0)
    }
    fun <T> filter(profile: Profile, items: List<T>, certificationOf: (T) -> String?): List<T> =
        if (profile.kind != ProfileKind.CHILD) items else items.filter { allows(profile, certificationOf(it)) }
}
