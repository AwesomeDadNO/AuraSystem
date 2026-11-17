package no.aura.os.v2.demo

import no.aura.os.v2.brand.BrandLabels

import no.aura.os.v2.data.*

/**
 * Demo Mode content for every v2 page. ILLUSTRATIVE ONLY: fictional titles, people, teams, scores and schedules transcribed from the approved
 * mockups. Nothing is a real catalogue entry, real availability, live score, EPG entry or watch progress. No network, no accounts.
 */
object DemoRepositoryImpl : DemoRepository {
    private fun a(n: String) = Art.a(n)

    private val auraBox = AuraBoxContent(
        hero = HeroEntry(
            "demo-box-hero", BrandLabels.AURABOX, "The Silver Realm", "A young cartographer discovers a hidden realm that could unite—or shatter—two worlds.", "U/A 13+",
            listOf("Fantasy", "Adventure", "2h 18m", "4K HDR", "Dolby Atmos"), a("art_hero_aurabox"),
            actions = listOf(
                HeroAction("play", "Play", HeroIcon.PLAY, primary = true), HeroAction("resume", "Resume", HeroIcon.RESUME), HeroAction("trailer", "Trailer", HeroIcon.TRAILER),
                HeroAction("watchlist", "Add to Watchlist", HeroIcon.ADD), HeroAction("open", "Open in App", HeroIcon.OPEN),
            ),
        ),
        poster = a("art_poster_silver_realm"), metadata = listOf("Fantasy", "Adventure", "2h 18m", "4K HDR", "Dolby Atmos"), resumeProgress = 0.55f,
        availableOn = listOf("AURA+", "Prime Video", "Disney+", "Netflix", "+3 more"),
        cast = listOf(
            CastMember("demo-cast-1", "Ethan Rowe", "as Kael", a("art_cast_ethan")), CastMember("demo-cast-2", "Lena Hart", "as Aria", a("art_cast_lena")),
            CastMember("demo-cast-3", "Marcus Vale", "as Toren", a("art_cast_marcus")), CastMember("demo-cast-4", "Adrian Cross", "as King Varin", a("art_cast_adrian")),
            CastMember("demo-cast-5", "Elara Voss", "as Queen Lys", a("art_cast_elara")),
        ),
        moreLikeThis = Art.cont("box-m", listOf(Triple("Orbital", 0.40f, ""), Triple("Riverland", 0.35f, ""), Triple("The Contender", 0.41f, ""), Triple("Duneward", 0.45f, ""), Triple("The Ledger", 0.40f, ""))),
        sources = listOf("AURA+", "prime video", "Disney+", "NETFLIX", "Apple TV+", "Google TV", "More Options").mapIndexed { i, l -> SourceCard("demo-src-$i", l, highlighted = i == 0) },
    )

    private fun mc(id: String, title: String, artist: String?, asset: String, progress: Float? = null, subtitle: String? = null) = MusicCard("demo-amp-$id", title, artist, a(asset), progress, subtitle)
    private val amp = AmpContent(
        hero = HeroEntry(
            "demo-amp-hero", BrandLabels.AURAAMP, "Velvet Horizon", "A cinematic collection of dream-pop anthems that drift between dusk and distant horizons.", "Album",
            listOf("12 Tracks", "48 min", "Alternative"), a("art_hero_velvet_horizon"), subtitle = "Elara Cove",
            actions = listOf(HeroAction("play", "Play", HeroIcon.PLAY, primary = true), HeroAction("queue", "Open Queue", HeroIcon.QUEUE), HeroAction("album", "View Album", HeroIcon.INFO)),
        ),
        tabs = listOf("For You", "Browse", "Artists", "Albums", "Playlists"),
        shelves = listOf(
            MusicShelf("recent", "Recently Played", listOf(
                mc("r1", "Velvet Horizon", "Elara Cove", "card_amp_velvet_horizon", 0.53f), mc("r2", "Mistline", "North Vale", "card_amp_mistline", 0.30f),
                mc("r3", "Afterglow", "Orion Flux", "card_amp_afterglow", 0.20f), mc("r4", "Tidal Memories", "Cresthaven", "card_amp_tidal_memories"),
                mc("r5", "Solace", "Riven", "card_amp_solace"), mc("r6", "Late Circles", "Vera Holt", "card_amp_late_circles"))),
            MusicShelf("recommended", "Recommended For You", listOf(
                mc("c1", "Dreamfall", null, "card_amp_dreamfall"), mc("c2", "Stratos", null, "card_amp_stratos"), mc("c3", "Ember Waves", null, "card_amp_ember_waves"),
                mc("c4", "Lunar Tide", null, "card_amp_lunar_tide"), mc("c5", "Hollow Lights", null, "card_amp_hollow_lights"), mc("c6", "Northstar", null, "card_amp_northstar"))),
            MusicShelf("followed", "New From Followed Artists", listOf(
                mc("f1", "Glass Garden", "Elara Cove", "card_amp_glass_garden", subtitle = "Single"), mc("f2", "Wide Awake", "Riven", "card_amp_wide_awake", subtitle = "EP"),
                mc("f3", "The Archive (Sessions)", "North Vale", "card_amp_archive_sessions", subtitle = "Album"), mc("f4", "Mirage (Deluxe)", "Cresthaven", "card_amp_mirage_deluxe", subtitle = "Album"),
                mc("f5", "City of Echoes", "Vera Holt", "card_amp_city_of_echoes", subtitle = "EP"))),
        ),
    )

