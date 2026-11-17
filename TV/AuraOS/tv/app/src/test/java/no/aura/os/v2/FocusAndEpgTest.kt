package no.aura.os.v2
import kotlinx.coroutines.runBlocking
import no.aura.os.v2.cards.CardSizes
import no.aura.os.v2.demo.DemoRepositoryImpl
import no.aura.os.v2.focus.FocusChain
import no.aura.os.v2.pages.*
import org.junit.Assert.*
import org.junit.Test

class FocusAndEpgTest {
    private val c = runBlocking { DemoRepositoryImpl.load() }
    @Test fun focus_chain_goes_down_and_up_without_wrapping() {
        // hero -> row0 -> row1 -> row2 ; last row has no "down"
        assertEquals(1, FocusChain.down(0, 3)); assertEquals(2, FocusChain.down(1, 3)); assertNull(FocusChain.down(2, 3))
        assertEquals(-1, FocusChain.up(0)); assertEquals(0, FocusChain.up(1))
    }
    @Test fun epg_filter_and_rows() {
        val l = c.liveTv
        assertEquals(6, Epg.visibleChannels(l.channels, "All Channels").size)
        assertEquals(listOf("Sports"), Epg.visibleChannels(l.channels, "Sports").map { it.category })
        val rows = Epg.rows(l.programs, l.channels); assertEquals(6, rows.size)
        rows.forEach { r -> assertEquals(r.sortedBy { it.start }, r) }
    }
    @Test fun epg_filter_cycle_wraps() {
        val f = c.liveTv.filters
        var cur = f.first(); repeat(f.size) { cur = Epg.nextFilter(f, cur) }; assertEquals(f.first(), cur)
        assertEquals("News", Epg.nextFilter(f, "All Channels"))
    }
    @Test fun epg_on_air_matches_live_flags() {
        val l = c.liveTv
        l.channels.forEach { ch -> assertEquals(l.programs.firstOrNull { it.channelId == ch.id && it.live }, Epg.onAir(l.programs, ch.id, l.nowFraction)) }
    }
    @Test fun epg_nearest_block_for_vertical_moves() {
        val row = c.liveTv.programs.filter { it.channelId == "ch101" }
        assertEquals(0, Epg.nearestIndex(row, 0.1f)); assertEquals(2, Epg.nearestIndex(row, 0.9f)); assertEquals(0, Epg.nearestIndex(emptyList(), 0.5f))
    }
    @Test fun shelf_layouts_fit_the_canvas_and_keep_shelves_above_the_player() {
        listOf(ShelfLayouts.Home, ShelfLayouts.Movies, ShelfLayouts.TvShows).forEach { l ->
            fun w(n: Int, cardW: Float, gap: Float) = 137f + n * cardW + (n - 1) * gap + 40f
            assertTrue(w(5, l.sizes.continueSize.width.value, l.sizes.continueGap.value) <= 1920f); assertTrue(w(6, l.sizes.compactSize.width.value, l.sizes.compactGap.value) <= 1920f)
            assertTrue(l.hero.height.value in 630f..680f) // larger cinematic hero on 85-inch TV
        }
        // Home compact rows must not run into the player column (261 + 18 margin)
        val home = ShelfLayouts.Home.sizes.compactSize.width.value
        assertTrue(137f + 6 * home + 5 * 12f < 1920f - 261f - 18f)
    }
    @Test fun total_page_heights_fit_1080() {
        val l = ShelfLayouts.Movies; val rows = 4
        val h = l.hero.height.value + rows * (30f + 2f + 16f + l.sizes.compactSize.height.value + l.shelfGap.value)
        assertTrue("Movies should scroll to expose lower shelves: $h", h > 1100f)
    }
}
