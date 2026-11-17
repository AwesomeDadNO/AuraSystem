package no.aura.os.v2.music

import no.aura.os.v2.mediaserver.*
import no.aura.os.v2.notifications.*
import no.aura.os.v2.search.MusicBrainzRateLimit
import no.aura.os.v2.tmdb.*
import java.net.URLEncoder
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId

/** Metadata only. Commercial distribution must supply a reviewed MusicBrainz service agreement and contact User-Agent. */
class MusicBrainzArtistReleaseSource(private val http: MediaHttp = UrlConnectionMediaHttp(1024 * 1024),
    private val userAgent: String = "AuraOS/0.1 (personal TV music metadata client)",
    private val serviceUseApproved: () -> Boolean = { false }) {
    suspend fun releases(artist: FollowedArtist, today: LocalDate): List<ArtistRelease> {
        check(serviceUseApproved()) { "MusicBrainz service use has not been approved for this distribution" }
        require(ArtistIds.valid(artist.id))
        require(userAgent.length in 10..250 && userAgent.none { it < ' ' })
        val query = "arid:${artist.id} AND firstreleasedate:[${today.minusDays(7)} TO $today]"
        val request = MediaRequest("GET", "https://musicbrainz.org/ws/2/release-group/?query=${URLEncoder.encode(query, "UTF-8")}&fmt=json&limit=100", mapOf("User-Agent" to userAgent))
        val response = MusicBrainzRateLimit.run { http.send(request) }
        check(response.code == 200) { "MusicBrainz unavailable or rate limited" }
        return parse(response.body, artist, today)
    }
    companion object {
        fun parse(json: String?, artist: FollowedArtist, today: LocalDate): List<ArtistRelease> = Json.parse(json).obj()?.get("release-groups").list().mapNotNull { row ->
            val o = row.obj() ?: return@mapNotNull null
            val id = o.str("id")?.takeIf(ArtistIds::valid) ?: return@mapNotNull null
            val date = ReleaseWindow.date(o.str("first-release-date"), today) ?: return@mapNotNull null
            val title = o.str("title")?.takeIf { it.isNotBlank() }?.take(160) ?: return@mapNotNull null
            // Search is not proof of ownership: require the returned artist credit to name the followed UUID.
            if (o["artist-credit"].list().none { it.obj()?.get("artist").obj()?.str("id") == artist.id }) return@mapNotNull null
            ArtistRelease(id, artist.id, artist.name, title, date)
        }.distinctBy { it.id }.take(100)
    }
}
data class ArtistRelease(val id: String, val artistId: String, val artistName: String, val title: String, val date: LocalDate)
class FollowedArtistNotificationAdapter(private val store: FollowedArtistStore, private val source: MusicBrainzArtistReleaseSource,
    private val profileAllowed: (String) -> Boolean, private val zone: ZoneId = ZoneId.systemDefault(), private val now: () -> Long = System::currentTimeMillis) : NotificationSourceAdapter {
    override val category = NotificationCategory.FOLLOWED_ARTIST
    override suspend fun fetch(profile: String): SourceBatch {
        if (!profileAllowed(profile)) return SourceBatch(emptyList(), "Artist metadata is unavailable for this profile.")
        val today = Instant.ofEpochMilli(now()).atZone(zone).toLocalDate()
        val events = store.artists(profile).take(40).flatMap { artist -> source.releases(artist, today) }.map { release ->
            CenterEvent("musicbrainz:${release.artistId}:${release.id}:${release.date}", category, "MusicBrainz", "${release.artistName} · ${release.title}",
                "Release date: ${release.date}. Metadata only; no audio source is connected.", release.date.atStartOfDay(zone).toInstant().toEpochMilli())
        }
        return SourceBatch(events)
    }
}
