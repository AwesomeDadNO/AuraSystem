package no.aura.os.v2.apps

import no.aura.os.v2.data.DataMode

/** An installed, launchable Android TV app (one entry per package). */
data class LaunchableApp(val packageName: String, val label: String)

/** Installed-app discovery. Platform-free so the logic is testable with fakes; the Android implementation uses PackageManager. */
interface InstalledAppsSource {
    /** Apps that expose a Leanback launcher activity and are visible to this app. May contain duplicates per package. */
    fun launchableApps(): List<LaunchableApp>
    /** True if the package is installed and visible (package-visibility declarations apply). */
    fun isInstalled(packageName: String): Boolean
}

/** Optional native Android TV 16:9 launcher banner. Used by All Apps and top navigation shortcuts; missing banners use a contained original icon. */
interface AppBannerSource {
    fun banner(packageName: String, widthPx: Int = 320, heightPx: Int = 180): android.graphics.Bitmap?
}

/** Launching is separate from discovery. Implementations must never throw. */
interface AppLauncher {
    fun launch(packageName: String): LaunchResult
}

enum class LaunchResult { LAUNCHED, NOT_INSTALLED, NOT_LAUNCHABLE, FAILED }

/** Persisted choice between Demo Mode (default, offline fixtures) and Real Mode (installed apps only in F1). */
interface ModeStore {
    fun load(): DataMode
    fun save(mode: DataMode)
}

class MemoryModeStore(private var mode: DataMode = DataMode.DEMO) : ModeStore {
    override fun load() = mode
    override fun save(mode: DataMode) { this.mode = mode }
}
