package no.aura.os.v2.shell

import android.content.Context
import android.content.SharedPreferences

/** SharedPreferences-backed navigation style. Any unreadable or unknown value is the default (Text only). */
class PrefsNavStyleStore(private val prefs: SharedPreferences) : NavStyleStore {
    constructor(context: Context) : this(context.getSharedPreferences("aura_v2_nav", Context.MODE_PRIVATE))
    override fun load(): NavStyle = NavStyle.parse(runCatching { prefs.getString(KEY, null) }.getOrNull())
    override fun save(style: NavStyle) { prefs.edit().putString(KEY, style.name).apply() }
    private companion object { const val KEY = "nav_style" }
}
