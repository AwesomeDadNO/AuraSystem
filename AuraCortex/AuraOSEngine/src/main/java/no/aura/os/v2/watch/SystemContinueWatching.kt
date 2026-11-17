package no.aura.os.v2.watch

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.media.tv.TvContract
import android.net.Uri
import no.aura.os.v2.profiles.Profile
import no.aura.os.v2.profiles.ProfileKind

/** Public TV-provider rows are device-wide, not Google-account data or AuraOS profile records. */
data class SystemContinueItem(val id: Long, val title: String, val packageName: String,
    val deepLink: String, val positionMs: Long, val durationMs: Long, val posterArtUri: String? = null, val thumbnailArtUri: String? = null)
data class SystemContinueResult(val items: List<SystemContinueItem>, val message: String)
enum class TvListingsAccess { GRANTED, REQUEST_RUNTIME_PERMISSION, UNAVAILABLE }

object SystemContinuePolicy {
    fun mayRead(profileId: String, optedIn: Boolean, child: Boolean) = profileId.isNotBlank() && optedIn && !child
    /** Artwork never forwards credentials or cleartext traffic. Supports https and local content URIs. */
    fun posterArtwork(link: String?): String? {
        if (link.isNullOrBlank() || link.length > 8192 || link.any { it.isWhitespace() || it.isISOControl() }) return null
        if (link.startsWith("content://") || link.startsWith("android.resource://")) return link
        val uri = runCatching { java.net.URI(link) }.getOrNull() ?: return null
        return link.takeIf { uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null }
    }
    /** Extract only data from the documented intent URI envelope; never deserialize an Intent.
     * All extras, components, flags, selectors, and grants are discarded. The row owner remains authority.
     */
    fun dataLink(link: String, owner: String): String? {
        if (!link.startsWith("intent:")) return link.takeIf(::safeLink)
        if (link.length > 8192 || link.any { it.isWhitespace() || it.isISOControl() }) return null
        val marker = link.indexOf("#Intent;")
        if (marker < 0 || !link.endsWith(";end")) return null
        val fields = link.substring(marker + 8, link.length - 4).split(';')
        if (fields.any { it == "SEL" }) return null
        val schemes = fields.filter { it.startsWith("scheme=") }.map { it.substringAfter('=') }
        val packages = fields.filter { it.startsWith("package=") }.map { it.substringAfter('=') }
        if (schemes.size != 1 || packages.any { it != owner } || packages.size > 1) return null
        val data = schemes.single() + ":" + link.substring(7, marker)
        return data.takeIf(::safeLink)
    }
    /** Only data URIs; never deserialize Intent extras, flags, components, selectors, or grants. */
    fun safeLink(link: String): Boolean {
        if (link.isBlank() || link.length > 8192 || link.any { it.isWhitespace() || it.isISOControl() }) return false
        val scheme = link.substringBefore(':', "").lowercase()
        return scheme.matches(Regex("[a-z][a-z0-9+.-]*")) &&
            scheme !in setOf("intent", "file", "content", "android.resource", "javascript", "data")
    }
}

