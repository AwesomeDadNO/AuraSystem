package no.aura.os.v2.launcher

/** Only a resolved, system-installed HOME activity can be a shortcut target. No package-name heuristics. */
data class HomeWindow(val packageName: String, val className: String)
object HomeShortcutPolicy {
    private val excluded = setOf("android", "com.android.systemui", "com.android.settings", "com.android.tv.settings")
    fun validatedHome(candidate: HomeWindow?, auraPackage: String, isSystem: Boolean, isHomeActivity: Boolean): HomeWindow? =
        candidate?.takeIf { isSystem && isHomeActivity && it.packageName != auraPackage && it.packageName !in excluded && it.className.isNotBlank() }
}

/**
 * Stops the shortcut from re-opening an AuraOS build that does not stay open. Every automatic start is counted; the count is cleared only
 * when AuraOS has been in the foreground for a while. After [MAX_UNCONFIRMED] starts in a row without that, the shortcut pauses itself, so
 * a build that crashes on launch cannot trap the TV in a Home -> AuraOS -> crash loop. Opening AuraOS by hand (and it staying open) resumes it.
 */
object HomeShortcutCrashGuard {
    const val MAX_UNCONFIRMED = 3
    const val STABLE_AFTER_MS = 10_000L
    fun mayLaunch(unconfirmedLaunches: Int) = unconfirmedLaunches < MAX_UNCONFIRMED
}

/** A home-window appearance triggers once, even if the OS refuses a launch. Repeated metadata never loops. */
class HomeShortcutPolicyGate {
    private var inHome = false
    private var lastLaunchMs: Long? = null
    fun shouldLaunch(consented: Boolean, home: HomeWindow?, window: HomeWindow?, nowMs: Long): Boolean {
        if (!consented || home == null) { inHome = false; return false }
        if (window != home) { inHome = false; return false }
        if (inHome) return false
        inHome = true
        val last = lastLaunchMs
        if (last != null && (nowMs < last || nowMs - last < 1800)) return false
        lastLaunchMs = nowMs
        return true
    }
}
