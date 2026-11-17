package no.aura.os.v2
import no.aura.os.v2.connect.*
import org.junit.Assert.*
import org.junit.Test
class PlaylistPushBoundaryTest {
    private fun denied(block:()->Unit) {try{block();fail("Expected profile refusal")}catch(e:ProtocolException){assertEquals(ErrorCode.NOT_AUTHORIZED,e.code)}}
    @Test fun trusted_phone_for_another_profile_cannot_pull_or_push_the_open_profile() {
        for(op in listOf(PlaylistSync.OP,PlaylistSync.PUSH_OP)) {
            var called=false
            val bound=ProfileBoundPlaylistHandler("adult-a",{true},CommandHandler{called=true;emptyMap()})
            denied{bound.handle(testCtx("phone","adult-b",op,emptyMap()))}
            assertFalse(called)
        }
    }
    @Test fun closed_profile_refuses_even_its_previously_authorized_phone() {
        val bound=ProfileBoundPlaylistHandler("adult-a",{false},CommandHandler{error("Must not dispatch")})
        denied{bound.handle(testCtx("phone","adult-a",PlaylistSync.OP,emptyMap()))}
    }
    @Test fun correct_active_profile_keeps_the_existing_handler_result() {
        val bound=ProfileBoundPlaylistHandler("adult-a",{true},CommandHandler{mapOf("ok" to true)})
        assertEquals(true,bound.handle(testCtx("phone","adult-a",PlaylistSync.PUSH_OP,emptyMap()))["ok"])
    }
    @Test fun oversized_push_preserves_the_old_playlist_and_imports_other_valid_items() {
        val original=SyncPlaylist("existing","Original","",listOf(SyncTrack("old","Old","A",1000L)))
        val saved=linkedMapOf(original.id to original)
        val sink=object:PlaylistSink {
            override fun canReceive()=true
            override fun importOne(playlist:SyncPlaylist){saved[playlist.id]=playlist}
        }
        val large=mapOf("id" to "existing","title" to "Replacement","tracks" to (0..PlaylistSync.MAX_TRACKS).map{mapOf("id" to "$it")})
        val valid=PlaylistSync.bodyMap(SyncPlaylist("new","New","",listOf(SyncTrack("n","New","A",1000L))))
        val result=PlaylistPushHandler{sink}.handle(testCtx("phone","adult-a",PlaylistSync.PUSH_OP,mapOf("playlists" to listOf(large,valid))))
        assertEquals(1,result["imported"])
        assertEquals(original,saved["existing"])
        assertTrue(saved.containsKey("new"))
        assertEquals("existing",((result["skipped"] as List<*>).single() as Map<*, *>)["id"])
    }
}

internal fun testCtx(deviceId: String, profileId: String, op: String, args: Map<String, Any?>) = no.aura.cortex.CommandContext(
    no.aura.cortex.AuraConnect.PairedDevice(deviceId, deviceId, "", no.aura.cortex.AuraConnect.DeviceType.PHONE, "", profileId, emptySet(), 0L),
    profileId, op, args)
