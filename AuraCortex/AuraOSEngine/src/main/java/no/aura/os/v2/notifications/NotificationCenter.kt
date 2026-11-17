package no.aura.os.v2.notifications

import java.time.Instant
import java.time.ZoneId

enum class NotificationCategory(val label: String) {
    SYSTEM("Android / system"), AURA_UPDATE("AuraOS updates"), FOLLOWED_ARTIST("Followed artists"),
    WATCHLIST_MOVIE("Watchlist movies"), WATCHLIST_EPISODE("Watchlist episodes"), CONTINUE_WATCHING("Continue Watching")
}
enum class SystemAccess { UNAVAILABLE, NOT_GRANTED, CONNECTED, DISCONNECTED, REVOKED }
data class QuietHours(val enabled: Boolean = false, val startMinute: Int = 1320, val endMinute: Int = 420, val zone: String = ZoneId.systemDefault().id) {
    init { require(startMinute in 0..1439 && endMinute in 0..1439); ZoneId.of(zone) }
    fun contains(now: Long): Boolean {
        if (!enabled) return false
        val local = Instant.ofEpochMilli(now).atZone(ZoneId.of(zone)); val minute = local.hour * 60 + local.minute
        return if (startMinute < endMinute) minute in startMinute until endMinute else minute >= startMinute || minute < endMinute
    }
}
data class CenterPreferences(val enabled: Set<NotificationCategory> = emptySet(), val quietHours: QuietHours = QuietHours())
/** Internal destination keys only; UI resolves these through its own known-item router, never Intent.parseUri. */
data class CenterEvent(val id: String, val category: NotificationCategory, val source: String, val title: String,
    val body: String, val occurredAt: Long, val destinationKey: String? = null, val completed: Boolean = false)
data class CenterItem(val event: CenterEvent, val read: Boolean = false)
data class CenterSnapshot(val preferences: CenterPreferences = CenterPreferences(), val items: List<CenterItem> = emptyList(),
    val seen: Map<String, Long> = emptyMap(), val systemAccess: SystemAccess = SystemAccess.NOT_GRANTED,
    val sourceStates: Map<NotificationCategory, String> = emptyMap())
interface CenterStore { fun load(profile: String): CenterSnapshot; fun save(profile: String, value: CenterSnapshot) }
/** Polling adapters must return verified events only, and report unavailable sources explicitly. Background refresh is best effort through JobScheduler; no exact alarm is installed. */
interface NotificationSourceAdapter { val category: NotificationCategory; suspend fun fetch(profile: String): SourceBatch }
data class SourceBatch(val events: List<CenterEvent>, val unavailableReason: String? = null)
class NotificationCenter(private val store: CenterStore, private val now: () -> Long = System::currentTimeMillis) {
    private val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val changes: kotlinx.coroutines.flow.StateFlow<Long> get() = revision
    private fun persist(profile: String, state: CenterSnapshot) { store.save(profile, state); revision.value = revision.value + 1 }

    private val retentionMs = 30L * 24 * 60 * 60 * 1000
    @Synchronized fun snapshot(profile: String): CenterSnapshot {
        require(profile.isNotBlank())
        val state = store.load(profile); val cutoff = now() - retentionMs
        return state.copy(items = state.items.filter { it.event.occurredAt >= cutoff }.take(100),
            seen = state.seen.filterValues { it >= cutoff }.entries.sortedByDescending { it.value }.take(500).associate { it.toPair() })
    }
    @Synchronized fun configure(profile: String, preferences: CenterPreferences) {
        val state = snapshot(profile)
        persist(profile, state.copy(preferences = preferences, items = state.items.filter { it.event.category in preferences.enabled }))
    }
    @Synchronized fun ingest(profile: String, event: CenterEvent): Boolean {
        val state = snapshot(profile)
        if (event.id.isBlank() || event.id.length > 240 || event.title.isBlank() || event.completed || event.occurredAt > now() || event.occurredAt < now() - retentionMs ||
            event.category !in state.preferences.enabled || event.id in state.seen ||
            (event.category == NotificationCategory.SYSTEM && state.systemAccess != SystemAccess.CONNECTED)) return false
        val clean = event.copy(id = event.id.take(240), title = event.title.take(200), body = event.body.take(600), source = event.source.take(120),
            destinationKey = event.destinationKey?.removePrefix("tmdb:")?.takeIf { Regex("(movie|tv):[0-9]+(:s[0-9]+:e[0-9]+)?").matches(it) })
        persist(profile, state.copy(items = (listOf(CenterItem(clean)) + state.items).take(100), seen = state.seen + (event.id to now())))
        return true
    }
    @Synchronized fun markAllRead(profile: String) {
        val state=snapshot(profile)
        persist(profile,state.copy(items=state.items.map { it.copy(read=true) }))
    }
    @Synchronized fun dismissAll(profile: String) {
        val state=snapshot(profile)
        // Retain seen IDs so polling cannot immediately recreate dismissed events.
        persist(profile,state.copy(items=emptyList()))
    }
    @Synchronized fun markRead(profile: String, id: String) { val s = snapshot(profile); persist(profile, s.copy(items = s.items.map { if (it.event.id == id) it.copy(read = true) else it })) }
    @Synchronized fun dismiss(profile: String, id: String) { val s = snapshot(profile); persist(profile, s.copy(items = s.items.filterNot { it.event.id == id })) }
    fun unread(profile: String) = snapshot(profile).items.count { !it.read }
    fun mayPresentNonIntrusively(profile: String, playbackActive: Boolean) = !playbackActive && !snapshot(profile).preferences.quietHours.contains(now())
    @Synchronized fun setSystemAccess(profile: String, access: SystemAccess) {
        val s = snapshot(profile)
        // Stored system notifications are removed only when the permission itself is gone. DISCONNECTED is transient (app start before the
        // listener binds, profile switch, the shell leaving composition) and must not erase the history the retention period promises.
        val withdrawn = access == SystemAccess.REVOKED || access == SystemAccess.NOT_GRANTED || access == SystemAccess.UNAVAILABLE
        persist(profile, s.copy(systemAccess = access, items = if (withdrawn) s.items.filterNot { it.event.category == NotificationCategory.SYSTEM } else s.items))
    }
    suspend fun refresh(profile: String, adapters: List<NotificationSourceAdapter>) {
        NotificationCategory.values().forEach { category ->
            if (category !in snapshot(profile).preferences.enabled || category == NotificationCategory.SYSTEM) return@forEach
            val adapter = adapters.firstOrNull { it.category == category }
            val batch = try { adapter?.fetch(profile) ?: SourceBatch(emptyList(), "No verified source connected") }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { SourceBatch(emptyList(), "Source unavailable. Try again later.") }
            synchronized(this) { val s = snapshot(profile); persist(profile, s.copy(sourceStates = s.sourceStates + (category to (batch.unavailableReason ?: "Connected")))) }
            if (batch.unavailableReason == null) {
                if (category == NotificationCategory.CONTINUE_WATCHING) synchronized(this) {
                    val active = batch.events.mapNotNull { it.destinationKey?.removePrefix("tmdb:") }.toSet()
                    val s = snapshot(profile)
                    persist(profile, s.copy(items = s.items.filter { it.event.category != category || it.event.destinationKey in active }))
                }
                batch.events.filter { it.category == category }.forEach { ingest(profile, it) }
            }
        }
    }
}
