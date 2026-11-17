package no.aura.os.v2.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import no.aura.os.v2.MainActivity

/** Metadata-only observer. No node trees, key interception, gestures, overlays or privileged launch permission. */
class AuraHomeShortcutService : AccessibilityService() {
    private val gate = HomeShortcutPolicyGate()
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val shortcut = HomeShortcut(this)
        // Preference is checked on every event, including while Android still has this service bound.
        if (!shortcut.consented()) return
        val window = HomeWindow(event.packageName?.toString() ?: return, event.className?.toString() ?: return)
        if (!gate.shouldLaunch(true, shortcut.resolvedStockHome(), window, SystemClock.elapsedRealtime())) return
        // Crash guard: a build that does not stay open is not re-opened again and again (see HomeShortcutCrashGuard).
        if (!shortcut.launchAllowed()) return
        shortcut.noteAutomaticLaunch()
        // Android/OEM background-launch restrictions remain authoritative. Failure never causes retry loops.
        runCatching { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
    }
    override fun onInterrupt() = Unit
}
