package no.aura.os.v2.launcher

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/** Device-wide consent is separate from the revocable Android Accessibility grant. Neither changes HOME. */
data class HomeShortcutState(val consented: Boolean, val accessibilityEnabled: Boolean, val stockHome: HomeWindow?, val paused: Boolean = false) {
    val active get() = consented && accessibilityEnabled && stockHome != null && !paused
    val message get() = when {
        !consented -> "Off — optional device-wide shortcut"
        paused -> "Paused — AuraOS did not stay open after its last automatic starts. It resumes once AuraOS has been open for a few seconds."
        !accessibilityEnabled -> "Waiting for Android Accessibility enablement"
        stockHome == null -> "Unavailable — no validated stock Home activity (normal default Home remains available)"
        else -> "Enabled — reacts when the stock Home screen appears"
    }
}
class HomeShortcut(private val context: Context) {
    private val prefs = context.getSharedPreferences("aura_home_shortcut", Context.MODE_PRIVATE)
    fun consented() = prefs.getBoolean("device_wide_consent", false)
    fun setConsented(enabled: Boolean) { prefs.edit().putBoolean("device_wide_consent", enabled).commit() }
    fun state() = HomeShortcutState(consented(), accessibilityEnabled(), resolvedStockHome(), !launchAllowed())
    private fun unconfirmed() = prefs.getInt("unconfirmed_launches", 0)
    /** False while the crash guard has paused the shortcut. */
    fun launchAllowed() = HomeShortcutCrashGuard.mayLaunch(unconfirmed())
    /** Called right before an automatic start. Synchronous on purpose: the count must survive the crash it guards against. */
    fun noteAutomaticLaunch() { prefs.edit().putInt("unconfirmed_launches", unconfirmed() + 1).commit() }
    /** Called once AuraOS has stayed in the foreground for [HomeShortcutCrashGuard.STABLE_AFTER_MS]. */
    fun confirmStable() { if (unconfirmed() != 0) prefs.edit().putInt("unconfirmed_launches", 0).apply() }
    fun settingsIntent() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    fun openSettings(launch: (Intent) -> Unit): Boolean = HomeSetupPolicy.attempt(listOf(settingsIntent()), launch)
    private fun accessibilityEnabled(): Boolean = runCatching {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val component = ComponentName(context, AuraHomeShortcutService::class.java)
        manager?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)?.any {
            val info = it.resolveInfo.serviceInfo
            ComponentName(info.packageName, info.name) == component
        } == true
    }.getOrDefault(false)
    @Suppress("DEPRECATION")
    fun resolvedStockHome(): HomeWindow? = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addCategory(Intent.CATEGORY_DEFAULT)
        val pm = context.packageManager
        val resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: return@runCatching null
        val candidate = HomeWindow(resolved.packageName, resolved.name)
        val homeActivity = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).any {
            it.activityInfo?.let { info -> HomeWindow(info.packageName, info.name) == candidate } == true
        }
        HomeShortcutPolicy.validatedHome(candidate, context.packageName,
            resolved.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0, homeActivity)
    }.getOrNull()
    companion object {
        const val DISCLOSURE = "Optional and device-wide: after your consent and a separate Android Accessibility grant, Aura observes only window package/class metadata and opens Aura when the validated stock Home screen appears. This is not a Home-key hook; Back returning to stock Home can also trigger it. No screen content, keys, gestures or other app content is read. The normal default Home app setting is preferred. Some TVs may block this shortcut. Turn it off here immediately, and revoke AuraOS Home shortcut in Android Accessibility settings to remove system access."
    }
}
