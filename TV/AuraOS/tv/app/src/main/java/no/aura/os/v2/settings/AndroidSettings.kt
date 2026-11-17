package no.aura.os.v2.settings

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import no.aura.os.v2.profiles.ProfileScope

/** SharedPreferences-backed module switches. Unreadable values fall back to enabled; nothing else is deleted when a module is switched off. */
class PrefsModulePrefsStore(private val prefs: SharedPreferences) : ModulePrefsStore {
    constructor(context: Context) : this(context.getSharedPreferences("aura_v2_modules", Context.MODE_PRIVATE))
    private fun k(profileId: String, name: String) = ProfileScope.key(profileId, "module.$name")
    override fun load(profileId: String) = ModulePrefs(
        auraBox = prefs.getBoolean(k(profileId, "auraBox"), true), auraAmp = prefs.getBoolean(k(profileId, "auraAmp"), true),
        sports = prefs.getBoolean(k(profileId, "sports"), true), liveTv = prefs.getBoolean(k(profileId, "liveTv"), true),
        auraTube = prefs.getBoolean(k(profileId, "auraTube"), true),   // absent in F3 data: defaults to enabled
    )
    override fun save(profileId: String, prefs: ModulePrefs) {
        this.prefs.edit().putBoolean(k(profileId, "auraBox"), prefs.auraBox).putBoolean(k(profileId, "auraAmp"), prefs.auraAmp)
            .putBoolean(k(profileId, "sports"), prefs.sports).putBoolean(k(profileId, "liveTv"), prefs.liveTv).putBoolean(k(profileId, "auraTube"), prefs.auraTube).apply()
    }
    override fun clear(profileId: String) {
        this.prefs.edit().apply { listOf("auraBox", "auraAmp", "sports", "liveTv", "auraTube").forEach { remove(k(profileId, it)) } }.apply()
    }
}

/** Reads (never changes) the current default Home app and the device/app version. */
class AndroidSettingsSources(private val context: Context) : LauncherStatusSource, DeviceInfoSource {
    @Suppress("DEPRECATION")
    override fun status(): LauncherStatus {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pkg = runCatching {
            val ri = if (Build.VERSION.SDK_INT >= 33) context.packageManager.resolveActivity(home, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            else context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ri?.activityInfo?.packageName
        }.getOrNull()
        // "android" is the system resolver (no default chosen).
        return when {
            pkg == null -> LauncherStatus(null, null)
            pkg == context.packageName -> LauncherStatus(true, null)
            pkg == "android" -> LauncherStatus(false, null)
            else -> LauncherStatus(false, pkg)
        }
    }

    @Suppress("DEPRECATION")
    override fun info(): DeviceInfo {
        val pi = runCatching {
            if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            else context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val code = if (pi == null) 0L else if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        return DeviceInfo(pi?.versionName ?: "unknown", code, Build.MODEL ?: "unknown", Build.VERSION.RELEASE ?: "unknown", Build.VERSION.SDK_INT,
            buildSha = no.aura.os.v2.BuildConfig.BUILD_SHA, buildVariant = no.aura.os.v2.BuildConfig.BUILD_TYPE)
    }
}

/** Public device settings entry; OEM TV handles it or caller reports unavailable. */
object AndroidTvSettings {
    fun intent() = Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
