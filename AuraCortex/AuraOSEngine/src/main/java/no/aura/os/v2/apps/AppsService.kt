package no.aura.os.v2.apps

import no.aura.os.v2.demo.ServiceEntry
import no.aura.os.v2.demo.ServiceState

/** Real Mode state of the Services and All Apps pages. */
data class AppsSnapshot(val apps: List<LaunchableApp>, val streaming: List<ServiceEntry>, val sources: List<ServiceEntry>)

/**
 * Real Mode logic: maps discovery results to UI state and launches apps through an allowlist. No Android or Compose types.
 * Rule: installed != connected. Streaming apps are INSTALLED / NOT_INSTALLED; media sources are always NOT_CONFIGURED in F1.
 */
class AppsService(private val source: InstalledAppsSource, private val launcher: AppLauncher, private val ownPackage: String) {

    fun snapshot(): AppsSnapshot {
        val apps = launchableApps()
        val streaming = ServiceApps.streaming.map { app ->
            ServiceEntry(app.name, if (installedPackage(app) != null) ServiceState.INSTALLED else ServiceState.NOT_INSTALLED)
        }
        val sources = ServiceApps.mediaSources.map { ServiceEntry(it, ServiceState.NOT_CONFIGURED) }
        return AppsSnapshot(apps, streaming, sources)
    }

    /** One entry per package, own app excluded, sorted by label. Discovery failures yield an empty list, never a crash. */
    fun launchableApps(): List<LaunchableApp> =
        runCatching { source.launchableApps() }.getOrDefault(emptyList())
            .filter { it.packageName.isNotBlank() && it.packageName != ownPackage }
            .distinctBy { it.packageName }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))

    /** Launch an app from the launchable list. Only packages that discovery returned are allowed. */
    fun launchApp(packageName: String): LaunchResult {
        if (packageName == ownPackage) return LaunchResult.NOT_LAUNCHABLE
        if (launchableApps().none { it.packageName == packageName }) return LaunchResult.NOT_INSTALLED
        return safeLaunch(packageName)
    }

    /** Launch a Services-page streaming app by its constant name; the package comes from [ServiceApps], never from input data. */
    fun launchService(name: String): LaunchResult {
        val app = ServiceApps.streaming.firstOrNull { it.name == name } ?: return LaunchResult.NOT_LAUNCHABLE
        val pkg = installedPackage(app) ?: return LaunchResult.NOT_INSTALLED
        return safeLaunch(pkg)
    }

    private fun installedPackage(app: StreamingApp): String? =
        app.packages.firstOrNull { runCatching { source.isInstalled(it) }.getOrDefault(false) }

    private fun safeLaunch(pkg: String): LaunchResult = runCatching { launcher.launch(pkg) }.getOrDefault(LaunchResult.FAILED)
}

/** User-facing launch feedback (null = nothing to say). */
object LaunchFeedback {
    fun message(result: LaunchResult, label: String): String? = when (result) {
        LaunchResult.LAUNCHED -> null
        LaunchResult.NOT_INSTALLED -> "$label isn't installed on this device."
        LaunchResult.NOT_LAUNCHABLE -> "$label can't be opened from AuraOS."
        LaunchResult.FAILED -> "$label couldn't be opened. Try again, or open it from the Android TV home screen."
    }
}

/** Remembers which tile launched an app so focus returns to it when AuraOS resumes. The key is consumed once. */
class LaunchFocusTracker {
    private var key: String? = null
    fun onLaunched(key: String) { this.key = key }
    fun consumeOnResume(): String? = key.also { key = null }
}
