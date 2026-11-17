package no.aura.os.v2
import kotlinx.coroutines.runBlocking
import no.aura.os.v2.data.*
import no.aura.os.v2.demo.DemoRepositoryImpl
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DemoCatalogTest {
    private val c = runBlocking { DemoRepositoryImpl.load() }
    private val pages = listOf(c.home, c.movies, c.tvShows)
    private fun assets(): List<Artwork> = pages.flatMap { p -> p.hero.map { it.artwork } + p.shelves.flatMap { s -> s.items.map { it.item.artwork } } } +
        listOf(c.auraBox.hero.artwork, c.auraBox.poster, c.auraAmp.hero.artwork, c.liveTv.hero.artwork) + c.auraBox.cast.map { it.artwork } + c.auraBox.moreLikeThis.map { it.item.artwork } +
        c.auraAmp.shelves.flatMap { s -> s.items.map { it.artwork } } + c.liveTv.channels.map { it.artwork } + c.queue.map { it.artwork }

    @Test fun flagged_demo() { assertEquals(DataMode.DEMO, c.mode) }
    @Test fun no_urls_anywhere() {
        val texts = pages.flatMap { p -> p.hero.flatMap { listOf(it.title, it.description) } + p.shelves.flatMap { s -> s.items.map { it.item.title } } } + c.liveTv.programs.map { it.title } + c.auraBox.sources.map { it.label }
        assertTrue(texts.none { it.contains("http", true) || it.contains("://") })
        assertTrue(assets().all { it.imageUrl == null && it.asset != null })
    }
    @Test fun every_bundled_asset_exists() {
        val dir = File("src/main/res/drawable-nodpi"); assertTrue("run from the app module dir", dir.isDirectory)
        assets().forEach { assertTrue("missing ${it.asset}", File(dir, it.asset + ".png").isFile) }
        // ids/names are valid Android resource names
        assets().forEach { assertTrue(it.asset!!.matches(Regex("[a-z][a-z0-9_]*"))) }
    }
    @Test fun home_matches_mockup() {
        val h = c.home
        assertEquals(5, h.hero.size); assertEquals(listOf("continue", "latest", "trending"), h.shelves.map { it.id })
        assertEquals(listOf("Orbital", "Riverland", "The Contender", "Duneward", "The Ledger"), h.shelves[0].items.map { it.item.title })
        assertEquals(listOf("42m left", "18m left", "36m left", "1h 5m left", "27m left"), h.shelves[0].items.map { it.remainingLabel })
        assertEquals(listOf("Ember Falls", "North Star", "The Last Voyage", "Hollow Crown", "Faultline", "Verdigris"), h.shelves[1].items.map { it.item.title })
        assertEquals(listOf("Crimson Tide", "Skylight", "The Archive", "Mirage", "Iron Vale", "Circuit 9"), h.shelves[2].items.map { it.item.title })
        val e = h.hero.first()
        assertEquals("A young cartographer discovers a hidden realm that could unite—or shatter—two worlds.", e.description); assertEquals(listOf("Fantasy", "Adventure", "2h 18m"), e.metadata)
        assertEquals("Through the Mist", c.queue.first().title); assertEquals("Elara", c.queue.first().artist)
    }
    @Test fun movies_match_mockup() {
        val m = c.movies
        assertEquals("The Last Voyage", m.hero.first().title)
        assertEquals(listOf("Continue Watching", "Popular Movies", "New Releases", "Award Winners"), m.shelves.map { it.title })
        assertEquals(listOf("The Silver Realm", "Hollow Crown", "Ember Falls", "Iron Vale", "Verdigris", "Circuit 9"), m.shelves[1].items.map { it.item.title })
        assertEquals(listOf("Duneward", "North Star", "Faultline", "Mirage", "Skylight", "Crimson Tide"), m.shelves[2].items.map { it.item.title })
        assertEquals(listOf("The Archive", "Riverland", "The Contender", "The Last Voyage", "Orbital", "Emerald Plains"), m.shelves[3].items.map { it.item.title })
    }
    @Test fun tv_shows_match_mockup() {
        val t = c.tvShows
        assertEquals("The Northern Crown", t.hero.first().title); assertEquals("U/A 16+", t.hero.first().ratingLabel)
        assertEquals(listOf("Continue Watching", "New Episodes", "Popular Series", "Because You Watched The Silver Realm"), t.shelves.map { it.title })
        assertEquals(listOf("Riverland", "The Contender", "Duneward", "The Ledger", "Faultline"), t.shelves[0].items.map { it.item.title })
        assertEquals(listOf("22m left", "36m left", "1h 5m left", "27m left", "41m left"), t.shelves[0].items.map { it.remainingLabel })
        assertEquals(listOf("The Iron Veil", "Marshlight", "Crown of Ashes", "The Sentinels", "Bloodhollow", "Eastwater"), t.shelves[1].items.map { it.item.title })
    }
    @Test fun ids_unique_per_page_and_progress_valid() {
        pages.forEach { p -> val ids = p.shelves.flatMap { s -> s.items.map { it.item.id } }; assertEquals(ids.size, ids.toSet().size); assertTrue(ids.all { it.startsWith("demo-") }) }
        pages.flatMap { it.shelves }.filter { it.continueStyle }.flatMap { it.items }.forEach { assertTrue(it.progress in 0f..1f && it.remainingLabel.endsWith("left")) }
        pages.forEach { p -> p.shelves.forEach { s -> assertEquals(s.title, s.continueStyle, s.items.all { it.remainingLabel.isNotEmpty() }) } }
    }
    @Test fun every_hero_has_exactly_one_primary_action() {
        (pages.flatMap { it.hero } + listOf(c.auraBox.hero, c.auraAmp.hero, c.liveTv.hero)).forEach { assertEquals(it.title, 1, it.actions.count { a -> a.primary }) }
        assertEquals(listOf("Play", "Open Queue", "View Album"), c.auraAmp.hero.actions.map { it.label })
        assertEquals(listOf("Play", "Resume", "Trailer", "Add to Watchlist", "Open in App"), c.auraBox.hero.actions.map { it.label })
    }
    @Test fun auraBox_content() {
        val b = c.auraBox
        assertEquals(listOf("Ethan Rowe", "Lena Hart", "Marcus Vale", "Adrian Cross", "Elara Voss"), b.cast.map { it.name })
        assertEquals(listOf("as Kael", "as Aria", "as Toren", "as King Varin", "as Queen Lys"), b.cast.map { it.role })
        assertEquals(5, b.moreLikeThis.size); assertEquals(7, b.sources.size); assertEquals(1, b.sources.count { it.highlighted })
    }
    @Test fun auraAmp_content() {
        val a = c.auraAmp
        assertEquals(listOf("For You", "Browse", "Artists", "Albums", "Playlists"), a.tabs)
        assertEquals(listOf("Recently Played", "Recommended For You", "New From Followed Artists"), a.shelves.map { it.title })
        assertEquals(listOf(6, 6, 5), a.shelves.map { it.items.size })
        assertEquals("Velvet Horizon", a.hero.title); assertEquals("Elara Cove", a.hero.subtitle)
    }
    @Test fun sports_content_is_illustrative_and_complete() {
        val s = c.sports
        assertEquals(listOf("Premier League", "NBA", "NFL"), s.groups.map { it.title }); s.groups.forEach { assertEquals(5, it.matches.size) }
        assertEquals(5, s.following.size); assertEquals(5, s.upcoming.size)
        assertEquals("2 – 1", s.hero.score)
        s.groups.flatMap { it.matches }.forEach { m -> if (m.state == MatchState.UPCOMING) assertNull(m.homeScore) else { assertNotNull(m.homeScore); assertNotNull(m.awayScore) } }
        assertTrue(s.groups.flatMap { it.matches }.count { it.state == MatchState.LIVE } == 4)
    }
    @Test fun live_tv_timeline_is_valid() {
        val l = c.liveTv
        assertEquals(6, l.channels.size); assertEquals(listOf(101, 102, 103, 104, 105, 106), l.channels.map { it.number })
        l.programs.forEach { assertTrue(it.title, it.start in 0f..1f && it.end in 0f..1f && it.start < it.end) }
        l.channels.forEach { ch ->
            val row = l.programs.filter { it.channelId == ch.id }.sortedBy { it.start }
            assertTrue(row.size in 2..3)
            row.zipWithNext().forEach { (a, b) -> assertTrue("${ch.id}: ${a.title} overlaps ${b.title}", a.end <= b.start) }
            // the first program of every channel is on air at the demo "now"
            assertEquals(ch.id, true, row.first().start <= l.nowFraction && l.nowFraction < row.first().end || row.any { it.start <= l.nowFraction && l.nowFraction < it.end })
        }
        assertEquals(6, l.timeLabels.size); assertEquals(7, l.filters.size); assertEquals("All Channels", l.filters.first())
    }
    @Test fun every_live_program_is_on_air_at_now() {
        val l = c.liveTv
        l.programs.filter { it.live }.forEach { assertTrue(it.title, it.start <= l.nowFraction && l.nowFraction < it.end) }
        l.programs.filter { !it.live }.forEach { assertFalse(it.title, it.start <= l.nowFraction && l.nowFraction < it.end) }
    }
}
