package no.aura.os.v2

import no.aura.os.v2.amp.*
import no.aura.os.v2.settings.ConnectionState
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Eclipse connection levels. Owner-confirmed facts this encodes: an expired ARL still gets a 200 manifest while search and stream answer
 * 401; a fresh ARL gets 200 for manifest, search (tracks) and stream (address). Neither proves that audio plays.
 */
class AmpConnectionLevelsTest {
    private fun failure(stage: AmpProviderStage, status: Int) = AmpProviderFailure(stage, AmpEclipseProtocol.httpProblem(status), status)
    private val rejected = failure(AmpProviderStage.SEARCH, 401)

    @Test fun only_401_is_a_verdict_on_the_credential() {
        assertEquals(AmpProviderProblem.CREDENTIAL_REJECTED, AmpEclipseProtocol.httpProblem(401))
        assertEquals("a blocked network or region also answers 403", AmpProviderProblem.HTTP, AmpEclipseProtocol.httpProblem(403))
        assertEquals(AmpProviderProblem.RATE_LIMITED, AmpEclipseProtocol.httpProblem(429))
        for (s in listOf(500, 502, 503, 599)) assertEquals(AmpProviderProblem.SERVER, AmpEclipseProtocol.httpProblem(s))
        assertEquals(AmpProviderProblem.HTTP, AmpEclipseProtocol.httpProblem(404)); assertEquals(AmpProviderProblem.HTTP, AmpEclipseProtocol.httpProblem(302))
        // each has its own plain message; none of them contains a URL or the credential
        val messages = listOf(rejected, failure(AmpProviderStage.SEARCH, 429), failure(AmpProviderStage.STREAM, 503), AmpProviderFailure(AmpProviderStage.SEARCH, AmpProviderProblem.NETWORK)).map { it.safeMessage }
        assertEquals(4, messages.toSet().size); assertTrue(messages[0].contains("must be renewed")); assertTrue(messages.none { it.contains("http", ignoreCase = true) && it.contains("://") })
        assertEquals("search?q=music", AmpEclipseProtocol.probeRoute())
    }

    @Test fun manifest_alone_is_never_connected() {
        val manifestOnlyRejected = AmpConnection(stored = true, check = AmpVerification(AmpAccess.MANIFEST_AVAILABLE, rejected), checkedAt = 10)
        val s = AmpConnectionPresenter.status(manifestOnlyRejected)
        assertEquals("expired ARL: manifest 200, search 401", ConnectionState.AUTH_FAILED, s.state); assertTrue(s.message!!.contains("must be renewed")); assertNull(s.verifiedAtEpochMs)
        assertTrue(AmpConnectionPresenter.needsRenewal(manifestOnlyRejected))
        // manifest available but music access could not be tested (addon without search): still not Connected
        val untested = AmpConnectionPresenter.status(AmpConnection(true, AmpVerification(AmpAccess.MANIFEST_AVAILABLE, null, musicProbed = false), 10))
        assertEquals(ConnectionState.NOT_CONFIGURED, untested.state); assertTrue(untested.message!!.contains("not verified"))
        // manifest available, then a rate limit / server error / network failure: an error, but NOT a renewal request
        for ((status, expected) in listOf(429 to ConnectionState.ERROR, 503 to ConnectionState.ERROR)) {
            val c = AmpConnection(true, AmpVerification(AmpAccess.MANIFEST_AVAILABLE, failure(AmpProviderStage.SEARCH, status)), 10)
            assertEquals(expected, AmpConnectionPresenter.status(c).state); assertFalse(AmpConnectionPresenter.needsRenewal(c)); assertTrue(AmpConnectionPresenter.status(c).message!!.startsWith("Manifest available, music access not verified."))
        }
        val offline = AmpConnection(true, AmpVerification(AmpAccess.NOT_CONNECTED, AmpProviderFailure(AmpProviderStage.MANIFEST, AmpProviderProblem.NETWORK)), 10)
        assertEquals(ConnectionState.OFFLINE, AmpConnectionPresenter.status(offline).state); assertFalse(AmpConnectionPresenter.needsRenewal(offline))
    }

    @Test fun the_three_levels_are_told_apart() {
        assertEquals(ConnectionState.NOT_CONFIGURED, AmpConnectionPresenter.status(AmpConnection(stored = false)).state)
        val saved = AmpConnectionPresenter.status(AmpConnection(stored = true)); assertEquals(ConnectionState.NOT_CONFIGURED, saved.state); assertTrue(saved.message!!.contains("Verify"))
        val music = AmpConnectionPresenter.status(AmpConnection(true, AmpVerification(AmpAccess.MUSIC_VERIFIED, null), 1234))
        assertEquals(ConnectionState.CONNECTED, music.state); assertEquals(1234L, music.verifiedAtEpochMs)
        assertTrue(music.message!!.startsWith("Music access verified")); assertTrue("it must not claim that audio plays", music.message!!.contains("not confirmed"))
        val played = AmpConnectionPresenter.status(AmpConnection(true, AmpVerification(AmpAccess.MUSIC_VERIFIED, null), 1234, playbackConfirmedAt = 5678))
        assertEquals(ConnectionState.CONNECTED, played.state); assertEquals(5678L, played.verifiedAtEpochMs); assertTrue(played.message!!.startsWith("Playback confirmed"))
        // audio that played without any Verify still counts as confirmed playback
        assertTrue(AmpConnectionPresenter.status(AmpConnection(true, playbackConfirmedAt = 9)).message!!.startsWith("Playback confirmed"))
    }

