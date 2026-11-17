package no.aura.os.v2.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.sports.*
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.AuraTypography

/** No demo match or fictional result is ever shown in Real Mode. */
@Composable
fun RealSportsPage(
    results: List<SportFeedState>, selected: Set<Sport>, entry: FocusRequester,
    onPickSport: (Sport) -> Unit, onRefresh: () -> Unit,
    enabled: Boolean = true, onEnabled: ((Boolean) -> Unit)? = null,
    favoriteTeams: Set<String> = emptySet(), selectedLeagues: Set<String> = emptySet(),
    onToggleTeam: ((String) -> Unit)? = null, onToggleLeague: ((String) -> Unit)? = null,

) {
    val uri = LocalUriHandler.current
    LazyColumn(
        Modifier.fillMaxSize().padding(start = 144.dp, top = 132.dp, end = 72.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(bottom = 136.dp),
    ) {
        item { Text("Sports Center", style = AuraTypography.PageTitle) }
        item { Text("Scores from SportScore · updates at most once per minute", style = AuraTypography.SectionTitle) }
        if (onEnabled != null) item {
            SportControl(if (enabled) "Sports: On" else "Sports: Off") { onEnabled(!enabled) }
        }
        if (!enabled) item { Text("Sports are off. Score updates are paused.", style = AuraTypography.Body) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Sport.values().forEachIndexed { index, sport ->
                    val selectedNow = sport in selected
                    Text(
                        (if (selectedNow) "● " else "") + sport.label,
                        style = AuraTypography.Button.copy(color = AuraColors.TextPrimary),
                        modifier = (if (index == 0) Modifier.focusRequester(entry) else Modifier)
                            .auraFocusable(RoundedCornerShape(10.dp), onClick = { onPickSport(sport) })
                            .background(AuraColors.Surface, RoundedCornerShape(10.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
        if (enabled) item {
            Text("Refresh scores", style = AuraTypography.Button,
                modifier = Modifier.auraFocusable(RoundedCornerShape(10.dp), onClick = onRefresh)
                    .background(AuraColors.Surface, RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp))
        }
        (if (enabled) results else emptyList()).forEach { result ->
            when (result) {
                is SportFeedState.Error -> item {
                    Text(result.message, style = AuraTypography.Body, color = AuraColors.TextSecondary)
                }
                is SportFeedState.Ready -> {
                    item { Text(result.sport.label + " · Matches", style = AuraTypography.SectionTitle) }
                    item {
                        Text(if (result.stale) "Cached scores · may be outdated. ${result.message.orEmpty()}"
                            else "Updated " + SportsLogic.localTime(java.time.Instant.ofEpochMilli(result.refreshedAt).toString()),
                            style = AuraTypography.Label, color = AuraColors.TextSecondary)
                    }
                    val leagues = result.matches.mapNotNull { it.league }.distinct()
                    if (leagues.isEmpty()) item {
                        Text("This feed does not identify leagues. League filtering is unavailable for these results.", style = AuraTypography.Label)
                    } else if (onToggleLeague != null) leagues.forEach { league -> item {
                        val key = result.sport.api + ":" + league
                        SportControl((if (key in selectedLeagues) "● " else "") + league) { onToggleLeague(key) }
                    } }
                    val matches = result.matches.filter { leagues.isEmpty() || selectedLeagues.none { it.startsWith(result.sport.api + ":") } ||
                        (result.sport.api + ":" + it.league) in selectedLeagues }
                        .sortedByDescending { SportsLogic.teamKey(result.sport, it.home) in favoriteTeams || SportsLogic.teamKey(result.sport, it.away) in favoriteTeams }
                    if (matches.isEmpty()) item {
                        Text("No matches returned for this sport.", style = AuraTypography.Body)
                    }
                    items(matches, key = { result.sport.api + ":" + it.id }) { match ->
                        Column(
                            Modifier.fillMaxWidth().background(AuraColors.Surface, RoundedCornerShape(10.dp))
                                .padding(horizontal = 23.dp, vertical = 15.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Text(match.home + "  " + (match.homeScore ?: "–") + " : " + (match.awayScore ?: "–") + "  " + match.away,
                                style = AuraTypography.CardTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOf(match.status, SportsLogic.localTime(match.kickoff)).filter { it.isNotBlank() }.joinToString(" · "),
                                style = AuraTypography.Label, color = AuraColors.TextSecondary)
                            if (onToggleTeam != null) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                listOf(match.home, match.away).forEach { team ->
                                    val key = SportsLogic.teamKey(result.sport, team)
                                    SportControl((if (key in favoriteTeams) "★ " else "☆ ") + team) { onToggleTeam(key) }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            SportControl("Powered by SportScore · sportscore.com") { runCatching { uri.openUri("https://sportscore.com/") } }
        }
    }
}

@Composable
private fun SportControl(label: String, onClick: () -> Unit) {
    Text(label, style = AuraTypography.Button, color = AuraColors.Focus,
        modifier = Modifier.auraFocusable(RoundedCornerShape(10.dp), onClick = onClick)
            .background(AuraColors.Surface, RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 12.dp))
}
