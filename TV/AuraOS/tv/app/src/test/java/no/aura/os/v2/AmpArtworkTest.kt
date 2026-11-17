package no.aura.os.v2
import no.aura.os.v2.amp.AmpArtwork
import org.junit.Assert.*
import org.junit.Test
class AmpArtworkTest {
    private val path="/images/cover/"+"a".repeat(32)+"/1000x1000-000000-80-0-0.jpg"
    @Test fun acceptsOnlyCatalogCdnVariants() {
        assertEquals(1000,AmpArtwork.width("https://cdn-images.dzcdn.net$path"))
        assertTrue(AmpArtwork.allowed("https://e-cdns-images.dzcdn.net$path"))
        listOf("http://cdn-images.dzcdn.net$path","https://cdn-images.dzcdn.net.evil.test$path","https://evil.test@$path","https://cdn-images.dzcdn.net$path?credential=x","https://cdn-images.dzcdn.net:443$path","https://api.deezer.com/artist/27/image").forEach {assertFalse(it,AmpArtwork.allowed(it))}
        assertFalse(AmpArtwork.allowed("https://cdn-images.dzcdn.net"+path.replace("1000x1000","9999x9999")))
    }
}
