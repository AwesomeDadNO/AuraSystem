package no.aura.os.v2.google

import android.content.Context
import android.content.Intent
import android.net.Uri

object YouTubeHandoff {
    fun channelIntent(context: Context, channelId: String): Intent? {
        if (!Regex("UC[A-Za-z0-9_-]{22}").matches(channelId)) return null
        return listOf("com.google.android.youtube.tv", "com.google.android.youtube").firstNotNullOfOrNull { pkg ->
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/channel/$channelId")).setPackage(pkg).takeIf { it.resolveActivity(context.packageManager) != null }
        }
    }
    /** Explicit official-app targets only; no arbitrary URL or third-party watch handler is executed. */
    fun videoIntent(context: Context, videoId: String): Intent? {
        if (!Regex("[A-Za-z0-9_-]{11}").matches(videoId)) return null
        return listOf("com.google.android.youtube.tv", "com.google.android.youtube").firstNotNullOfOrNull { pkg ->
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId")).setPackage(pkg)
                .takeIf { it.resolveActivity(context.packageManager) != null }
        }
    }
}
