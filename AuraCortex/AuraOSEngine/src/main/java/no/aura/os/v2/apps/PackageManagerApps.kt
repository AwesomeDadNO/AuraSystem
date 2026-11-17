package no.aura.os.v2.apps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import no.aura.os.v2.data.DataMode

/**
 * PackageManager-backed discovery and launch. Discovery only sees what the manifest `<queries>` allows (LEANBACK_LAUNCHER activities and
 * the known streaming packages); QUERY_ALL_PACKAGES is deliberately not used. Launch prefers the Leanback intent and falls back to the
 * regular launch intent; every failure maps to a [LaunchResult].
 */
class PackageManagerApps(private val context: Context) : InstalledAppsSource, AppLauncher, AppIconSource, AppBannerSource {
    private val pm: PackageManager get() = context.packageManager

    @Suppress("DEPRECATION")
    override fun launchableApps(): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val infos = if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0)) else pm.queryIntentActivities(intent, 0)
        return infos.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            LaunchableApp(pkg, runCatching { ri.loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg)
        }
    }

    /** The app's own launcher icon from PackageManager (never a redrawn logo), rasterised to [sizePx]. */
    override fun icon(packageName: String, sizePx: Int): android.graphics.Bitmap? = runCatching {
        val d = pm.getApplicationIcon(packageName)
        val bmp = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp); d.setBounds(0, 0, sizePx, sizePx); d.draw(c)
        bmp
    }.getOrNull()

    /** Draw the installed application's own Leanback banner, with the app icon as a fallback. */
    override fun banner(packageName: String, widthPx: Int, heightPx: Int): android.graphics.Bitmap? = runCatching {
        val component = pm.getLeanbackLaunchIntentForPackage(packageName)?.component
        val d = component?.let { runCatching { pm.getActivityBanner(it) }.getOrNull() } ?: pm.getApplicationBanner(packageName) ?: pm.getApplicationIcon(packageName)
        val bmp = android.graphics.Bitmap.createBitmap(widthPx, heightPx, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val bounds = if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
            val scale = minOf(widthPx.toFloat() / d.intrinsicWidth, heightPx.toFloat() / d.intrinsicHeight)
            val w = (d.intrinsicWidth * scale).toInt()
            val h = (d.intrinsicHeight * scale).toInt()
            android.graphics.Rect((widthPx - w) / 2, (heightPx - h) / 2,
                (widthPx + w) / 2, (heightPx + h) / 2)
        } else android.graphics.Rect(0, 0, widthPx, heightPx)
        d.bounds = bounds
        d.draw(canvas)
        bmp
    }.getOrNull()

    @Suppress("DEPRECATION")
    override fun isInstalled(packageName: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33) pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0)) else pm.getApplicationInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) { false } catch (_: SecurityException) { false }

    /**
     * Opens an official trailer in the installed YouTube TV app only (explicit package, never a browser, never arbitrary URLs). The key must be
     * a plain 11-char YouTube id. Returns false when the key is invalid, the app is missing, or the launch fails; nothing is simulated.
     */
    fun openTrailer(key: String): Boolean {
        if (!TRAILER_KEY.matches(key) || !isInstalled(YOUTUBE_TV)) return false
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$key")).setPackage(YOUTUBE_TV).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { context.startActivity(intent); true } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false } catch (_: RuntimeException) { false }
    }

    override fun launch(packageName: String): LaunchResult {
        val intent = runCatching { pm.getLeanbackLaunchIntentForPackage(packageName) ?: pm.getLaunchIntentForPackage(packageName) }.getOrNull()
            ?: return if (isInstalled(packageName)) LaunchResult.NOT_LAUNCHABLE else LaunchResult.NOT_INSTALLED
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); LaunchResult.LAUNCHED
        } catch (_: ActivityNotFoundException) { LaunchResult.NOT_LAUNCHABLE } catch (_: SecurityException) { LaunchResult.FAILED } catch (_: RuntimeException) { LaunchResult.FAILED }
    }
}

private val TRAILER_KEY = Regex("[A-Za-z0-9_-]{11}")
private const val YOUTUBE_TV = "com.google.android.youtube.tv"

/** SharedPreferences-backed mode store. Default (and any unreadable value) is Demo Mode. */
class PrefsModeStore(private val prefs: SharedPreferences) : ModeStore {
    constructor(context: Context) : this(context.getSharedPreferences("aura_v2_mode", Context.MODE_PRIVATE))
    override fun load(): DataMode = if (prefs.getString(KEY, null) == DataMode.REAL.name) DataMode.REAL else DataMode.DEMO
    override fun save(mode: DataMode) { prefs.edit().putString(KEY, mode.name).apply() }
    private companion object { const val KEY = "data_mode" }
}
