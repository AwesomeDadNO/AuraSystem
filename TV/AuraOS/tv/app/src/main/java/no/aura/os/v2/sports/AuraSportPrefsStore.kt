package no.aura.os.v2.sports

import android.content.Context

class AuraSportPrefsStore(context: Context, profileId: String) {
    private val prefs = context.getSharedPreferences("aurasport_$profileId", Context.MODE_PRIVATE)
    fun load(): AuraSportPrefs {
        fun selection(key: String, default: Set<SportLeague>): Set<SportLeague> = prefs.getStringSet(key, null)?.let { names -> SportLeague.entries.filter { it.name in names }.toSet() } ?: default
        return AuraSportPrefs(selection("leagues", SportLeague.entries.toSet()), selection("news", setOf(SportLeague.PL)), prefs.getBoolean("spoilers", false),
            prefs.getStringSet("favorite_teams", emptySet())?.toSet().orEmpty().intersect(AuraSportDemo.teams.toSet()),
            prefs.getStringSet("widget_fixtures", emptySet())?.toSet().orEmpty().intersect(AuraSportDemo.fixtures.map { it.id }.toSet()))
    }
    fun save(value: AuraSportPrefs) { prefs.edit().putStringSet("leagues", value.leagues.map { it.name }.toSet()).putStringSet("news", value.news.map { it.name }.toSet()).putBoolean("spoilers", value.spoilersHidden).putStringSet("favorite_teams", value.favoriteTeams.toSet()).putStringSet("widget_fixtures", value.widgetFixtureIds.toSet()).apply() }
}
