package no.aura.os.v2.demo

import no.aura.os.v2.brand.BrandLabels

import no.aura.os.v2.data.*

/** Shared artwork lookup: title -> bundled interim asset (see references/README.md). */
internal object Art {
    fun a(name: String) = Artwork(asset = name)
    val byTitle = mapOf(
        "Orbital" to "card_orbital", "Riverland" to "card_riverland", "The Contender" to "card_contender", "Duneward" to "card_duneward",
        "The Ledger" to "card_ledger", "Faultline" to "card_faultline", "Ember Falls" to "card_ember_falls", "North Star" to "card_north_star",
        "The Last Voyage" to "card_last_voyage", "Hollow Crown" to "card_hollow_crown", "Verdigris" to "card_verdigris",
        "Crimson Tide" to "card_trending_crimson_tide", "Skylight" to "card_trending_skylight", "The Archive" to "card_trending_archive",
        "Mirage" to "card_trending_mirage", "Iron Vale" to "card_trending_iron_vale", "Circuit 9" to "card_trending_circuit_9",
        "The Silver Realm" to "card_silver_realm", "Emerald Plains" to "card_emerald_plains", "The Iron Veil" to "card_iron_veil",
        "Marshlight" to "card_marshlight", "Crown of Ashes" to "card_crown_of_ashes", "The Sentinels" to "card_sentinels",
        "Bloodhollow" to "card_bloodhollow", "Eastwater" to "card_eastwater",
    )
    private val twoLineTitles = setOf("Ember Falls", "North Star", "Hollow Crown", "Crimson Tide", "Crown of Ashes", "The Silver Realm", "Emerald Plains")
    fun item(title: String, group: String): MediaItem =
        MediaItem("demo-$group-" + title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'), title, a(byTitle.getValue(title)), twoLine = title in twoLineTitles)
    fun plain(group: String, titles: List<String>) = titles.map { ContinueItem(item(it, group), 0f, "") }
    fun cont(group: String, rows: List<Triple<String, Float, String>>) = rows.map { (t, p, l) -> ContinueItem(item(t, group), p, l) }
}

internal object ShelfCatalogs {
    private fun shelf(id: String, title: String, items: List<ContinueItem>, cont: Boolean) = Shelf(id, title, items, cont)

    val home = ShelfPageContent(
        hero = listOf(
            HeroEntry("demo-hero-1", BrandLabels.RECOMMENDED, "The Silver Realm", "A young cartographer discovers a hidden realm that could unite—or shatter—two worlds.", "U/A 13+", listOf("Fantasy", "Adventure", "2h 18m"), Art.a("art_hero_silver_realm")),
            HeroEntry("demo-hero-2", BrandLabels.RECOMMENDED, "The Last Voyage", "A crew sets sail on a perilous expedition that will test loyalty, survival, and the limits of human ambition.", "U/A 13+", listOf("Adventure", "2h 18m"), Art.a("art_hero_last_voyage")),
            HeroEntry("demo-hero-3", BrandLabels.RECOMMENDED, "The Northern Crown", "A battle-hardened princess must unite fractured kingdoms as a darker threat rises beyond the Wall.", "U/A 16+", listOf("Fantasy", "Drama", "2 Seasons"), Art.a("art_hero_northern_crown")),
            HeroEntry("demo-hero-4", BrandLabels.RECOMMENDED, "The Aurora Concert", "An exclusive live concert event featuring chart-topping artists, stunning visuals, and a global audience.", "AURA MUSIC HD", listOf("Live Special"), Art.a("art_hero_aurora_concert")),
            HeroEntry("demo-hero-5", BrandLabels.RECOMMENDED, "Velvet Horizon", "A cinematic collection of dream-pop anthems that drift between dusk and distant horizons.", "Album", listOf("12 Tracks", "48 min", "Alternative"), Art.a("art_hero_velvet_horizon")),
        ),
        shelves = listOf(
            shelf("continue", "Continue Watching", Art.cont("home-c", listOf(Triple("Orbital", 0.47f, "42m left"), Triple("Riverland", 0.35f, "18m left"), Triple("The Contender", 0.41f, "36m left"), Triple("Duneward", 0.49f, "1h 5m left"), Triple("The Ledger", 0.41f, "27m left"))), true),
            shelf("latest", "Aura Latest", Art.plain("home-l", listOf("Ember Falls", "North Star", "The Last Voyage", "Hollow Crown", "Faultline", "Verdigris")), false),
            shelf("trending", "Trending on Aura", Art.plain("home-t", listOf("Crimson Tide", "Skylight", "The Archive", "Mirage", "Iron Vale", "Circuit 9")), false),
        ),
    )

