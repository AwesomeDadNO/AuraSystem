package no.aura.os.v2

import no.aura.os.v2.notifications.*
import no.aura.os.v2.mediaserver.JsonOut
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class UpdateNoticePolicyTest {
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(key.public.encoded)
    private fun envelope(app: String = "no.aura.os.tv.v2demo", channel: String = "private-perf", version: Long = 2): String {
        val payload = JsonOut.obj("appId" to app, "channel" to channel, "versionCode" to version, "versionName" to "0.2", "publishedAt" to "2026-11-12T12:00:00Z")
        val signature = Signature.getInstance("SHA256withRSA").run { initSign(key.private); update(payload.toByteArray()); sign() }
        return JsonOut.obj("payload" to Base64.getEncoder().encodeToString(payload.toByteArray()), "signature" to Base64.getEncoder().encodeToString(signature))
    }
    @Test fun accepts_only_signed_newer_release_for_this_application_and_channel() {
        assertNotNull(UpdateManifestPolicy.verify(envelope(), publicKey, "no.aura.os.tv.v2demo", "private-perf", 1, 1794499200000L))
        assertNull(UpdateManifestPolicy.verify(envelope(app="legacy.app"), publicKey, "no.aura.os.tv.v2demo", "private-perf", 1, 1794499200000L))
        assertNull(UpdateManifestPolicy.verify(envelope(channel="release"), publicKey, "no.aura.os.tv.v2demo", "private-perf", 1, 1794499200000L))
        assertNull(UpdateManifestPolicy.verify(envelope(version=1), publicKey, "no.aura.os.tv.v2demo", "private-perf", 1, 1794499200000L))
    }
    @Test fun rejects_invalid_signatures_keys_and_future_notices() {
        assertNull(UpdateManifestPolicy.verify(envelope().replace("signature", "broken"), publicKey, "no.aura.os.tv.v2demo", "private-perf", 1, Long.MAX_VALUE))
        assertNull(UpdateManifestPolicy.verify(envelope(), "not-a-key", "no.aura.os.tv.v2demo", "private-perf", 1, Long.MAX_VALUE))
        assertNull(UpdateManifestPolicy.verify(envelope(), publicKey, "no.aura.os.tv.v2demo", "private-perf", 1, 1L))
    }
}