    private fun m(id: String, st: MatchState, badge: String, h: String, aw: String, hs: Int?, a: Int?, footer: String, league: String? = null) = Match("demo-m-$id", st, badge, h, aw, hs, a, footer, league)
    private val sports = SportsContent(
        hero = SportsHero(
            BrandLabels.SPORTS_CENTER, "Northbridge FC vs Valen City", listOf("LIVE", "Premier League", "Matchweek 24", "Northbridge Stadium"), "Northbridge FC", "Valen City", "2 – 1", "67:24   2nd Half",
            listOf(HeroAction("hub", "Match Hub", HeroIcon.INFO), HeroAction("watch", "Watch Live", HeroIcon.PLAY, primary = true)),
        ),
        following = listOf(
            FollowChip("demo-f1", "Northbridge FC", "Live Now", true), FollowChip("demo-f2", "Valen City", "Live Now", true), FollowChip("demo-f3", "Rivermere Lakers", "Tonight, 7:30 PM"),
            FollowChip("demo-f4", "Crestview Hawks", "Final: 112 – 108"), FollowChip("demo-f5", "Pinecrest Patriots", "Sun, 6:00 PM"),
        ),
        groups = listOf(
            MatchGroup("pl", "Premier League", listOf(
                m("pl1", MatchState.LIVE, "LIVE", "NBR", "VAL", 2, 1, "67:24 • 2nd Half"), m("pl2", MatchState.LIVE, "LIVE", "RIV", "ELM", 0, 0, "58:10 • 2nd Half"),
                m("pl3", MatchState.FINAL, "Final", "HBR", "WES", 3, 1, "Full Time"), m("pl4", MatchState.FINAL, "Final", "LND", "BRK", 2, 2, "Full Time"),
                m("pl5", MatchState.UPCOMING, "Sun, 4:30 PM", "MOR", "STH", null, null, "Matchweek 24"))),
            MatchGroup("nba", "NBA", listOf(
                m("nba1", MatchState.LIVE, "LIVE", "RVM", "CRW", 98, 94, "4th • 3:12"), m("nba2", MatchState.FINAL, "Final", "LKS", "PYT", 118, 112, "Full Time"),
                m("nba3", MatchState.FINAL, "Final", "BRK", "ATL", 105, 101, "Full Time"), m("nba4", MatchState.UPCOMING, "Tonight, 8:00 PM", "MRC", "STN", null, null, "Rivermere Arena"),
                m("nba5", MatchState.UPCOMING, "Tomorrow, 7:00 PM", "GLD", "HRT", null, null, "Harbor Dome"))),
            MatchGroup("nfl", "NFL", listOf(
                m("nfl1", MatchState.LIVE, "LIVE", "PCP", "RDK", 17, 10, "3rd • 6:24"), m("nfl2", MatchState.FINAL, "Final", "BLV", "MST", 27, 20, "Full Time"),
                m("nfl3", MatchState.FINAL, "Final", "GRN", "SKY", 24, 17, "Full Time"), m("nfl4", MatchState.UPCOMING, "Sun, 1:00 PM", "NRC", "WLD", null, null, "Metropolitan Field"),
                m("nfl5", MatchState.UPCOMING, "Sun, 4:25 PM", "CST", "HVR", null, null, "Lakeside Stadium"))),
        ),
        upcoming = listOf(
            m("u1", MatchState.UPCOMING, "Sat, 12:30 PM", "NBR", "BRK", null, null, "Sat, 12:30 PM", "Premier League"), m("u2", MatchState.UPCOMING, "Tonight, 7:30 PM", "RVM", "ATL", null, null, "Tonight, 7:30 PM", "NBA"),
            m("u3", MatchState.UPCOMING, "Sun, 4:25 PM", "PCP", "WLD", null, null, "Sun, 4:25 PM", "NFL"), m("u4", MatchState.UPCOMING, "Mon, 3:00 PM", "LND", "VAL", null, null, "Mon, 3:00 PM", "Premier League"),
            m("u5", MatchState.UPCOMING, "Tue, 8:00 PM", "LKS", "CRW", null, null, "Tue, 8:00 PM", "NBA"),
        ),
    )