    @Test fun a_rejection_during_playback_overrides_an_earlier_success_and_keeps_the_library() {
        val c = AmpConnection(true, AmpVerification(AmpAccess.MUSIC_VERIFIED, null), 1234, playbackConfirmedAt = 2000, playbackFailure = failure(AmpProviderStage.STREAM, 401))
        val s = AmpConnectionPresenter.status(c)
        assertEquals("the ARL was revoked after it had worked", ConnectionState.AUTH_FAILED, s.state); assertTrue(s.message!!.contains(AmpConnectionPresenter.KEPT))
        // a network failure during playback is not a renewal request and does not show Connected either
        val net = AmpConnectionPresenter.status(c.copy(playbackFailure = AmpProviderFailure(AmpProviderStage.AUDIO, AmpProviderProblem.NETWORK)))
        assertEquals(ConnectionState.OFFLINE, net.state)
        // renewal never touches local music data: the ARL vault and the library are separate stores
        val vault = File("src/main/java/no/aura/os/v2/amp/AmpProvider.kt").readText().substringAfter("class AmpArlVault").substringBefore("enum class AmpProviderStage")
        assertFalse(vault.contains("AmpLibrary")); assertFalse(vault.contains("playlist", ignoreCase = true))
    }

    @Test fun sheet_lines_after_save_or_verify() {
        assertEquals(listOf("Invalid ARL or secure storage unavailable."), AmpConnectionPresenter.resultLines(false, null))
        val renew = AmpConnectionPresenter.resultLines(true, AmpVerification(AmpAccess.MANIFEST_AVAILABLE, rejected))
        assertEquals(AmpConnectionPresenter.RENEW, renew.first()); assertTrue(renew.contains(AmpConnectionPresenter.KEPT))
        val ok = AmpConnectionPresenter.resultLines(true, AmpVerification(AmpAccess.MUSIC_VERIFIED, null))
        assertTrue(ok[0].startsWith("Music access verified")); assertTrue(ok[1].contains("confirmed only when a track has played"))
        val partial = AmpConnectionPresenter.resultLines(true, AmpVerification(AmpAccess.MANIFEST_AVAILABLE, failure(AmpProviderStage.STREAM, 503)))
        assertTrue(partial[0].contains("NOT verified")); assertTrue(partial[1].contains("server problem"))
        assertTrue(AmpConnectionPresenter.resultLines(true, AmpVerification(AmpAccess.MANIFEST_AVAILABLE, null, musicProbed = false))[1].contains("could not be tested"))
        assertTrue(AmpConnectionPresenter.resultLines(true, AmpVerification(AmpAccess.NOT_CONNECTED, AmpProviderFailure(AmpProviderStage.MANIFEST, AmpProviderProblem.NETWORK)))[0].contains("could not reach"))
    }

    @Test fun wiring_uses_the_levels_and_replacement_clears_what_the_old_credential_proved() {
        val provider = File("src/main/java/no/aura/os/v2/amp/AmpProvider.kt").readText()
        assertTrue("every Eclipse request classifies its HTTP status", provider.contains("fail(stage, AmpEclipseProtocol.httpProblem(status), status)"))
        val check = provider.substringAfter("suspend fun verifyAccess()").substringBefore("suspend fun resolve(")
        assertTrue(check.indexOf("manifest.json") < check.indexOf("AmpAccess.MANIFEST_AVAILABLE")); assertTrue(check.indexOf("AmpAccess.MANIFEST_AVAILABLE") < check.indexOf("probeRoute()"))
        assertTrue(check.indexOf("probeRoute()") < check.indexOf("AmpAccess.MUSIC_VERIFIED")); assertTrue(check.contains("safeStreamUrl(url, credential)"))
        assertFalse("the check never downloads audio", check.contains("inputStream") || check.contains("ExoPlayer"))
        val playback = File("src/main/java/no/aura/os/v2/amp/AmpPlayback.kt").readText()
        val invalidate = playback.substringAfter("fun invalidateSource()").substringBefore("\n")
        for (part in listOf("pause()", "player.stop()", "clearMediaItems()", "providerFailure=null", "audioConfirmedAt=null")) assertTrue(part, invalidate.contains(part))
        assertTrue("pause cancels a resolution in flight", playback.substringAfter("fun pause()").substringBefore("\n").contains("resolving?.cancel()"))
        val shell = File("src/main/java/no/aura/os/v2/shell/AuraShell.kt").readText()
        assertFalse(shell.contains("provider.verify()")); assertFalse(shell.contains("Eclipse manifest verified"))
        assertTrue(shell.contains("ampPlayback?.invalidateSource();ampVault.clear(owner);ampCheck=null"))                    // disconnect
        assertTrue(shell.indexOf("ampPlayback?.invalidateSource()") < shell.indexOf("ampVault.save(owner,raw)"))             // replacement
        assertTrue(shell.contains("AmpConnectionPresenter.status("))
    }
}
