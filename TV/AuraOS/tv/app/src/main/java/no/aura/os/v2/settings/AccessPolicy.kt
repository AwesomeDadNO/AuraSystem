package no.aura.os.v2.settings

import no.aura.os.v2.admin.Authorizer

/**
 * Which Settings items need the System Administrator PIN. One list, used by the UI (to ask for the PIN) and pinned by tests. The service
 * layer ([no.aura.os.v2.profiles.ProfileService], [ModuleSwitches]) enforces the same rules independently of what the UI shows.
 * Ordinary profile preferences and switching profiles stay open.
 */
object AccessPolicy {
    private val open = setOf("users.active", "users.switch", "users.admin")   // switching is open; the Administrator PIN item runs its own flow

    fun requiresAdmin(item: SettingsItem): Boolean = when {
        item.id == SettingsCatalog.DATA_MODE -> true                                   // protected global setting
        item.id.startsWith(SettingsCatalog.MODULE_PREFIX) && item.kind == ItemKind.TOGGLE -> true   // enable / disable modules
        item.category == SettingsCategory.USERS && item.kind == ItemKind.ACTION -> item.id !in open  // create/edit/delete, PINs, child policy, Google accounts
        else -> false
    }
}

sealed interface ModuleToggle {
    data class Changed(val prefs: ModulePrefs) : ModuleToggle
    object NeedsAdmin : ModuleToggle
    object Unknown : ModuleToggle
}

/** Module switches for the active profile. Enabling or disabling a module is a protected action. */
class ModuleSwitches(private val store: ModulePrefsStore, private val authorizer: Authorizer) {
    fun toggle(profileId: String, current: ModulePrefs, itemId: String): ModuleToggle {
        if (!authorizer.isAuthorized()) return ModuleToggle.NeedsAdmin
        val next = SettingsPresenter.toggleModule(current, itemId) ?: return ModuleToggle.Unknown
        authorizer.touch(); store.save(profileId, next)
        return ModuleToggle.Changed(next)
    }
}
