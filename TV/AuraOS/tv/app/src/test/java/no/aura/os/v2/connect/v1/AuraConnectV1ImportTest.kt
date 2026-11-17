package no.aura.os.v2.connect.v1

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The ported v1 receiver's sanitizer (AuraSyncJournal, identical to feat/aura-connect-protocol) -> AuraOS AmpLibrary items. */
class AuraConnectV1ImportTest {
    private fun t(id: String, title: String) = JSONObject().put("id", id).put("title", title).put("artist", "Artist $id")
        .put("album", "Album").put("cover", "https://e-cdns-images.dzcdn.net/images/cover/$id.jpg")

    @Test fun pushed_playlist_becomes_a_library_playlist_with_song_metadata() {
        val songs = JSONArray().put(t("3135556", "Harder")).put(t("3135553", "One More Time").put("streamUrl", "https://secret/x.mp3"))
            .put(JSONObject().put("id", "bad id").put("title", "x"))
        val frame = JSONObject().put("op", "playlist-push").put("playlist", JSONObject().put("id", "pl_1731").put("title", "Road trip").put("tracks", songs))
        val snapshot = AuraSyncJournal.sanitizePlaylistPush(frame, "arne")
        assertFalse("no stream URL reaches the TV", snapshot.contains("secret"))
        val r = AuraConnectV1Import.parse(snapshot, "arne")
        assertEquals(1, r.playlists.size)
        val (meta, tracks) = r.playlists[0]
        assertEquals("phone:pl_1731", meta.id); assertEquals("Road trip", meta.title); assertEquals(2, meta.trackCount)
        assertEquals(listOf("3135556", "3135553"), tracks.map { it.id })
        assertEquals("One More Time", tracks[1].title); assertEquals("Artist 3135553", tracks[1].artist)
        assertTrue(tracks[0].cover!!.startsWith("https://"))
        assertTrue(r.favourites.isEmpty())
    }

    @Test fun liked_songs_become_favourites() {
        val frame = JSONObject().put("op", "favorites-push").put("tracks", JSONArray().put(t("1", "A")).put(t("2", "B")))
        val r = AuraConnectV1Import.parse(AuraSyncJournal.sanitizeFavoritesPush(frame, "arne"), "arne")
        assertEquals(listOf("1", "2"), r.favourites.map { it.id }); assertEquals("B", r.favourites[1].title)
        assertTrue(r.playlists.isEmpty())
    }

    @Test fun a_snapshot_for_another_profile_or_too_many_songs_is_refused() {
        val frame = JSONObject().put("op", "favorites-push").put("tracks", JSONArray().put(t("1", "A")))
        val snapshot = AuraSyncJournal.sanitizeFavoritesPush(frame, "arne")
        assertThrows(IllegalArgumentException::class.java) { AuraConnectV1Import.parse(snapshot, "kid") }
        val many = JSONArray().apply { repeat(101) { put(t("$it", "S$it")) } }
        assertThrows(IllegalArgumentException::class.java) {
            AuraSyncJournal.sanitizePlaylistPush(JSONObject().put("playlist", JSONObject().put("id", "p").put("title", "x").put("tracks", many)), "arne")
        }
    }
}
