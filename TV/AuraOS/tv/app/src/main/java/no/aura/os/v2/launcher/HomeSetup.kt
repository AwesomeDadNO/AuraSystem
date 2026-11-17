package no.aura.os.v2.launcher

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/** Request initiation never establishes success; refresh after resume/restart resolves the actual Home handler. */
data class HomeSetupState(val isDefault: Boolean, val currentPackage: String?, val canRequest: Boolean, val message: String)
class HomeSetup(private val context: Context) {
    private fun roleManager(): RoleManager? = if (Build.VERSION.SDK_INT >= 29) context.getSystemService(RoleManager::class.java) else null
    fun requestIntent(): Intent? {
        val request = runCatching {
            val roles = roleManager()
            if (Build.VERSION.SDK_INT >= 29 && roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME))
                roles.createRequestRoleIntent(RoleManager.ROLE_HOME) else null
        }.getOrNull()
        if (request != null) return request
        return Intent(Settings.ACTION_HOME_SETTINGS)
    }
    /** Pass an Activity-result launcher here; no package-visibility preflight may suppress the role UI. */
    fun request(launch: (Intent) -> Unit): Boolean {
        return HomeSetupPolicy.attempt(listOfNotNull(requestIntent(), Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_SETTINGS)), launch)
    }
    fun openAccessibility(launch: (Intent) -> Unit): Boolean = openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS, launch)
    private fun openSettings(action: String, launch: (Intent) -> Unit): Boolean {
        return HomeSetupPolicy.attempt(listOf(action, Settings.ACTION_SETTINGS).map(::Intent), launch)
    }
    @Suppress("DEPRECATION")
    fun refresh(): HomeSetupState {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addCategory(Intent.CATEGORY_DEFAULT)
        val handler = runCatching { context.packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull()
        return HomeSetupPolicy.state(context.packageName, handler, requestIntent() != null)
    }
}
