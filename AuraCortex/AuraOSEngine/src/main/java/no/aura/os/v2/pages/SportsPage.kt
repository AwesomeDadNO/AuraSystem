@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package no.aura.os.v2.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.data.*
import no.aura.os.v2.demo.ArtworkImage
import no.aura.os.v2.overlay.DemoDialog
import no.aura.os.v2.shell.*
import no.aura.os.v2.sports.*
import no.aura.os.v2.theme.*

/** Native guide demo. No live API, rights mapping or external playback claim. */
@Composable
fun SportsPage(content: SportsContent, entry: FocusRequester, onDialog: (DemoDialog) -> Unit, modifier: Modifier = Modifier, profileId: String = "demo", onSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val store = remember(profileId) { AuraSportPrefsStore(context, profileId) }
    var prefs by remember(profileId) { mutableStateOf(store.load()) }
    var view by remember(profileId) { mutableStateOf(SportView.FOR_YOU) }
    val focusScope=rememberCoroutineScope()
    fun toggleWidget(match: SportFixture) {
        prefs = prefs.copy(widgetFixtureIds = if(match.id in prefs.widgetFixtureIds) prefs.widgetFixtureIds-match.id else prefs.widgetFixtureIds+match.id)
        store.save(prefs)
        if(view==SportView.WIDGETS) focusScope.launch { withFrameNanos{}; runCatching { entry.requestFocus() } }
    }
    var league by remember(profileId) { mutableStateOf(prefs.leagues.firstOrNull()) }
    var newsIndex by remember(profileId) { mutableStateOf(0) }
    val newsLeagues = prefs.news.toList()
    LaunchedEffect(newsLeagues) { while(newsLeagues.size > 1) { kotlinx.coroutines.delay(12000); newsIndex = (newsIndex + 1) % newsLeagues.size } }
    val headline = when(newsLeagues.getOrNull(newsIndex % newsLeagues.size.coerceAtLeast(1))) {
        SportLeague.PL -> "Klart for en stor fotballhelg"
        SportLeague.ELITESERIEN -> "Norsk fotball gjør seg klar"
        SportLeague.UCL -> "Europa venter på neste oppgjør"
        null -> "Din personlige kampoversikt"
    }
    val leagueEntry = remember { FocusRequester() }
    val gridEntry = remember { FocusRequester() }
    val matches = AuraSportDemo.matches(prefs, league, view)
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(450.dp)) {
            ArtworkImage(Artwork(asset = "art_hero_sports"))
            Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(AuraColors.Background.copy(alpha=.94f), Color.Transparent))))
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to AuraColors.Background)))
        }
        Column(Modifier.fillMaxSize().padding(start=137.dp, top=94.dp, end=40.dp, bottom=24.dp)) {
            Text("AURA SPORTS CENTER", style=AuraTypography.SectionTitle, color=AuraColors.BrandOrange)
            no.aura.os.v2.hero.HeroTitleText(headline,width=1100.dp,height=56.dp,maxFont=48,minFont=20,modifier=Modifier.padding(top=18.dp))
            Text(if(prefs.news.isEmpty()) "Nyheter er slått av i AuraSport." else "Lagene gjør sine siste forberedelser.\nHer er historiene før helgens kamper.", style=AuraTypography.Body.copy(fontSize=24.sp), modifier=Modifier.padding(top=12.dp))
            Text("DESIGNDEMO · Illustrerende nyhet · Ingen artikkelkilde tilkoblet", color=AuraColors.TextSecondary, modifier=Modifier.padding(top=16.dp))
            Spacer(Modifier.height(24.dp))
            Text("Favorittlag: " + prefs.favoriteTeams.joinToString().ifEmpty { "Alle lag · velg i Tilpass" }, color=AuraColors.TextPrimary)
            Spacer(Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(28.dp), verticalAlignment=Alignment.CenterVertically) {
                SportView.entries.forEachIndexed { i, item ->
                    SportLine(item.title, view==item, Modifier.then(if(i==0) Modifier.focusRequester(entry) else Modifier).focusProperties { down=if(view == SportView.WIDGETS && matches.isNotEmpty()) gridEntry else if(prefs.leagues.isEmpty()) FocusRequester.Cancel else leagueEntry }) { view=item }
                }
                Spacer(Modifier.weight(1f))
                SportLine("⚙ Tilpass", false) { onSettings() }
            }
            Row(Modifier.fillMaxWidth().padding(top=8.dp), horizontalArrangement=Arrangement.spacedBy(28.dp), verticalAlignment=Alignment.CenterVertically) {
                prefs.leagues.forEachIndexed { i, item -> SportLine(item.title, league==item, Modifier.then(if(i==0) Modifier.focusRequester(leagueEntry) else Modifier).focusProperties { up=entry; down=if(matches.isEmpty()) FocusRequester.Cancel else gridEntry }) { league=item } }
                Spacer(Modifier.weight(1f))
                Text("Demoøyeblikk · Ingen live-oppdatering", color=AuraColors.TextSecondary, fontSize=16.sp)
            }
            Text(if(view == SportView.WIDGETS) "Mine kampwidgeter · ${matches.size}" else (league?.title ?: "Ingen liga valgt") + " · " + view.title, style=AuraTypography.SectionTitle, modifier=Modifier.padding(vertical=16.dp))
            if(matches.isEmpty()) Text(if(view == SportView.WIDGETS) "Ingen widgeter valgt. Velg en kamp for å feste den, eller åpne Tilpass." else "Ingen kamper her. Velg en annen visning, liga eller favorittlag i Tilpass.", color=AuraColors.TextSecondary)
            LazyVerticalGrid(columns=GridCells.Fixed(3), modifier=Modifier.weight(1f), horizontalArrangement=Arrangement.spacedBy(16.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
                itemsIndexed(matches, key={ _, match -> match.id }) { i, match ->
                    Column(Modifier.height(160.dp).then(if(i==0) Modifier.focusRequester(gridEntry) else Modifier).focusProperties { if(i<3) up=if(view == SportView.WIDGETS && prefs.leagues.isEmpty()) entry else leagueEntry }
                        .auraFocusable(RoundedCornerShape(12.dp), scale=1f, elevation=0.dp, onClick = { toggleWidget(match) })
                        .panel(RoundedCornerShape(12.dp), AuraColors.Surface.copy(alpha=.8f)).padding(18.dp), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.SpaceBetween) {
                        Text(if(prefs.spoilersHidden && match.score!=null) "DEMO · Resultat skjult" else match.status, color=AuraColors.Focus)
                        Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                            Text(match.home, modifier=Modifier.weight(1f), style=AuraTypography.CardTitle, maxLines=2)
                            Text(match.result(prefs), modifier=Modifier.padding(horizontal=12.dp), fontSize=if(prefs.spoilersHidden) 18.sp else 28.sp, color=AuraColors.TextPrimary)
                            Text(match.away, modifier=Modifier.weight(1f), style=AuraTypography.CardTitle, maxLines=2)
                        }
                        Text(if(match.id in prefs.widgetFixtureIds) "✓ Widget valgt · OK for å fjerne" else "OK for å feste kampwidget", color=AuraColors.TextPrimary, fontSize=16.sp)
                    }
                }
            }
            Text("DESIGNDEMO · Fiktive kamper og resultater · Tilbyder ikke bekreftet · Valgte widgeter vises på Home og i AuraSport", color=AuraColors.TextSecondary, fontSize=14.sp, modifier=Modifier.padding(top=12.dp))
        }
    }
}

@Composable
private fun SportLine(label:String, selected:Boolean, modifier:Modifier=Modifier, onClick:()->Unit) {
    val interaction=rememberNavigationInteraction()
    Text(label, fontSize=24.sp, color=AuraColors.TextPrimary,
        modifier=modifier.auraNavigationFocusable(interaction, selected, onClick = onClick).padding(horizontal=10.dp, vertical=14.dp))
}
