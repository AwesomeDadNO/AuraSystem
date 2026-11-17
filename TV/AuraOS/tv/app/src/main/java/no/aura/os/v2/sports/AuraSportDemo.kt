package no.aura.os.v2.sports

enum class SportLeague(val title: String) { PL("Premier League"), ELITESERIEN("Eliteserien"), UCL("Champions League") }
enum class SportView(val title: String) { FOR_YOU("For deg"), LIVE("Live"), UPCOMING("Kommende"), RESULTS("Resultater"), WIDGETS("Mine widgeter") }
data class AuraSportPrefs(val leagues: Set<SportLeague> = SportLeague.entries.toSet(), val news: Set<SportLeague> = setOf(SportLeague.PL), val spoilersHidden: Boolean = false, val favoriteTeams: Set<String> = emptySet(), val widgetFixtureIds: Set<String> = emptySet())
data class SportFixture(val id: String, val league: SportLeague, val home: String, val away: String, val score: String?, val status: String, val view: SportView) {
    fun result(prefs: AuraSportPrefs) = if (prefs.spoilersHidden && score != null) "Resultat skjult" else score ?: "vs"
}
object AuraSportDemo {
    val fixtures = SportLeague.entries.flatMap { league ->
        val teams = when(league) {
            SportLeague.PL -> listOf("Arsenal", "Liverpool", "Manchester City", "Chelsea", "Tottenham", "Newcastle", "Manchester United", "Everton", "Brighton", "Aston Villa", "Brentford", "Fulham")
            SportLeague.ELITESERIEN -> listOf("Brann", "Rosenborg", "Viking", "Molde", "Bodø/Glimt", "Tromsø", "Fredrikstad", "Sarpsborg 08", "Sandefjord", "HamKam", "KFUM", "Vålerenga")
            SportLeague.UCL -> listOf("Barcelona", "Inter", "Real Madrid", "Bayern", "PSG", "Dortmund", "Benfica", "Ajax", "Sporting", "Porto", "Juventus", "Atalanta")
        }
        (0..5).map { i -> SportFixture("${league.name}-$i", league, teams[i*2], teams[i*2+1], if(i<3) listOf("1 – 0", "2 – 1", "0 – 0")[i] else if(i==5) "2 – 0" else null,
            listOf("DEMO LIVE · 74′", "DEMO LIVE · 62′", "DEMO LIVE · 38′", "I morgen · 16:00", "I morgen · 18:30", "Ferdig · Demo")[i],
            if(i<3) SportView.LIVE else if(i==5) SportView.RESULTS else SportView.UPCOMING) }
    }
    val teams: List<String> = fixtures.flatMap { listOf(it.home, it.away) }.distinct()
    fun widgets(prefs: AuraSportPrefs) = fixtures.filter { it.id in prefs.widgetFixtureIds }
    fun matches(prefs: AuraSportPrefs, league: SportLeague?, view: SportView) = if (view == SportView.WIDGETS) widgets(prefs) else fixtures.filter { it.league in prefs.leagues && it.league == league && (view == SportView.FOR_YOU || it.view == view) && (view != SportView.FOR_YOU || prefs.favoriteTeams.isEmpty() || it.home in prefs.favoriteTeams || it.away in prefs.favoriteTeams) }
}
