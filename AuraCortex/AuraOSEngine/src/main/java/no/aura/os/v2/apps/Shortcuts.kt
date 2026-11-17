package no.aura.os.v2.apps

/**
 * Chooses the external streaming apps offered as icon-only shortcuts. Pure: only apps that discovery says are installed AND launchable can
 * be picked, in a fixed preference order (never alphabetical, never from untrusted data). Package names here are constants.
 */
object ShortcutPicker {
    const val MAX = 5
    /** Preference order of well-known streaming apps. A listed package that is not installed is simply skipped. */
    val preferred: List<Pair<String, List<String>>> = listOf(
        "YouTube" to listOf("com.google.android.youtube.tv", "com.google.android.youtube.tvunplugged"),
        "Netflix" to listOf("com.netflix.ninja"),
        "Prime Video" to listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod"),
        "Disney+" to listOf("com.disney.disneyplus"),
        "Max" to listOf("com.wbd.stream", "com.hbo.hbonow"),
        "Apple TV" to listOf("com.apple.atve.androidtv.appletv"),
        "Plex" to listOf("com.plexapp.android"),
    )

    fun pick(installed: List<LaunchableApp>, max: Int = MAX): List<no.aura.os.v2.shell.AppShortcut> {
        val byPackage = installed.associateBy { it.packageName }
        return preferred.mapNotNull { (name, pkgs) ->
            val hit = pkgs.firstOrNull { it in byPackage } ?: return@mapNotNull null
            no.aura.os.v2.shell.AppShortcut(hit, byPackage[hit]?.label?.takeIf { it.isNotBlank() } ?: name)
        }.take(max.coerceIn(0, MAX))
    }
}

/** Installed-app icon source (PackageManager). Blocking: call off the main thread. Null when the package has no loadable icon. */
interface AppIconSource { fun icon(packageName: String, sizePx: Int): android.graphics.Bitmap? }
