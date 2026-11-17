@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package no.aura.os.v2.pages

import no.aura.os.v2.brand.AuraBrandLabel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.data.*
import no.aura.os.v2.demo.ArtworkImage
import no.aura.os.v2.focus.leftToRail
import no.aura.os.v2.focus.verticalLinks
import no.aura.os.v2.hero.HeroActionButton
import no.aura.os.v2.hero.smallCapsTitle
import no.aura.os.v2.cards.splitPrefix
import no.aura.os.v2.overlay.DemoDialog
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.AuraTypography

/** Pure EPG helpers (unit-tested). Guide positions are fractions of the time grid on a fixed demo timeline. */
object Epg {
    fun visibleChannels(all: List<Channel>, filter: String): List<Channel> = if (filter == "All Channels") all else all.filter { it.category == filter }
    fun rows(programs: List<Program>, channels: List<Channel>): List<List<Program>> = channels.map { c -> programs.filter { it.channelId == c.id }.sortedBy { it.start } }
    /** Program on [channelId] that is on air at [now], or null. */
    fun onAir(programs: List<Program>, channelId: String, now: Float): Program? = programs.firstOrNull { it.channelId == channelId && now >= it.start && now < it.end }
    /** Next filter in the cycle (wraps). */
    fun nextFilter(filters: List<String>, current: String): String = filters[(filters.indexOf(current) + 1).mod(filters.size)]
    /** Nearest block in [row] (list of one channel's programs) to a block of the adjacent row, by block centre. Used for deterministic up/down. */
    fun nearestIndex(row: List<Program>, center: Float): Int = row.indices.minByOrNull { kotlin.math.abs((row[it].start + row[it].end) / 2f - center) } ?: 0
}

@Stable class LiveTvUiState { var selectedChannel by mutableStateOf("ch101"); var filter by mutableStateOf("All Channels") }

private val GridStart = 490.dp
private val GridWidth = 1389.dp

