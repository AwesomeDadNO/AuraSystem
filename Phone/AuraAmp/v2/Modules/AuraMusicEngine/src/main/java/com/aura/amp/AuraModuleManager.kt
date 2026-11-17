package com.aura.amp

import android.content.Context
import android.content.SharedPreferences

object AuraModuleManager {
    private const val PREFS_NAME = "aura_modules_config"

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isModuleEnabled(context: Context, moduleId: String, defaultEnabled: Boolean = true): Boolean {
        return prefs(context).getBoolean("mod_$moduleId", defaultEnabled)
    }

    fun setModuleEnabled(context: Context, moduleId: String, enabled: Boolean) {
        prefs(context).edit().putBoolean("mod_$moduleId", enabled).apply()
    }

    val availableModules = listOf(
        ModuleInfo("cast", "Google Cast", "Enables casting audio to speakers and TV receivers"),
        ModuleInfo("spotify", "Spotify Import", "Enables importing playlists from Spotify via PKCE"),
        ModuleInfo("lansync", "Local LAN Sync", "Enables 6-digit secure peer-to-peer sync over local network"),
        ModuleInfo("auraconnect", "AuraConnect", "Enables pairing and syncing with Aura TV / desktop receivers"),
        ModuleInfo("updates", "Auto-Updates", "Enables checking for secure app updates from feed")
    )
}

data class ModuleInfo(
    val id: String,
    val name: String,
    val description: String
)