    private val channels = listOf(
        Channel("ch101", "AURA", "NEWS HD", "News", 101, a("art_channel_news")), Channel("ch102", "AURA", "ENTERTAINMENT", "Entertainment", 102, a("art_channel_entertainment")),
        Channel("ch103", "AURA", "MOVIES HD", "Movies", 103, a("art_channel_movies")), Channel("ch104", "AURA", "SPORTS HD", "Sports", 104, a("art_channel_sports")),
        Channel("ch105", "AURA", "KIDS", "Kids", 105, a("art_channel_kids")), Channel("ch106", "AURA", "MUSIC HD", "Music", 106, a("art_channel_music")),
    )
    // x positions measured on the mockup grid (427..1637 of 1672 px), stored as 0..1 fractions
    private fun f(x: Int) = (x - 427) / 1210f
    private fun p(ch: String, n: Int, t: String, x0: Int, x1: Int, live: Boolean = false) = Program("demo-p-$ch-$n", ch, t, f(x0), f(x1), live)
    private val programs = listOf(
        p("ch101", 1, "Global News Tonight", 427, 924, true), p("ch101", 2, "The World Report", 936, 1287), p("ch101", 3, "Newsroom Live", 1301, 1637),
        p("ch102", 1, "Celebrity Hour", 427, 714), p("ch102", 2, "Behind the Scenes", 724, 1062, true), p("ch102", 3, "The Spotlight", 1072, 1637),
        p("ch103", 1, "The Last Voyage (2023)", 427, 981, true), p("ch103", 2, "Duneward", 1021, 1637),
        p("ch104", 1, "Arena Live: Matchday", 427, 927, true), p("ch104", 2, "Sports Center", 946, 1301), p("ch104", 3, "The Final Score", 1319, 1637),
        p("ch105", 1, "Aura Kids Club", 427, 693), p("ch105", 2, "Adventure Pals", 717, 1064, true), p("ch105", 3, "Galaxy Rangers", 1084, 1637),
        p("ch106", 1, "Live from Aurora: Day 2", 427, 932, true), p("ch106", 2, "Top 20 Countdown", 956, 1300), p("ch106", 3, "Indie Sessions", 1313, 1637),
    )
    private val liveTv = LiveTvContent(
        hero = HeroEntry("demo-live-hero", BrandLabels.LIVE_ON_AURA, "The Aurora Concert", "An exclusive live concert event featuring chart-topping artists, stunning visuals, and a global audience.", "AURA MUSIC HD", listOf("LIVE"), a("art_hero_aurora_concert"),
            subtitle = "LIVE SPECIAL", actions = listOf(HeroAction("details", "More details", HeroIcon.INFO), HeroAction("watch", "Watch Now", HeroIcon.PLAY, primary = true))),
        channels = channels, programs = programs,
        timeLabels = listOf("7:00 PM" to f(444), "7:30 PM" to f(650), "8:00 PM" to f(871), "8:30 PM" to f(1087), "9:00 PM" to f(1302), "9:30 PM" to f(1517)),
        nowFraction = f(801), dateLabel = "Today, Jan 26", filters = listOf("All Channels") + channels.map { it.category },
    )

    private val queue = listOf(
        PlayerTrack("demo-q-1", "Through the Mist", "Elara", 214, a("art_album_through_the_mist")),
        PlayerTrack("demo-q-2", "Afterglow", "Orion Flux", 187, a("card_amp_afterglow")),
        PlayerTrack("demo-q-3", "Tidal Memories", "Cresthaven", 242, a("card_amp_tidal_memories")),
    )

    override suspend fun load() = DemoCatalog(
        DataMode.DEMO, ShelfCatalogs.home, ShelfCatalogs.movies, ShelfCatalogs.tvShows, auraBox, amp, sports, liveTv, queue,
        apps = listOf("Home", "Movies", "TV Shows", "Sports", "Live TV", "AuraBox", "AuraAmp", "Services"),
    )
}
