package no.aura.os.v2.profiles

import android.content.Context
import android.content.SharedPreferences

/** SharedPreferences-backed profile store. Unreadable data falls back to the initial single admin profile. */
class PrefsProfileStore(private val prefs: SharedPreferences) : ProfileStore {
    constructor(context: Context) : this(context.getSharedPreferences("aura_v2_profiles", Context.MODE_PRIVATE))
    override fun load(): ProfileState? = ProfileCodec.decode(prefs.getString("state", null))
    override fun save(state: ProfileState) { prefs.edit().putString("state", ProfileCodec.encode(state)).apply() }
}
