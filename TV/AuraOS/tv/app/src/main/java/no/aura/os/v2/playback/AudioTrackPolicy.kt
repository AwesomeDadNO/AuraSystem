package no.aura.os.v2.playback

/** Distinguishes an absent track from a discovered track the device cannot decode. */
object AudioTrackPolicy {
    fun message(tracks: List<TrackInfo>, selected: Int?): String? = when {
        tracks.isEmpty() -> "This source has no discovered audio tracks."
        tracks.none { it.supported } -> "This TV cannot decode the audio tracks in this source. Try another source."
        selected == null -> "No audio track is selected. Choose an available audio track."
        else -> null
    }
}

/** Aura's subtitle overlay renders text cues, not bitmap subtitle images. */
object TextTrackPolicy {
    fun canRender(mime: String?): Boolean = mime != null && mime in setOf(
        "text/vtt", "application/x-mp4-vtt", "application/ttml+xml", "application/x-subrip",
        "text/x-ssa", "application/x-quicktime-tx3g", "application/cea-608", "application/x-mp4-cea-608", "application/cea-708"
    )
}