class SystemContinueWatching(private val context: Context) {
    companion object { const val READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS" }
    private val prefs = context.getSharedPreferences("aura_system_continue_read", Context.MODE_PRIVATE)
    fun enabled(profileId: String): Boolean = profileId.isNotBlank() && prefs.getBoolean("read:$profileId", false)
    fun setEnabled(profileId: String, value: Boolean) {
        if (profileId.isNotBlank()) prefs.edit().putBoolean("read:$profileId", value).apply()
    }
    fun purge(profileId: String) { prefs.edit().remove("read:$profileId").apply() }

    @Suppress("DEPRECATION")
    fun permissionAccess(): TvListingsAccess {
        if (context.checkSelfPermission(READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED) return TvListingsAccess.GRANTED
        val info = runCatching { context.packageManager.getPermissionInfo(READ_TV_LISTINGS, 0) }.getOrNull()
            ?: return TvListingsAccess.UNAVAILABLE
        return if ((info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS)
            TvListingsAccess.REQUEST_RUNTIME_PERMISSION else TvListingsAccess.UNAVAILABLE
    }

    /** Run off the UI thread; no persistent cache, no import into the local profile's progress. */
    fun read(profile: Profile): SystemContinueResult {
        if (!enabled(profile.id)) return SystemContinueResult(emptyList(), "Read Android TV Continue Watching is off for this profile.")
        if (!SystemContinuePolicy.mayRead(profile.id, true, profile.kind == ProfileKind.CHILD))
            return SystemContinueResult(emptyList(), "System Continue Watching is hidden for child profiles because device-wide viewing and ratings cannot be verified.")
        val access = permissionAccess()
        if (access != TvListingsAccess.GRANTED) return SystemContinueResult(emptyList(),
            if (access == TvListingsAccess.REQUEST_RUNTIME_PERMISSION) "Allow TV listings access in Android to read shared Continue Watching."
            else "TV listings access is unavailable. This device may restrict system Continue Watching to its launcher.")
        val rows = mutableListOf<SystemContinueItem>()
        var own = 0
        var skipped = 0
        try {
            val projection = arrayOf("_id", "package_name", "title", "intent_uri", "last_playback_position_millis", "duration_millis", "watch_next_type", "browsable", "poster_art_uri", "thumbnail_art_uri")
            val cursor = context.contentResolver.query(TvContract.WatchNextPrograms.CONTENT_URI, projection, null, null,
                "last_engagement_time_utc_millis DESC")
                ?: return SystemContinueResult(emptyList(), "Android TV provider returned no cursor; shared Continue Watching is unavailable.")
            cursor.use { c ->
                var examined = 0
                while (c.moveToNext() && examined++ < 500) {
                    if (c.getInt(6) != TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE || c.getInt(7) != 1) continue
                    val owner = c.getString(1) ?: continue
                    if (owner == context.packageName) { own++; continue }
                    val poster = SystemContinuePolicy.posterArtwork(c.getString(8))
                    val thumbnail = SystemContinuePolicy.posterArtwork(c.getString(9)) ?: poster
                    val item = SystemContinueItem(c.getLong(0), c.getString(2).orEmpty().take(160), owner,
                        c.getString(3).orEmpty(), c.getLong(4), c.getLong(5), poster, thumbnail)
                    if (item.title.isBlank() || item.positionMs < 0 || (item.durationMs > 0 && item.durationMs <= item.positionMs) || verifiedIntent(item) == null) { skipped++; continue }
                    if (rows.size < 50) rows.add(item)
                }
            }
        } catch (_: SecurityException) {
            return SystemContinueResult(emptyList(), "Android denied the public TV-provider query. No shared Continue Watching data was read.")
        } catch (_: Exception) {
            return SystemContinueResult(emptyList(), "Android TV Continue Watching provider is unavailable or incompatible on this device.")
        }
        if (!enabled(profile.id)) return SystemContinueResult(emptyList(), "Read Android TV Continue Watching was turned off for this profile.")
        val message = when {
            rows.isNotEmpty() -> "${rows.size} shared Android TV items. Only rows exposed by this device are available; this is not Google-account Watchlist sync."
            own > 0 -> "Only AuraOS-owned Continue Watching rows were visible. Cross-app access may be restricted on this device."
            else -> "No supported shared Continue Watching rows are visible. Apps may not publish rows or this device may restrict access."
        }
        return SystemContinueResult(rows, message + if (skipped > 0) " $skipped rows had unavailable or unsupported app links." else "")
    }

    @Suppress("DEPRECATION")
    private fun verifiedIntent(item: SystemContinueItem): Intent? {
        val data = SystemContinuePolicy.dataLink(item.deepLink, item.packageName) ?: return null
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(data)).setPackage(item.packageName)
        val list = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return intent.takeIf { list.any { info -> info.activityInfo?.packageName == item.packageName } }
    }

    fun launch(profile: Profile, item: SystemContinueItem, start: (Intent) -> Unit): Boolean {
        if (!SystemContinuePolicy.mayRead(profile.id, enabled(profile.id), profile.kind == ProfileKind.CHILD)) return false
        val intent = verifiedIntent(item) ?: return false
        start(intent)
        return true
    }
}
