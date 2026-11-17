package no.aura.os.v2.playback

import no.aura.os.v2.data.Artwork

/** A connected provider must supply explicit content advice and attribution; ratings never imply categories. */
data class VerifiedContentAdvisory(val text: String, val source: String)
data class PlaybackMetadata(
    val artwork: Artwork? = null,
    val certification: String? = null,
    val ratingSource: String? = null,
    val advisories: List<VerifiedContentAdvisory> = emptyList(),
    val backdropArtwork: Artwork? = null,
)

object PlaybackAdvisoryPolicy {
    const val DISPLAY_MS = 8_000L
    fun banner(metadata: PlaybackMetadata): String {
        val rating = metadata.certification?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { value -> metadata.ratingSource?.trim()?.takeIf { it.isNotEmpty() }?.let { "Rated $value · $it" } }
        val verified = metadata.advisories.filter { it.text.isNotBlank() && it.source.isNotBlank() }
            .map { "${it.text.trim()} (${it.source.trim()})" }
        return (listOf(rating ?: "Age rating unavailable") + if (verified.isEmpty()) listOf("Detailed content advisory unavailable") else verified).joinToString("\n")
    }
}
