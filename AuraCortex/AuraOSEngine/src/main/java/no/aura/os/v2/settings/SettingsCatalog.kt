package no.aura.os.v2.settings

import no.aura.os.v2.settings.Availability.COMING_LATER
import no.aura.os.v2.settings.ItemKind.*
import no.aura.os.v2.settings.SettingsCategory.*

/** The complete Settings structure. Anything not working yet is COMING_LATER with the task that will deliver it. */
object SettingsCatalog {
    const val DATA_MODE = "general.data_mode"
    const val NAV_STYLE = "general.nav_style"
    const val PB_QUALITY = "playback.quality"; const val PB_AUTOSTART = "playback.autostart"; const val PB_AUDIO = "playback.audio"
    const val PB_AUDIO2 = "playback.audio2"; const val PB_SUBS = "playback.subtitles"; const val PB_SUBLANG = "playback.sublang"
    /** UltraMax is the one connection with its own actions (enter code, verify, remove). */
    const val ULTRAMAX_ITEM = "connection.ULTRAMAX"
    const val MODULE_PREFIX = "module."
    const val CONNECTION_PREFIX = "connection."

    private fun later(id: String, title: String, cat: SettingsCategory, kind: ItemKind, planned: String) = SettingsItem(id, title, cat, kind, COMING_LATER, planned)

    val general = listOf(
        SettingsItem("general.android_tv", "Android TV settings", GENERAL, ACTION),
        SettingsItem("general.launcher_status", "Default launcher status", GENERAL, VALUE),
        SettingsItem("general.set_default", "Set AuraOS as default Home app", GENERAL, ACTION),
        SettingsItem("general.home_button", "Optional Home shortcut (device-wide)", GENERAL, ACTION),
        SettingsItem("general.system_continue", "Read Android TV Continue Watching", GENERAL, ACTION),
        SettingsItem("general.watch_next", "Publish my Continue Watching to Android TV", GENERAL, ACTION),
        SettingsItem("general.notifications", "Notification center and preferences", GENERAL, ACTION),
        SettingsItem("general.media_libraries", "Connected media libraries", GENERAL, ACTION),
        SettingsItem("general.accessibility", "Accessibility", GENERAL, ACTION),
        later("general.language", "Language and region", GENERAL, ACTION, "F12"),
        later("general.theme", "Theme and appearance", GENERAL, ACTION, "F12"),
        SettingsItem("general.motion", "Reduce motion", GENERAL, ACTION),
        later("general.image_quality", "Background and image quality", GENERAL, ACTION, "F4"),
        SettingsItem(PB_QUALITY, "Preferred quality", GENERAL, ACTION),
        later("general.cache", "Cache management", GENERAL, ACTION, "F4"),
        later("general.storage", "Storage information", GENERAL, INFO, "F12"),
        later("general.autoplay", "Autoplay next episode", GENERAL, TOGGLE, "F6"),
        SettingsItem(PB_AUTOSTART, "Auto-start at preferred quality", GENERAL, TOGGLE),
        SettingsItem(PB_AUDIO, "Audio language", GENERAL, ACTION), SettingsItem(PB_AUDIO2, "Secondary audio language", GENERAL, ACTION),
        SettingsItem(PB_SUBS, "Subtitles", GENERAL, TOGGLE), SettingsItem(PB_SUBLANG, "Preferred subtitle language", GENERAL, ACTION),
        later("general.performance", "Performance mode", GENERAL, TOGGLE, "F12"),
        SettingsItem(NAV_STYLE, "Main menu style", GENERAL, INFO),
        SettingsItem(DATA_MODE, "Real mode (TMDB catalogue + installed apps)", GENERAL, TOGGLE),
    )

    val modules = listOf(SettingsItem("module.home", "Home", MODULES, INFO)) +
        ModuleKey.values().map { SettingsItem(MODULE_PREFIX + it.name, it.title, MODULES, TOGGLE) }

    val connections = listOf(
        SettingsItem("auraconnect.pairing_code", "AuraConnect code", CONNECTIONS, ACTION),
        SettingsItem("simkl.auto_sync", "Simkl automatic sync · hourly", CONNECTIONS, TOGGLE),
        SettingsItem("simkl.sync_status", "Simkl sync status", CONNECTIONS, VALUE)
    ) + ConnectionId.values().map { SettingsItem(CONNECTION_PREFIX + it.name, it.title, CONNECTIONS, if (it == ConnectionId.ECLIPSE || it == ConnectionId.ULTRAMAX || it == ConnectionId.SIMKL || it == ConnectionId.PLEX || it == ConnectionId.JELLYFIN || it == ConnectionId.EMBY) ACTION else if (it == ConnectionId.AURAMUSIC) TOGGLE else VALUE) }

    val users = listOf(
        SettingsItem("users.active", "Active profile", USERS, VALUE),
        SettingsItem("users.switch", "Switch profile", USERS, ACTION), SettingsItem("users.create", "Add user", USERS, ACTION),
        SettingsItem("users.edit", "Edit profile", USERS, ACTION), SettingsItem("users.delete", "Delete profile", USERS, ACTION),
        SettingsItem("users.pin", "Profile PIN", USERS, ACTION), SettingsItem("users.kids", "Child content restrictions", USERS, ACTION),
        SettingsItem("users.google", "Google accounts", USERS, ACTION), SettingsItem("users.admin", "Administrator PIN", USERS, ACTION),
    )

    val status = listOf(
        SettingsItem("status.version", "App version", STATUS, VALUE), SettingsItem("status.profile", "Active profile", STATUS, VALUE),
        SettingsItem("status.modules", "Enabled modules", STATUS, VALUE), SettingsItem("status.connected", "Connected services", STATUS, VALUE),
        SettingsItem("status.sync", "Last sync", STATUS, VALUE), SettingsItem("status.cache", "Cache", STATUS, VALUE),
        SettingsItem("status.device", "Device", STATUS, VALUE), SettingsItem("status.errors", "Connection errors", STATUS, VALUE),
    )

    val about = listOf(
        SettingsItem("about.version", "AuraOS version", ABOUT, VALUE),
        SettingsItem("about.build", "Build (git commit)", ABOUT, VALUE), SettingsItem("about.variant", "Build variant", ABOUT, VALUE),
        later("about.changelog", "Changelog", ABOUT, INFO, "later"), later("about.updates", "Check for updates", ABOUT, ACTION, "when a safe update mechanism exists"),
        later("about.licenses", "Licenses", ABOUT, INFO, "later"), later("about.diagnostics", "Diagnostics", ABOUT, ACTION, "F12"),
    )

    fun items(category: SettingsCategory): List<SettingsItem> = when (category) {
        AURASPORT -> emptyList(); GENERAL -> general; MODULES -> modules; CONNECTIONS -> connections; USERS -> users; STATUS -> status; ABOUT -> about
    }
    val all: List<SettingsItem> get() = SettingsCategory.values().flatMap(::items)
}
