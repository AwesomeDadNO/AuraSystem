package no.aura.os.v2.amp

enum class AmpDemoTab(val label: String) {
    SEARCH("Search"), DISCOVER("Discover"), NEW_RELEASES("New releases"), FOLLOWING("Following"),
    LIBRARY("Library"), FAVOURITES("Favourites"), PLAYLISTS("Playlists")
}
enum class AmpDemoModule(val label: String) { AMP("Amp"), ALBUMS("Albums"), ARTISTS("Artists"), PLAYLISTS("Playlists") }
enum class AmpDemoTrackTab(val label: String) { RECENTLY_PLAYED("Recently played"), QUEUE("Queue") }
data class AmpDemoTrack(val id: String, val title: String, val artist: String, val duration: Int, val art: String)
object AmpDemoCatalog {
    val tracks = listOf(
        AmpDemoTrack("bloom", "Midnight In Bloom", "Maya Sol", 258, "bloom"),
        AmpDemoTrack("northern", "Northern Lights", "Arctic Echo", 222, "northern"),
        AmpDemoTrack("quiet", "Quiet Streets", "Jonas Vale", 236, "quiet"),
        AmpDemoTrack("golden", "Golden Hour", "Luna Reed", 209, "golden"),
        AmpDemoTrack("blue", "Blue Room", "Elin North", 217, "blue"),
        AmpDemoTrack("night", "Night Drive", "The Coast", 245, "night"),
    )
    fun track(id: String) = tracks.firstOrNull { it.id == id }
}
data class AmpDemoState(
    val selectedId: String = "bloom", val playing: Boolean = false, val seconds: Int = 102,
    val favourites: Set<String> = emptySet(), val queue: List<String> = listOf("golden", "blue", "night"),
    val shuffle: Boolean = false, val repeat: Boolean = false,
    val followed: Set<String> = setOf("Maya Sol"),
) {
    val selected: AmpDemoTrack get() = AmpDemoCatalog.track(selectedId) ?: AmpDemoCatalog.tracks.first()
    fun select(id: String): AmpDemoState = if (AmpDemoCatalog.track(id) == null) this else copy(selectedId = id, seconds = 0, playing = true)
    fun toggleFavourite(id: String) = if (AmpDemoCatalog.track(id) == null) this else copy(favourites = if (id in favourites) favourites - id else favourites + id)
    fun toggleFollow(artist: String) = copy(followed = if (artist in followed) followed - artist else followed + artist)
    fun enqueue(id: String) = if (AmpDemoCatalog.track(id) == null || id in queue) this else copy(queue = queue + id)
    fun next(): AmpDemoState {
        val valid = queue.filter { AmpDemoCatalog.track(it) != null }
        val id = (if (shuffle) valid.lastOrNull() else valid.firstOrNull()) ?: return copy(playing = false, seconds = 0)
        return select(id).copy(queue = valid - id)
    }
    fun trackSection(tab: AmpDemoTrackTab): List<AmpDemoTrack> = when (tab) {
        AmpDemoTrackTab.RECENTLY_PLAYED -> AmpDemoCatalog.tracks.take(4)
        AmpDemoTrackTab.QUEUE -> queue.distinct().mapNotNull(AmpDemoCatalog::track)
    }
    fun playQueued(id: String) = if (id !in queue || AmpDemoCatalog.track(id) == null) this else select(id).copy(queue = queue - id)
    fun seek(value: Int) = copy(seconds = value.coerceIn(0, selected.duration))
    fun tick(delta: Int = 1): AmpDemoState {
        if (!playing) return this
        val elapsed = seconds + delta.coerceAtLeast(0)
        return if (elapsed >= selected.duration) { if (repeat) copy(seconds = 0) else next() } else copy(seconds = elapsed)
    }
    fun visibleTracks(tab: AmpDemoTab, query: String = ""): List<AmpDemoTrack> = if (tab == AmpDemoTab.PLAYLISTS) queue.mapNotNull { AmpDemoCatalog.track(it) } else AmpDemoCatalog.tracks.filter {
        when (tab) {
            AmpDemoTab.SEARCH -> "${it.title} ${it.artist}".contains(query.trim(), ignoreCase = true)
            AmpDemoTab.FAVOURITES -> it.id in favourites
            AmpDemoTab.FOLLOWING -> it.artist in followed
            AmpDemoTab.NEW_RELEASES -> it.id in listOf("quiet", "golden", "blue")
            AmpDemoTab.PLAYLISTS -> it.id in queue
            else -> true
        }
    }
}
fun ampDemoTime(seconds: Int) = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
