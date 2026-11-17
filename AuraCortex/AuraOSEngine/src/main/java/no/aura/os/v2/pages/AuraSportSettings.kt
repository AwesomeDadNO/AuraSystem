package no.aura.os.v2.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.aura.os.v2.shell.*
import no.aura.os.v2.sports.*
import no.aura.os.v2.theme.AuraColors

@Composable
internal fun AuraSportSettings(profileId:String, entry:FocusRequester, back:FocusRequester) {
    val context=LocalContext.current
    val store=remember(profileId) { AuraSportPrefsStore(context, profileId) }
    var prefs by remember(profileId) { mutableStateOf(store.load()) }
    fun save(next:AuraSportPrefs) { prefs=next; store.save(next) }
    LazyColumn(Modifier.fillMaxHeight(), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Text("AuraSport · Norge · Demodata", color=AuraColors.Focus, fontSize=24.sp) }
        item { Text("Velg ligaer i kampoversikten", color=AuraColors.TextPrimary, fontSize=22.sp) }
        SportLeague.entries.forEachIndexed { i, league -> item {
            SportSetting(league.title, league in prefs.leagues, Modifier.then(if(i==0) Modifier.focusRequester(entry) else Modifier).focusProperties { left=back }) {
                save(prefs.copy(leagues=if(league in prefs.leagues) prefs.leagues-league else prefs.leagues+league))
            }
        } }
        item { Text("Nyheter i hero · uavhengig av kampoversikten", color=AuraColors.TextPrimary, fontSize=22.sp) }
        SportLeague.entries.forEach { league -> item {
            SportSetting(league.title, league in prefs.news, Modifier.focusProperties { left=back }) {
                save(prefs.copy(news=if(league in prefs.news) prefs.news-league else prefs.news+league))
            }
        } }
        item { SportSetting("Skjul resultater (spoilerfri)", prefs.spoilersHidden, Modifier.focusProperties { left=back }) { save(prefs.copy(spoilersHidden=!prefs.spoilersHidden)) } }
        item { Text("Favorittlag · For deg viser lagenes demokamper", color=AuraColors.TextPrimary, fontSize=22.sp) }
        SportLeague.entries.forEach { league ->
            item { Text(league.title, color=AuraColors.TextSecondary, fontSize=18.sp) }
            AuraSportDemo.fixtures.filter { it.league == league }.flatMap { listOf(it.home, it.away) }.distinct().forEach { team -> item {
                SportSetting(team, team in prefs.favoriteTeams, Modifier.focusProperties { left=back }) {
                    save(prefs.copy(favoriteTeams=if(team in prefs.favoriteTeams) prefs.favoriteTeams-team else prefs.favoriteTeams+team))
                }
            } }
        }
        item { Text("Kampwidgeter · Home og AuraSport", color=AuraColors.TextPrimary, fontSize=22.sp) }
        AuraSportDemo.fixtures.forEach { match -> item {
            SportSetting("${match.home} – ${match.away} · ${match.league.title}", match.id in prefs.widgetFixtureIds, Modifier.focusProperties { left=back }) {
                save(prefs.copy(widgetFixtureIds=if(match.id in prefs.widgetFixtureIds) prefs.widgetFixtureIds-match.id else prefs.widgetFixtureIds+match.id))
            }
        } }
        item { Text("Nyhetskilder, live-API og kampvarsler kobles til senere. Ingen bakgrunnsvarsler eller nye systemrettigheter aktiveres her.", color=AuraColors.TextSecondary, fontSize=18.sp) }
    }
}
@Composable
private fun SportSetting(label:String, checked:Boolean, modifier:Modifier, action:()->Unit) {
    val interaction=rememberNavigationInteraction()
    Text((if(checked) "✓  " else "○  ")+label, color=AuraColors.TextPrimary, fontSize=22.sp,
        modifier=modifier.fillMaxWidth().auraNavigationFocusable(interaction, checked, onClick=action).padding(16.dp))
}
