package no.aura.os.v2.sports

import no.aura.os.v2.tmdb.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Provider boundaries shared by Android and JVM tests. No inferred league, score, time or team identity. */
object SportsLogic {
    const val REFRESH_MS = 60_000L
    fun shouldFetch(enabled: Boolean, visible: Boolean, now: Long, lastAttempt: Long?): Boolean =
        enabled && visible && (lastAttempt == null || now - lastAttempt >= REFRESH_MS)
    fun preferenceKey(profileId: String): String = "aura_v2_sports_" + java.security.MessageDigest.getInstance("SHA-256").digest(profileId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun teamKey(sport: Sport, name: String): String = "${sport.api}:$name"
    fun localTime(raw: String, zone: ZoneId = ZoneId.systemDefault()): String = runCatching {
        DateTimeFormatter.ofPattern("d MMM HH:mm z", Locale.getDefault()).withZone(zone).format(Instant.parse(raw))
    }.getOrDefault("Time unavailable")
    fun parse(text: String): List<LiveMatch>? {
        if (text.length > 512 * 1024) return null
        val root = runCatching { Json.parse(text) as? Map<*, *> }.getOrNull() ?: return null
        val rows = root["matches"] as? List<*> ?: return null
        fun clean(v: Any?, max: Int) = (v as? String)?.filter { it >= ' ' }?.trim()?.take(max)?.takeIf { it.isNotEmpty() }
        fun score(v: Any?): String? = when(v) {
            is Double -> if (v.isFinite() && v >= 0 && v % 1.0 == 0.0 && v <= Int.MAX_VALUE) v.toInt().toString() else null
            is String -> v.takeIf { it.length <= 40 && it.matches(Regex("[0-9 /().:-]+")) }
            else -> null
        }
        return rows.take(50).mapNotNull { row ->
            val m = row as? Map<*, *> ?: return@mapNotNull null
            val id = clean(m["slug"], 120) ?: return@mapNotNull null
            val home = clean(m["home"], 75) ?: return@mapNotNull null
            val away = clean(m["away"], 75) ?: return@mapNotNull null
            LiveMatch(id, home, away, score(m["home_score"]), score(m["away_score"]),
                clean(m["status_text"], 70) ?: clean(m["status"], 70) ?: "Status unavailable",
                clean(m["time"], 60) ?: "", clean(m["league"], 100))
        }.distinctBy { it.id }
    }
}
