package no.aura.os.v2

import no.aura.os.v2.mediaserver.*
import org.junit.Assert.*
import org.junit.Test

class ServerPlaybackBoundaryTest {
    @Test fun onlyExactRelativePlexPartsCanBecomeStreams() {
        assertTrue(ServerPlaybackPolicy.plexPart("/library/parts/123/456/file.mkv"))
        listOf("https://other.example/file", "//other.example/file", "/library/parts/1/../file", "/library/parts/1/file?X-Plex-Token=secret", "/library/parts/1/%2e%2e/file").forEach {
            assertFalse(it, ServerPlaybackPolicy.plexPart(it))
        }
    }
    @Test fun ticksCannotOverflowAndNegativeProgressIsClamped() {
        assertEquals(0L, ServerPlaybackPolicy.ticks(-5))
        assertEquals(120000000L, ServerPlaybackPolicy.ticks(12000))
        assertEquals(Long.MAX_VALUE, ServerPlaybackPolicy.ticks(Long.MAX_VALUE))
    }
}

class ServerLibraryOwnershipTest {
    private val account = ServerAccount(ServerKind.JELLYFIN, "server123", "Library", "https://media.example", false, "user12345", "User", "fixture-token-only-12345", "device123")
    @Test fun aDifferentProfileCannotBrowseOrSendAnyCredential() = kotlinx.coroutines.runBlocking {
        val sent = mutableListOf<MediaRequest>()
        val http = object : MediaHttp { override suspend fun send(request: MediaRequest): MediaResponse { sent += request; return MediaResponse(500, null) } }
        val store = MemoryMediaAccountStore().apply { save("owner", listOf(account)) }
        val service = MediaServerService(store, http, ClientIdentity("device123", "test"))
        assertEquals(MediaResult.Failure(MediaError.FORBIDDEN), service.libraryPlayback.libraries("other", account))
        assertTrue(sent.isEmpty())
    }
    @Test fun changedServerIdentityStopsBeforeTokenIsSent() = kotlinx.coroutines.runBlocking {
        val sent = mutableListOf<MediaRequest>()
        val http = object : MediaHttp { override suspend fun send(request: MediaRequest): MediaResponse {
            sent += request
            return MediaResponse(200, """{"Id":"different123","ServerName":"Other"}""")
        } }
        val store = MemoryMediaAccountStore().apply { save("owner", listOf(account)) }
        val service = MediaServerService(store, http, ClientIdentity("device123", "test"))
        assertEquals(MediaResult.Failure(MediaError.IDENTITY_MISMATCH), service.libraryPlayback.libraries("owner", account))
        assertEquals(1, sent.size)
        assertFalse(sent.single().headers.values.any { it.contains(account.token) })
    }
}

class ServerDecoderWindowTest {
    @Test fun nearbyDecoderReadsReuseWindowAndSeeksInvalidate() {
        assertEquals(1000, MediaRangeWindow.available(500, 1000, 500))
        assertEquals(900, MediaRangeWindow.available(500, 1000, 600))
        assertEquals(0, MediaRangeWindow.available(500, 1000, 499))
        assertEquals(0, MediaRangeWindow.available(500, 1000, 1500))
        assertEquals(0, MediaRangeWindow.available(-1, 1000, 500))
    }
    @Test fun rangeEndSaturatesInsteadOfWrapping() {
        assertEquals(1048575L, MediaRangeWindow.end(0))
        assertEquals(Long.MAX_VALUE, MediaRangeWindow.end(Long.MAX_VALUE - 2))
    }
}

class ServerRotatingPlaybackCredentialTest {
    private val account = ServerAccount(ServerKind.PLEX, "server123", "Server", "https://media.example", false, "user12345", "User", "fixture-resource-token-old", "device123")
    @Test fun rotatedResourceTokenIsUsedForRangesAndReports() = kotlinx.coroutines.runBlocking {
        val requests = mutableListOf<MediaRequest>()
        val http = object : MediaHttp { override suspend fun send(request: MediaRequest): MediaResponse {
            requests += request
            val body = when {
                request.url.endsWith("/identity") -> """{"MediaContainer":{"machineIdentifier":"server123"}}"""
                request.url.endsWith("/library/metadata/1") -> """{"MediaContainer":{"Metadata":[{"Media":[{"Part":[{"key":"/library/parts/1/2/file.mkv"}]}]}]}}"""
                else -> """{"MediaContainer":{"Directory":[]}}"""
            }
            return MediaResponse(200, body)
        } }
        val store = MemoryMediaAccountStore().apply { save("owner", listOf(account)) }
        val service = MediaServerService(store, http, ClientIdentity("device123", "test"))
        val api = service.libraryPlayback
        val handle = (api.playback("owner", account, MediaEntry("1", "Movie", EntryType.MOVIE)) as MediaResult.Ok).value
        val fresh = account.copy(token = "fixture-" + "resource-token-rotated")
        assertTrue(service.add("owner", fresh))
        val headers = (api.streamHeaders("owner", account, handle) as MediaResult.Ok).value
        assertEquals(fresh.token, headers["X-Plex-Token"])
        assertTrue(api.report("owner", account, handle, ServerPlaybackEvent.PROGRESS, 1000, 10000, false) is MediaResult.Ok)
        assertEquals(fresh.token, requests.last().headers["X-Plex-Token"])
        assertTrue(api.streamHeaders("other", account, handle) is MediaResult.Failure)
        val boundary = requests.size
        assertEquals(MediaResult.Failure(MediaError.FORBIDDEN), api.streamHeaders("owner", account.copy(userId = "otheruser"), handle))
        val forged = ServerPlayback(handle.url, handle.entry, null, null, "owner", account.key, account.serverId, account.userId, account.base)
        assertEquals(MediaResult.Failure(MediaError.FORBIDDEN), api.streamHeaders("owner", account, forged))
        assertEquals(boundary, requests.size)
        service.add("owner", fresh.copy(base = "https://different.example"))
        val before = requests.size
        assertEquals(MediaResult.Failure(MediaError.IDENTITY_MISMATCH), api.streamHeaders("owner", account, handle))
        assertEquals(before, requests.size)
    }
}
