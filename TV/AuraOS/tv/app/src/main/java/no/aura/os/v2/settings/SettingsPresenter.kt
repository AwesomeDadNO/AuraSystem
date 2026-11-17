package no.aura.os.v2.settings

import no.aura.os.v2.data.DataMode
import no.aura.os.v2.admin.AdminStatus

/** One rendered row. [on] is non-null for working toggles. [enabled] is false for COMING_LATER items. */
data class SettingsRow(val item: SettingsItem, val value: String, val on: Boolean?, val enabled: Boolean)

/** Pure mapping from [SettingsState] to rows and toggle results; no Android or Compose types. */
object SettingsPresenter {

    fun rows(category: SettingsCategory, s: SettingsState): List<SettingsRow> = SettingsCatalog.items(category).map { row(it, s) }

    private fun row(item: SettingsItem, s: SettingsState): SettingsRow {
        if (item.availability == Availability.COMING_LATER) {
            val planned = item.plannedIn?.let { " ($it)" } ?: ""
            return SettingsRow(item, "Coming later$planned", null, enabled = false)
        }
        // Protected items are always selectable: choosing one asks for the Administrator PIN (or runs first-time setup). Enforcement is in the services.
        if (item.id.startsWith("users.") && item.kind == ItemKind.ACTION) {
            return when (item.id) {
                "users.switch" -> SettingsRow(item, "${s.profiles.profiles.size} profiles", null, true)
                "users.admin" -> SettingsRow(item, when (s.admin) { AdminStatus.NOT_SET_UP -> "Not set up"; AdminStatus.LOCKED -> "Locked"; AdminStatus.UNLOCKED -> "Unlocked" }, null, true)
                "users.google" -> SettingsRow(item, s.profiles.profiles.count { it.googleSubject != null }.let { if (it == 0) "None linked" else "$it linked" }, null, true)
                else -> SettingsRow(item, "", null, true)
            }
        }
        return when {
            item.id == SettingsCatalog.DATA_MODE ->
                if (s.realModeAvailable) SettingsRow(item, if (s.mode == DataMode.REAL) "On" else "Off", s.mode == DataMode.REAL, true)
                else SettingsRow(item, "Not available", null, false)
            item.id.startsWith(SettingsCatalog.MODULE_PREFIX) && item.kind == ItemKind.TOGGLE -> {
                val on = s.modules.isEnabled(ModuleKey.valueOf(item.id.removePrefix(SettingsCatalog.MODULE_PREFIX)))
                SettingsRow(item, if (on) "On" else "Off", on, true)
            }
            item.id == SettingsCatalog.PB_QUALITY -> SettingsRow(item, s.playback.quality.label, null, true)
            item.id == SettingsCatalog.PB_AUTOSTART -> SettingsRow(item, if (s.playback.autoStart) "On" else "Off", s.playback.autoStart, true)
            item.id == SettingsCatalog.PB_AUDIO -> SettingsRow(item, s.playback.audio.label, null, true)
            item.id == SettingsCatalog.PB_AUDIO2 -> SettingsRow(item, s.playback.audioSecondary?.label ?: "None", null, true)
            item.id == SettingsCatalog.PB_SUBS -> SettingsRow(item, if (s.playback.subtitlesOn) "On" else "Off", s.playback.subtitlesOn, true)
            item.id == SettingsCatalog.PB_SUBLANG -> SettingsRow(item, s.playback.subtitleLang.label, null, true)
            item.id == "simkl.auto_sync" -> SettingsRow(item, if(s.simklAutoSync) "On · about hourly" else "Off", s.simklAutoSync, !s.profiles.active.isChild)
            item.id == "simkl.sync_status" -> SettingsRow(item,s.simklSyncStatus,null,true)
            item.id == SettingsCatalog.NAV_STYLE -> SettingsRow(item, "Text only", null, true)
            item.id == "module.home" -> SettingsRow(item, "Always on", null, true)
            item.id == "connection.AURAMUSIC" -> SettingsRow(item, if (s.connections.firstOrNull { it.id.name == "AURAMUSIC" }?.state == ConnectionState.CONNECTED) "On" else "Off", null, true)
            item.id.startsWith(SettingsCatalog.CONNECTION_PREFIX) -> SettingsRow(item, status(item, s).state.label, null, true)
            else -> SettingsRow(item, statusValue(item.id, s), null, true)
        }
    }

    fun status(item: SettingsItem, s: SettingsState): ConnectionStatus =
        s.connections.first { it.id.name == item.id.removePrefix(SettingsCatalog.CONNECTION_PREFIX) }

    private fun statusValue(id: String, s: SettingsState): String = when (id) {
        "general.launcher_status" -> launcherText(s.launcher)
        "general.home_button" -> "Consent, Android access and disable options"
        "general.accessibility" -> "Open Android accessibility settings"
        "users.active", "status.profile" -> s.profiles.active.label
        "status.version", "about.version" -> "${s.device.versionName} (build ${s.device.versionCode})"
        "about.build" -> s.device.buildSha
        "about.variant" -> s.device.buildVariant
        "status.modules" -> (listOf("Home") + ModuleKey.values().filter { s.modules.isEnabled(it) }.map { it.title }).joinToString(", ")
        "status.connected" -> "${s.connections.count { it.state == ConnectionState.CONNECTED }} of ${s.connections.size}"
        "status.sync" -> "Not available yet"
        "status.cache" -> "Not available yet"
        "status.device" -> "${s.device.model} · Android ${s.device.androidRelease} (API ${s.device.sdk})"
        "status.errors" -> s.connections.filter { it.isError }.joinToString("; ") { "${it.id.title}: ${it.state.label}" }.ifEmpty { "None" }
        else -> ""
    }

    fun launcherText(l: LauncherStatus) = when (l.isDefault) {
        true -> "AuraOS is the default Home app"
        false -> "AuraOS is not the default Home app" + (l.currentHome?.let { " (current: $it)" } ?: "")
        null -> "Unknown"
    }

    /** Lines for the connection detail dialog. Actions are listed as not yet available. */
    fun connectionLines(c: ConnectionStatus): List<String> {
        val verified = c.verifiedAtEpochMs?.let { "Last verified: $it" } ?: "Last verified: never"
        return listOfNotNull(
            "Status: ${c.state.label}", c.message?.let { "Note: $it" }, verified,
            "Configure, Connect, Test and Disconnect are not available yet (${c.id.plannedIn}).",
        )
    }

    /** Applies a toggle. Returns the new module prefs, or null when the id is not a module toggle. Home has no toggle. */
    fun toggleModule(prefs: ModulePrefs, itemId: String): ModulePrefs? {
        if (!itemId.startsWith(SettingsCatalog.MODULE_PREFIX)) return null
        val key = ModuleKey.values().firstOrNull { it.name == itemId.removePrefix(SettingsCatalog.MODULE_PREFIX) } ?: return null
        return prefs.toggled(key)
    }
}
