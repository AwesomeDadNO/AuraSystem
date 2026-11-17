package no.aura.os.v2.apps

import android.content.Context
import no.aura.os.v2.shell.AppShortcut

/**
 * Device-side recency for media apps actually launched through AuraOS. No Usage Access or
 * QUERY_ALL_PACKAGES permission, and never assumes apps used elsewhere were launched here.
 * Each local profile has independent recency; returned apps must still be installed+launchable.
 */
class RecentMediaShortcuts(context: Context) {
    private val prefs = context.getSharedPreferences("aura_v2_media_recents", Context.MODE_PRIVATE)

    fun order(profileId: String, available: List<AppShortcut>): List<AppShortcut> {
        if (profileId.isBlank()) return available
        val recent = prefs.getString("p:$profileId", "").orEmpty().split('|').filter { it.isNotEmpty() }
        return available.sortedWith(compareBy<AppShortcut> { val i = recent.indexOf(it.packageName); if (i < 0) Int.MAX_VALUE else i })
    }

    fun launched(profileId: String, packageName: String) {
        if (profileId.isBlank() || packageName !in ShortcutPicker.preferred.flatMap { it.second }) return
        val cur = prefs.getString("p:$profileId", "").orEmpty().split('|')
        val next = (listOf(packageName) + cur.filter { it.isNotBlank() && it != packageName }).take(ShortcutPicker.MAX)
        prefs.edit().putString("p:$profileId", next.joinToString("|")).apply()
    }

    fun clear(profileId: String) { prefs.edit().remove("p:$profileId").apply() }
}