    val movies = ShelfPageContent(
        hero = listOf(HeroEntry("demo-movie-hero-1", BrandLabels.RECOMMENDED, "The Last Voyage", "A crew sets sail on a perilous expedition that will test loyalty, survival, and the limits of human ambition.", "U/A 13+", listOf("Adventure", "2h 18m"), Art.a("art_hero_last_voyage"))) +
            home.hero.drop(2).take(4).mapIndexed { i, h -> h.copy(id = "demo-movie-hero-${i + 2}") },
        shelves = listOf(
            shelf("continue", "Continue Watching", Art.cont("mv-c", listOf(Triple("Orbital", 0.47f, "42m left"), Triple("Riverland", 0.35f, "18m left"), Triple("The Contender", 0.41f, "36m left"), Triple("Duneward", 0.49f, "1h 5m left"), Triple("The Ledger", 0.41f, "27m left"))), true),
            shelf("popular", "Popular Movies", Art.plain("mv-p", listOf("The Silver Realm", "Hollow Crown", "Ember Falls", "Iron Vale", "Verdigris", "Circuit 9")), false),
            shelf("new", "New Releases", Art.plain("mv-n", listOf("Duneward", "North Star", "Faultline", "Mirage", "Skylight", "Crimson Tide")), false),
            shelf("awards", "Award Winners", Art.plain("mv-a", listOf("The Archive", "Riverland", "The Contender", "The Last Voyage", "Orbital", "Emerald Plains")), false),
        ),
    )

    val tvShows = ShelfPageContent(
        hero = listOf(HeroEntry("demo-tv-hero-1", BrandLabels.RECOMMENDED, "The Northern Crown", "A battle-hardened princess must unite fractured kingdoms as a darker threat rises beyond the Wall.", "U/A 16+", listOf("Fantasy", "Drama", "2 Seasons"), Art.a("art_hero_northern_crown"))) +
            listOf(home.hero[0], home.hero[1], home.hero[3], home.hero[4]).mapIndexed { i, h -> h.copy(id = "demo-tv-hero-${i + 2}") },
        shelves = listOf(
            shelf("continue", "Continue Watching", Art.cont("tv-c", listOf(Triple("Riverland", 0.35f, "22m left"), Triple("The Contender", 0.41f, "36m left"), Triple("Duneward", 0.49f, "1h 5m left"), Triple("The Ledger", 0.41f, "27m left"), Triple("Faultline", 0.40f, "41m left"))), true),
            shelf("episodes", "New Episodes", Art.plain("tv-e", listOf("The Iron Veil", "Marshlight", "Crown of Ashes", "The Sentinels", "Bloodhollow", "Eastwater")), false),
            shelf("popular", "Popular Series", Art.plain("tv-p", listOf("Orbital", "North Star", "The Archive", "Hollow Crown", "Verdigris", "Circuit 9")), false),
            shelf("because", "Because You Watched The Silver Realm", Art.plain("tv-b", listOf("Ember Falls", "Skylight", "The Last Voyage", "Mirage", "Iron Vale", "Crimson Tide")), false),
        ),
    )
}
