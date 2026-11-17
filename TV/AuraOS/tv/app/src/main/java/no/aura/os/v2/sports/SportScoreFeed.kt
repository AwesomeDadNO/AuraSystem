package no.aura.os.v2.sports

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.HttpURLConnection
import java.net.URL

/** Only the sports documented by SportScore's public widget API are supported. */
enum class Sport(val api: String, val label: String) {
    FOOTBALL("football", "Football"),
    BASKETBALL("basketball", "Basketball"),
    TENNIS("tennis", "Tennis"),
    CRICKET("cricket", "Cricket")
}
data class LiveMatch(
    val id: String, val home: String, val away: String, val homeScore: String?,
    val awayScore: String?, val status: String, val kickoff: String, val league: String? = null,
)
sealed interface SportFeedState {
    data class Ready(val sport: Sport, val matches: List<LiveMatch>, val refreshedAt: Long, val stale: Boolean = false, val message: String? = null) : SportFeedState
    data class Error(val message: String) : SportFeedState
}

/** Source: https://sportscore.com/developers/. No embedded API token, 60s minimum refresh. */
class SportScoreFeed {
    private val cache = mutableMapOf<Sport, SportFeedState.Ready>()
    private val attempts = mutableMapOf<Sport, Long>()
    private val failures = mutableMapOf<Sport, String>()
    private val mutex = Mutex()
    private fun failure(sport: Sport, message: String): SportFeedState {
        failures[sport] = message
        return cache[sport]?.copy(stale = true, message = message) ?: SportFeedState.Error(message)
    }
    suspend fun matches(sport: Sport, enabled: Boolean = true, visible: Boolean = true): SportFeedState = mutex.withLock { withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!enabled || !visible) return@withContext SportFeedState.Error("Sports updates are paused.")
        if (!SportsLogic.shouldFetch(enabled, visible, now, attempts[sport])) {
            return@withContext cache[sport]?.let { cached ->
                failures[sport]?.let { cached.copy(stale = true, message = it) } ?: cached
            } ?: SportFeedState.Error(failures[sport] ?: "Please wait before refreshing scores.")
        }
        attempts[sport] = now
        val url = URL("https://sportscore.com/api/widget/matches/?sport=${sport.api}&limit=20")
        val con = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 6000; readTimeout = 10000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (con.responseCode != 200) return@withContext failure(sport, "SportScore returned HTTP ${con.responseCode}.")
            val bytes = con.inputStream.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > 512 * 1024) return@withContext failure(sport, "Sports response exceeded size limit.")
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            val matches = SportsLogic.parse(String(bytes, Charsets.UTF_8))
                ?: return@withContext failure(sport, "Sports response could not be read.")
            failures.remove(sport)
            SportFeedState.Ready(sport, matches, System.currentTimeMillis()).also { cache[sport] = it }
        } catch (cancel: CancellationException) { throw cancel
        } catch (_: Exception) {
            failure(sport, "Could not contact SportScore. Cached scores may be outdated.")
        } finally { con.disconnect() }
    } }
}

/** Profile-scoped preference and independently selectable sports; no background polling when disabled. */
class SportPreferences(context: Context, profileId: String = "default") {
    private val prefs = context.getSharedPreferences(SportsLogic.preferenceKey(profileId), Context.MODE_PRIVATE)
    fun enabled(): Boolean = prefs.getBoolean("enabled", true)
    fun setEnabled(enabled: Boolean) = prefs.edit().putBoolean("enabled", enabled).apply()
    fun selected(): Set<Sport> {
        val values = prefs.getStringSet("sports", setOf(Sport.FOOTBALL.name)) ?: emptySet()
        return values.mapNotNull { value -> Sport.values().firstOrNull { it.name == value } }.toSet()
    }
    fun setSelected(sports: Set<Sport>) = prefs.edit().putStringSet("sports", sports.map { it.name }.toSet()).apply()
    fun leagues(): Set<String> = prefs.getStringSet("leagues", emptySet())?.toSet() ?: emptySet()
    fun setLeagues(values: Set<String>) = prefs.edit().putStringSet("leagues", values.filter { it.isNotBlank() && it.length <= 120 }.toSet()).apply()
    fun teams(): Set<String> = prefs.getStringSet("teams", emptySet())?.toSet() ?: emptySet()
    fun setTeams(values: Set<String>) = prefs.edit().putStringSet("teams", values.filter { it.isNotBlank() && it.length <= 160 }.take(100).toSet()).apply()
}
