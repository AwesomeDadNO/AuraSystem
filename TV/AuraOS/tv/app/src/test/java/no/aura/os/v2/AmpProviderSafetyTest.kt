package no.aura.os.v2

import no.aura.os.v2.amp.AmpProviderSafety
import org.junit.Assert.*
import org.junit.Test

class AmpProviderSafetyTest {
    private val example = "a".repeat(128)
    @Test fun acceptsBareAndExactManifestArl() {
        assertEquals(example, AmpProviderSafety.parseArl(" $example "))
        assertEquals(example, AmpProviderSafety.parseArl("https://eclipse.ultramax.vip/$example/manifest.json"))
    }
    @Test fun rejectsCredentialLookalikesAndMalformedInput() {
        for (input in listOf("a".repeat(99), "g".repeat(128), "a".repeat(257), "https://other.example/$example/manifest.json", "http://eclipse.ultramax.vip/$example/manifest.json", "https://eclipse.ultramax.vip@$example.example/manifest.json", "https://eclipse.ultramax.vip/$example/manifest.json?q=x", "https://eclipse.ultramax.vip/$example/manifest.json#x", "https://eclipse.ultramax.vip:443/$example/manifest.json")) assertNull(AmpProviderSafety.parseArl(input))
    }
    @Test fun rejectsUnsafeAudioUrlForms() {
        for (url in listOf("http://cdn.example/music.mp3", "file:///music.mp3", "https://localhost/music", "https://127.0.0.1/music", "https://[::1]/music", "https://user:secret@cdn.example/music", "https://cdn.example:8443/music", "https://a.local/music", "https://cdn.example/music#token")) assertFalse(url, AmpProviderSafety.publicHttps(url))
        assertTrue(AmpProviderSafety.publicHttps("https://cdn.example/music.mp3?signature=abc"))
    }
    @Test fun addressFilteringUsesPreciseRanges() {
        fun bytes(ip: String) = ip.split('.').map { it.toInt().toByte() }.toByteArray()
        for (ip in listOf("198.41.0.4", "192.169.1.1", "100.1.1.1", "100.63.255.255", "100.128.0.0", "172.15.255.255", "172.32.0.0", "198.17.255.255", "198.20.0.0", "8.8.8.8")) assertTrue(ip, AmpProviderSafety.publicAddress(bytes(ip)))
        for (ip in listOf("0.1.2.3", "10.0.0.1", "100.64.0.0", "100.127.255.255", "127.0.0.1", "169.254.2.3", "172.16.0.0", "172.31.255.255", "192.168.1.1", "192.0.0.1", "192.0.2.1", "198.18.0.0", "198.19.255.255", "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.1.2.3", "255.255.255.255")) assertFalse(ip, AmpProviderSafety.publicAddress(bytes(ip)))
    }
}
