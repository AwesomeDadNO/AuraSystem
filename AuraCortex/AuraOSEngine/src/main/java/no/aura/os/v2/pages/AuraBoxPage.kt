@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package no.aura.os.v2.pages

import no.aura.os.v2.brand.AuraBrandLabel
import no.aura.os.v2.brand.BrandLabels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import no.aura.os.v2.cards.ContinueCard
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.cards.splitPrefix
import no.aura.os.v2.data.*
import no.aura.os.v2.demo.ArtworkImage
import no.aura.os.v2.focus.leftToRail
import no.aura.os.v2.focus.verticalLinks
import no.aura.os.v2.hero.HeroActionButton
import no.aura.os.v2.hero.smallCapsTitle
import no.aura.os.v2.overlay.DemoDialog
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.AuraTypography

@Stable class AuraBoxUiState { var inWatchlist by mutableStateOf(false) }

private val BoxStart = 152.dp

/** AuraBox title detail (The Silver Realm mockup): poster + hero, actions, availability, cast, more like this, source cards. Demo only. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AuraBoxPage(content: AuraBoxContent, ui: AuraBoxUiState, entry: FocusRequester, onDialog: (DemoDialog) -> Unit, modifier: Modifier = Modifier) {
    val hero = content.hero
    val (prefix, mainTitle) = splitPrefix(hero.title)
    val castReq = remember { FocusRequester() }; val moreReq = remember { FocusRequester() }; val srcReq = remember { FocusRequester() }
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(540.dp)) {
            ArtworkImage(hero.artwork)
            Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to AuraColors.Background.copy(alpha = 0.75f), 0.55f to Color.Transparent)))
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to AuraColors.Background)))
        }
        // breadcrumb
        Row(Modifier.padding(start = BoxStart, top = 100.dp), verticalAlignment = Alignment.CenterVertically) {
            AuraBrandLabel(BrandLabels.AURABOX)
            Box(Modifier.padding(horizontal = 14.dp).width(1.dp).height(18.dp).background(AuraColors.TextMuted))
            Text("Watch", style = AuraTypography.Metadata.copy(fontSize = 20.sp))
        }
        // poster
        Box(Modifier.padding(start = BoxStart, top = 163.dp).size(228.dp, 358.dp).panel(RoundedCornerShape(12.dp))) { ArtworkImage(content.poster) }
        Column(Modifier.padding(start = 414.dp, top = 163.dp)) {
            Box(Modifier.height(30.dp).padding(start = 36.dp)) { prefix?.let { Text(it.uppercase(), style = AuraTypography.HeroTitlePrefix.copy(fontSize = 24.sp)) } }
            Text(smallCapsTitle(mainTitle), style = AuraTypography.HeroTitle.copy(fontSize = 72.sp, lineHeight = 74.sp), maxLines = 1, modifier = Modifier.offset(y = (-4).dp))
            Spacer(Modifier.height(18.dp))
            Text(hero.description, style = AuraTypography.Body.copy(color = AuraColors.TextPrimary.copy(alpha = 0.92f)), maxLines = 2, modifier = Modifier.width(520.dp))
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(hero.ratingLabel, style = AuraTypography.Label.copy(color = AuraColors.TextPrimary), modifier = Modifier.border(1.dp, AuraColors.TextPrimary.copy(alpha = 0.7f), RoundedCornerShape(6.dp)).padding(horizontal = 9.dp, vertical = 2.dp))
                Box(Modifier.padding(horizontal = 18.dp).width(1.dp).height(18.dp).background(AuraColors.TextMuted))
                Text(hero.metadata.joinToString("   •   "), style = AuraTypography.Metadata)
            }
            Spacer(Modifier.height(30.dp))
            Row(Modifier.verticalLinks(down = castReq), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                hero.actions.forEachIndexed { i, a ->
                    val shown = if (a.id == "watchlist" && ui.inWatchlist) HeroAction(a.id, "In Watchlist", HeroIcon.CHECK) else a
                    Box {
                        HeroActionButton(shown, Modifier.leftToRail(i == 0).then(if (a.primary) Modifier.focusRequester(entry) else Modifier)) {
                            when (a.id) {
                                "watchlist" -> ui.inWatchlist = !ui.inWatchlist
                                "play" -> onDialog(heroDialog(hero.title, "Play")); "resume" -> onDialog(DemoDialog("Simulated playback", listOf("Resume \"${hero.title}\" at 55% (demo).", "No stream is opened.")))
                                "trailer" -> onDialog(DemoDialog("Trailer (demo)", listOf("A trailer for \"${hero.title}\" would play here.")))
                                else -> onDialog(DemoDialog("Open in App (demo)", listOf("Opening a third-party app is not part of the demo.", "No availability is claimed.")))
                            }
                        }
                        if (a.id == "resume") Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 7.dp).width(130.dp).height(3.dp).drawBehind {
                            drawRect(Color.White.copy(alpha = 0.3f)); drawRect(AuraColors.Progress, Offset.Zero, Size(size.width * content.resumeProgress, size.height))
                        })
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Available on", style = AuraTypography.Metadata.copy(fontSize = 18.sp))
                content.availableOn.forEachIndexed { i, s ->
                    Text(s, style = AuraTypography.Label.copy(color = if (i == 0) AuraColors.AccentBlue else AuraColors.TextPrimary, fontSize = 16.sp), maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).border(1.dp, if (i == 0) AuraColors.AccentBlue else AuraColors.Border, RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 8.dp))
                }
                DemoTag("Demo · not real availability")
            }
        }
        // lower rows: cast / more like this / available on
        Column(Modifier.padding(top = 553.dp)) {
            RowTitle("Cast & Crew")
            LazyRow(Modifier.verticalLinks(up = entry, down = moreReq).focusRequester(castReq).focusRestorer(), contentPadding = PaddingValues(start = BoxStart, end = 40.dp, top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                itemsIndexed(content.cast, key = { _, c -> c.id }) { i, c -> CastCard(c, Modifier.leftToRail(i == 0)) { onDialog(DemoDialog("${c.name} (demo)", listOf(c.role.removePrefix("as ").let { "Plays $it in \"The Silver Realm\"." }, "Fictional cast for the visual demo."))) } }
                item(key = "full-cast") { FullCastCard { onDialog(DemoDialog("Full cast & crew (demo)", content.cast.map { "${it.name} ${it.role}" })) } }
            }
            Spacer(Modifier.height(10.dp)); RowTitle("More Like This")
            LazyRow(Modifier.verticalLinks(up = castReq, down = srcReq).focusRequester(moreReq).focusRestorer(), contentPadding = PaddingValues(start = BoxStart, end = 40.dp, top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(content.moreLikeThis, key = { _, c -> c.item.id }) { i, c -> ContinueCard(c, Modifier.leftToRail(i == 0), DpSize(333.dp, 125.dp)) { onDialog(DemoDialog.simple("Open \"${c.item.title}\"")) } }
            }
            Spacer(Modifier.height(10.dp)); RowTitle("Available On")
            LazyRow(Modifier.verticalLinks(up = moreReq, down = FocusRequester.Cancel).focusRequester(srcReq).focusRestorer(), contentPadding = PaddingValues(start = BoxStart, end = 40.dp, top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(content.sources, key = { _, s -> s.id }) { i, s -> SourceCardView(s, Modifier.leftToRail(i == 0)) { onDialog(DemoDialog("Source (demo)", listOf("${s.label} — illustrative source card.", "No third-party service is contacted and no availability is claimed."))) } }
            }
        }
    }
}

@Composable private fun RowTitle(t: String) { Text(t, style = AuraTypography.SectionTitle, modifier = Modifier.padding(start = BoxStart).height(30.dp)) }

@Composable
private fun CastCard(c: CastMember, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(modifier.size(285.dp, 83.dp).auraFocusable(shape, onClick = onClick).panel(shape), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(133.dp, 83.dp)) { ArtworkImage(c.artwork) }
        Column(Modifier.padding(start = 20.dp)) { Text(c.name, style = AuraTypography.CardTitle.copy(fontSize = 20.sp), maxLines = 1); Text(c.role, style = AuraTypography.Label.copy(fontSize = 16.sp), maxLines = 1) }
    }
}

@Composable
private fun FullCastCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.size(210.dp, 83.dp).auraFocusable(shape, onClick = onClick).panel(shape), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("• • •", style = AuraTypography.CardTitle.copy(letterSpacing = 4.sp)); Text("Full Cast & Crew", style = AuraTypography.Label.copy(fontSize = 16.sp, color = AuraColors.TextPrimary))
    }
}

/** Text-only source card. Brand logos are NOT reproduced (licensing); real logo assets are requested in references/README.md. */
@Composable
private fun SourceCardView(s: SourceCard, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.size(243.dp, 93.dp).auraFocusable(shape, onClick = onClick).clip(shape).background(AuraColors.Surface)
        .border(if (s.highlighted) 2.dp else 1.dp, if (s.highlighted) AuraColors.Focus else AuraColors.Border.copy(alpha = 0.75f), shape), contentAlignment = Alignment.Center) {
        Text(s.label, style = AuraTypography.SectionTitle.copy(fontSize = 26.sp, color = if (s.highlighted) AuraColors.AccentBlue else AuraColors.TextPrimary, letterSpacing = if (s.highlighted) 0.12.em else 0.sp), textAlign = TextAlign.Center, maxLines = 1)
    }
}
