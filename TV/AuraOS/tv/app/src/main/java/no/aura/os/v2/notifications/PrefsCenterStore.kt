package no.aura.os.v2.notifications

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Separate keys prevent a profile switch from reading another profile's notification text. */
class PrefsCenterStore(context: Context) : CenterStore {
    private val prefs = context.getSharedPreferences("aura_notification_center", Context.MODE_PRIVATE)
    private fun key(profile: String) = "profile:" + android.util.Base64.encodeToString(profile.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
    override fun load(profile: String): CenterSnapshot = runCatching {
        val o = JSONObject(prefs.getString(key(profile), "{}") ?: "{}")
        val enabled = o.optJSONArray("enabled") ?: JSONArray()
        val quiet = o.optJSONObject("quiet") ?: JSONObject()
        val rows = o.optJSONArray("items") ?: JSONArray()
        val seen = o.optJSONObject("seen") ?: JSONObject()
        val sources = o.optJSONObject("sources") ?: JSONObject()
        CenterSnapshot(CenterPreferences((0 until enabled.length()).mapNotNull { index -> NotificationCategory.values().firstOrNull { it.name == enabled.optString(index) } }.toSet(),
            QuietHours(quiet.optBoolean("on"), quiet.optInt("start",1320), quiet.optInt("end",420), quiet.optString("zone", java.time.ZoneId.systemDefault().id))),
            (0 until rows.length()).mapNotNull { index -> runCatching {
                val row = rows.getJSONObject(index)
                CenterItem(CenterEvent(row.getString("id"), NotificationCategory.valueOf(row.getString("category")), row.getString("source"), row.getString("title"), row.optString("body"), row.getLong("at"), row.optString("destination").takeIf { it.isNotBlank() }), row.optBoolean("read"))
            }.getOrNull() }, seen.keys().asSequence().associateWith { seen.getLong(it) },
            liveAccess[profile] ?: SystemAccess.NOT_GRANTED, // Live capability is process-local.
            sources.keys().asSequence().mapNotNull { key -> NotificationCategory.values().firstOrNull { it.name == key }?.let { it to sources.getString(key) } }.toMap())
    }.getOrDefault(CenterSnapshot())
    // The listener's live connection is process-local, not a persisted permission claim.
    private val liveAccess = mutableMapOf<String, SystemAccess>()
    override fun save(profile: String, value: CenterSnapshot) {
        liveAccess[profile] = value.systemAccess
        val o = JSONObject().put("enabled", JSONArray(value.preferences.enabled.map { it.name }))
        val q = value.preferences.quietHours
        o.put("quiet", JSONObject().put("on",q.enabled).put("start",q.startMinute).put("end",q.endMinute).put("zone",q.zone))
        val rows = JSONArray()
        value.items.forEach { item -> val e = item.event; rows.put(JSONObject().put("id", e.id).put("category", e.category.name).put("source",e.source).put("title",e.title).put("body",e.body).put("at",e.occurredAt).put("destination",e.destinationKey.orEmpty()).put("read",item.read)) }
        o.put("items", rows).put("seen", JSONObject(value.seen)).put("sources", JSONObject(value.sourceStates.mapKeys { it.key.name }))
        prefs.edit().putString(key(profile), o.toString()).apply()
    }
}
