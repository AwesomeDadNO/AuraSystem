package no.aura.os.v2.amp

import java.net.URL

/** Public catalog & playlist artwork safety policy. Allows Deezer CDN, iTunes/Apple, Spotify and clean HTTPS artwork hosts. */
object AmpArtwork {
    private val DEEZER_IMAGE = Regex("^https://(?:cdn-images|e-cdns-images)\\.dzcdn\\.net/images/(?:cover|artist|playlist)/[a-fA-F0-9]{32}/([0-9]{2,4})x([0-9]{2,4})-[A-Za-z0-9-]+\\.(?:jpg|png|webp)$")

    fun allowed(url: String): Boolean = runCatching {
        val u = URL(url)
        if (u.protocol != "https" || u.port != -1 || u.userInfo != null || url.contains('?') || url.contains('@')) return false
        val host = u.host.lowercase()
        val deezerMatch = DEEZER_IMAGE.matchEntire(url)
        if (deezerMatch != null) {
            val w = deezerMatch.groupValues[1].toInt()
            val h = deezerMatch.groupValues[2].toInt()
            return w in 50..2000 && h in 50..2000
        }
        val isItunesCdn = (host == "mzstatic.com" || host.endsWith(".mzstatic.com") || host == "apple.com" || host.endsWith(".apple.com"))
        val isSpotifyCdn = (host == "scdn.co" || host.endsWith(".scdn.co"))
        isItunesCdn || isSpotifyCdn
    }.getOrDefault(false)

    fun width(url: String): Int? = DEEZER_IMAGE.matchEntire(url)?.let { match ->
        val w = match.groupValues[1].toInt(); val h = match.groupValues[2].toInt()
        w.takeIf { it in 50..2000 && h in 50..2000 }
    } ?: Regex("(\\d{2,4})x(\\d{2,4})").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 500
}
