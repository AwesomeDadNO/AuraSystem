package no.aura.os.v2

import no.aura.os.v2.cards.CategoryArtwork
import no.aura.os.v2.apps.ShortcutBrand
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ApprovedVisualAssetsTest {
    @Test fun genreArtUsesStableTypedIdsNotTranslatedDisplayNames() {
        assertEquals("action", CategoryArtwork.forId("genre:movie:28"))
        assertEquals("sci-fi", CategoryArtwork.forId("genre:movie:878"))
        assertEquals("sci-fi", CategoryArtwork.forId("genre:tv:10765"))
        assertEquals("animation", CategoryArtwork.forId("genre:tv:16"))
        assertNull(CategoryArtwork.forId("genre:movie:9999"))
        assertNull(CategoryArtwork.forId("genre:movie:28/../../private"))
        assertNull(CategoryArtwork.forId("genre:unknown:28"))
    }
    @Test fun everyMappedGenreAssetExistsAndNoExternalPathsAreUsed() {
        CategoryArtwork.names.forEach { name -> assertTrue(File("src/main/assets/category_art/category-$name.webp").isFile) }
        assertEquals(12, CategoryArtwork.names.size)
    }
    @Test fun approvedLogoAtlasUsesBoundedDistinctCellsAndUnknownPackagesStayNative() {
        val brands = ShortcutBrand.values()
        assertEquals(36, brands.size)
        assertEquals(36, brands.map { it.column to it.row }.toSet().size)
        brands.forEach { assertTrue(it.column in 0..5); assertTrue(it.row in 0..5) }
        assertEquals(ShortcutBrand.TV2, ShortcutBrand.forPackage("no.tv2.sumo"))
        assertEquals(ShortcutBrand.NRK, ShortcutBrand.forPackage("no.nrk.tv"))
        assertEquals(ShortcutBrand.PLEX, ShortcutBrand.forPackage("com.plexapp.android"))
        assertNull(ShortcutBrand.forPackage("clone.no.nrk.tv"))
        assertNull(ShortcutBrand.forPackage("com.google.android.youtube.tvunplugged"))
        assertTrue(File("src/main/assets/app_logos/approved_atlas.png").isFile)
    }
}
