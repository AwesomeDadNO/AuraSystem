package no.aura.os.v2.youtube

/** A platform-neutral description of an Android VIEW intent; the Android layer builds the real Intent from it. */
data class IntentSpec(val action: String, val uri: String, val packageName: String)

/**
 * Playback is handed to the official YouTube app. AuraOS never extracts, proxies or re-hosts streams and embeds no scraping or downloading
 * code. The id is validated first so nothing user-controlled can reshape the URI.
 */
object PlaybackHandoff {
    const val YOUTUBE_TV_PACKAGE = "com.google.android.youtube.tv"
    fun watch(videoId: String): IntentSpec? =
        if (VideoId.isValid(videoId)) IntentSpec("android.intent.action.VIEW", "https://www.youtube.com/watch?v=$videoId", YOUTUBE_TV_PACKAGE) else null
}
