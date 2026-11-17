package no.aura.os.v2.playback

/** Actual provider results only. URLs are transport values and must never become UI labels. */
data class PlayerSource(val key: String, val label: String, val url: String) {
    override fun toString(): String = "PlayerSource(key=$key, label=$label, url=<redacted>)"
}
object PlayerSourcePolicy {
    fun choices(resolved: List<PlayerSource>): List<PlayerSource> = resolved.filter { it.url.isNotBlank() }.distinctBy { it.url }
}

object TrackSelection {
    /** A successful command alone does not prove a switch if the platform can report the selected track. */
    fun confirmed(previous: Int?, requested: Int?, succeeded: Boolean, actual: Int?): Int? =
        if (!succeeded) previous else if (actual == requested) requested else actual
}