/** Live TV (Aurora Concert mockup): hero, channel cards, electronic program guide on a fixed demo timeline. No IPTV, no real EPG. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LiveTvPage(content: LiveTvContent, ui: LiveTvUiState, entry: FocusRequester, onDialog: (DemoDialog) -> Unit, modifier: Modifier = Modifier) {
    val h = content.hero
    val channelsReq = remember { FocusRequester() }; val filterReq = remember { FocusRequester() }
    val channels = Epg.visibleChannels(content.channels, ui.filter)
    val rows = Epg.rows(content.programs, channels)
    val rowReqs = remember(rows.size) { List(rows.size) { FocusRequester() } }
    val (prefix, main) = splitPrefix(h.title)
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(470.dp)) {
            ArtworkImage(h.artwork)
            Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to AuraColors.Background.copy(alpha = 0.72f), 0.5f to Color.Transparent)))
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to AuraColors.Background)))
        }
        Column(Modifier.padding(start = 150.dp, top = 88.dp)) {
            AuraBrandLabel(h.sectionLabel)
            Box(Modifier.height(28.dp).padding(start = 40.dp)) { prefix?.let { Text(it.uppercase(), style = AuraTypography.HeroTitlePrefix.copy(fontSize = 24.sp)) } }
            Text(smallCapsTitle(main), style = AuraTypography.HeroTitle.copy(fontSize = 66.sp, lineHeight = 66.sp), maxLines = 1, modifier = Modifier.offset(y = (-6).dp))
            Text(h.subtitle ?: "", style = AuraTypography.HeroTitlePrefix.copy(fontSize = 19.sp, letterSpacing = 0.5.em), modifier = Modifier.padding(start = 46.dp))
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(buildAnnotatedString { append("AURA "); withStyle(SpanStyle(color = AuraColors.AccentBlue)) { append("MUSIC") }; append(" HD") }, style = AuraTypography.Label.copy(color = AuraColors.TextPrimary, fontSize = 19.sp),
                    modifier = Modifier.border(1.dp, AuraColors.TextPrimary.copy(alpha = 0.7f), RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 5.dp))
                Box(Modifier.padding(horizontal = 16.dp).width(1.dp).height(18.dp).background(AuraColors.TextMuted)); LiveMarker("LIVE", 19)
            }
            Spacer(Modifier.height(12.dp))
            Text(h.description, style = AuraTypography.Body.copy(fontSize = 20.sp, lineHeight = 26.sp), maxLines = 2, modifier = Modifier.width(520.dp))
            Spacer(Modifier.height(14.dp))
            Row(Modifier.verticalLinks(down = channelsReq), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                h.actions.forEachIndexed { i, a -> HeroActionButton(a, Modifier.leftToRail(i == 0).then(if (a.primary) Modifier.focusRequester(entry) else Modifier)) { onDialog(heroDialog(h.title, a.label)) } }
            }
        }
        Column(Modifier.padding(top = 448.dp)) {
            Text("Live TV Channels", style = AuraTypography.SectionTitle, modifier = Modifier.padding(start = 145.dp).height(30.dp))
            LazyRow(Modifier.verticalLinks(up = entry, down = filterReq).focusRequester(channelsReq).focusRestorer(), contentPadding = PaddingValues(start = 145.dp, end = 40.dp, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(content.channels, key = { _, c -> c.id }) { i, c ->
                    ChannelCard(c, selected = c.id == ui.selectedChannel, Modifier.leftToRail(i == 0)) {
                        ui.selectedChannel = c.id
                        val now = Epg.onAir(content.programs, c.id, content.nowFraction)
                        onDialog(DemoDialog("Channel (demo)", listOf("${c.brand} ${c.name} · Ch ${c.number}", "On now: ${now?.title ?: "—"}", "Simulated tune. No IPTV or live stream is used.")))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 145.dp, end = 28.dp, top = 6.dp).height(46.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("TV Guide", style = AuraTypography.SectionTitle.copy(fontSize = 26.sp)); Spacer(Modifier.weight(1f))
                val shape = RoundedCornerShape(10.dp)
                Row(Modifier.focusRequester(filterReq).verticalLinks(up = channelsReq, down = rowReqs.firstOrNull() ?: FocusRequester.Cancel).auraFocusable(shape, scale = 1.03f, elevation = 4.dp) { ui.filter = Epg.nextFilter(content.filters, ui.filter) }
                    .panel(shape, AuraColors.SurfaceElevated.copy(alpha = 0.8f)).padding(horizontal = 18.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ui.filter, style = AuraTypography.Nav.copy(fontSize = 18.sp)); Icon(Icons.Rounded.ArrowDropDown, null, tint = AuraColors.TextPrimary, modifier = Modifier.size(22.dp))
                }
            }
            Guide(content, channels, rows, rowReqs, filterReq, onDialog)
        }
    }
}

@Composable
private fun ChannelCard(c: Channel, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.size(284.dp, 113.dp).auraFocusable(shape, onClick = onClick).clip(shape).background(AuraColors.Surface)
        .border(if (selected) 2.dp else 1.dp, if (selected) AuraColors.Focus else AuraColors.Border.copy(alpha = 0.75f), shape)) {
        ArtworkImage(c.artwork)
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.7f to Color.Transparent)))
        Column(Modifier.align(Alignment.CenterStart).padding(start = 24.dp)) {
            Text(c.brand, style = AuraTypography.Nav.copy(fontSize = 22.sp, letterSpacing = 0.06.em))
            Text(c.name, style = AuraTypography.Nav.copy(fontSize = 22.sp, letterSpacing = 0.06.em, color = if (c.name.endsWith("HD")) AuraColors.AccentBlue else AuraColors.TextPrimary.copy(alpha = 0.9f)), maxLines = 1)
        }
    }
}

@Composable
private fun Guide(content: LiveTvContent, channels: List<Channel>, rows: List<List<Program>>, rowReqs: List<FocusRequester>, filterReq: FocusRequester, onDialog: (DemoDialog) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.padding(start = 145.dp, end = 41.dp, top = 8.dp).fillMaxWidth().height(371.dp).panel(shape, AuraColors.Background.copy(alpha = 0.6f))) {
        Column {
            // time header
            Row(Modifier.fillMaxWidth().height(45.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.width(345.dp).padding(start = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.GridView, null, tint = AuraColors.TextPrimary, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(16.dp))
                    Text(content.dateLabel, style = AuraTypography.Nav.copy(fontSize = 18.sp)); Icon(Icons.Rounded.ArrowDropDown, null, tint = AuraColors.TextPrimary, modifier = Modifier.size(20.dp))
                }
                Box(Modifier.width(GridWidth).fillMaxHeight()) {
                    content.timeLabels.forEach { (t, f) -> Text(t, style = AuraTypography.Metadata.copy(fontSize = 17.sp, color = AuraColors.TextPrimary), modifier = Modifier.offset(x = GridWidth * f).align(Alignment.CenterStart)) }
                    Text("Now", style = AuraTypography.Label.copy(color = AuraColors.OnPrimaryAction, fontSize = 15.sp), modifier = Modifier.offset(x = GridWidth * content.nowFraction - 24.dp).align(Alignment.CenterStart).clip(RoundedCornerShape(14.dp)).background(AuraColors.AccentBlue).padding(horizontal = 14.dp, vertical = 3.dp))
                }
            }
            channels.forEachIndexed { r, ch ->
                Row(Modifier.fillMaxWidth().height(53.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.width(345.dp).padding(start = 22.dp, end = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(150.dp)) { Text(ch.brand, style = AuraTypography.Label.copy(fontSize = 13.sp, color = AuraColors.TextPrimary), maxLines = 1); Text(ch.name, style = AuraTypography.Label.copy(fontSize = 15.sp, color = AuraColors.AccentBlue), maxLines = 1) }
                        Text(ch.category, style = AuraTypography.Label.copy(fontSize = 16.sp, color = AuraColors.TextPrimary), modifier = Modifier.weight(1f), maxLines = 1)
                        Text("${ch.number}", style = AuraTypography.Label.copy(fontSize = 16.sp))
                    }
                    Row(Modifier.width(GridWidth).verticalLinks(up = if (r == 0) filterReq else rowReqs[r - 1], down = rowReqs.getOrNull(r + 1) ?: FocusRequester.Cancel).focusRequester(rowReqs[r]).focusRestorer()) {
                        var cursor = 0f
                        rows[r].forEachIndexed { i, p ->
                            Spacer(Modifier.width(GridWidth * (p.start - cursor).coerceAtLeast(0f)))
                            ProgramBlock(p, GridWidth * (p.end - p.start) - 6.dp, Modifier.leftToRail(i == 0)) {
                                onDialog(DemoDialog("Program (demo)", listOf(p.title, "${ch.brand} ${ch.name} · Ch ${ch.number}", if (p.live) "Live now (fixed demo timeline)" else "Later tonight (fixed demo timeline)", "Illustrative guide data. No real EPG or stream.")))
                            }
                            cursor = p.end
                        }
                    }
                }
            }
        }
        // current-time indicator through all rows
        Box(Modifier.offset(x = 345.dp + GridWidth * content.nowFraction).width(2.dp).fillMaxHeight().padding(top = 36.dp).background(AuraColors.AccentBlue))
    }
}

@Composable
private fun ProgramBlock(p: Program, width: Dp, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.padding(end = 6.dp).width(width).height(44.dp).auraFocusable(shape, scale = 1.02f, elevation = 4.dp, onClick = onClick).clip(shape)
            .background(if (p.live) AuraColors.AccentBlue.copy(alpha = 0.16f) else AuraColors.Surface.copy(alpha = 0.9f))
            .border(if (p.live) 1.5.dp else 1.dp, if (p.live) AuraColors.AccentBlue else AuraColors.Border.copy(alpha = 0.8f), shape).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(p.title, style = AuraTypography.Nav.copy(fontSize = 18.sp, fontWeight = if (p.live) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal), maxLines = 1, modifier = Modifier.weight(1f))
        if (p.live) { Spacer(Modifier.width(8.dp)); LiveMarker("LIVE", 14) }
    }
}
