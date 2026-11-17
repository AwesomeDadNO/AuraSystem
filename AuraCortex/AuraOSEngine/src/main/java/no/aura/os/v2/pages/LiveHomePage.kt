@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package no.aura.os.v2.pages

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import no.aura.os.v2.apps.LaunchableApp
import no.aura.os.v2.brand.BrandLabels
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.data.*
import no.aura.os.v2.focus.verticalLinks
import no.aura.os.v2.hero.CinematicHero
import no.aura.os.v2.hero.HeroRotation
import no.aura.os.v2.livetv.*
import no.aura.os.v2.theme.*

/** Home-style app discovery. Only the schedule/art are demo; buttons launch actual discovered apps. */
@Composable
fun LiveHomePage(apps: List<LaunchableApp>, loading: Boolean, child: Boolean, entry: FocusRequester,
    banner: ((String) -> Bitmap?)?, onLaunch: (LaunchableApp) -> Unit, onMessage: (String) -> Unit,
    rotationPaused: Boolean = false,
) {
    val page = rememberLazyListState()
    no.aura.os.v2.shell.ReportChromeScroll(page)
    var heroIndex by remember { mutableIntStateOf(0) }
    var heroFocused by remember { mutableStateOf(false) }
    val firstApp = remember { FocusRequester() }
    val candidates = remember(apps, child) { LiveAppDiscovery.pick(apps, child) }
    val heroes = remember { LiveSchedulePreview.programmes.map { p -> HeroEntry(
        "live-preview:${p.id}", BrandLabels.LIVE_ON_AURA, p.title,
        "Demo schedule and illustrative artwork. Open ${p.provider} to check actual broadcasts and availability.",
        "DEMO EPG", listOf(p.provider, p.time, "Illustrative schedule"), Artwork(asset = "category:${p.art}"),
        actions = listOf(HeroAction("open-app", "Open ${p.provider}", HeroIcon.OPEN, primary = true))) } }
    LaunchedEffect(page.firstVisibleItemIndex, rotationPaused, heroFocused, child) {
        while (!child && page.firstVisibleItemIndex == 0 && !rotationPaused && !heroFocused) {
            delay(HeroRotation.INTERVAL_MS); heroIndex = HeroRotation.next(heroIndex, heroes.size)
        }
    }
    if (child) {
        Column(Modifier.fillMaxSize().padding(start = ContentStart, top = 140.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Live TV", style = AuraTypography.PageTitle)
            Text("External live apps are unavailable on child profiles until their content restrictions can be verified.", style = AuraTypography.Body)
            Text("Show availability", style = AuraTypography.Button, modifier = Modifier.focusRequester(entry).auraFocusable(RoundedCornerShape(12.dp)) { onMessage("Live apps cannot be opened from this child profile.") }.padding(16.dp))
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), state = page, contentPadding = PaddingValues(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item("hero") { CinematicHero(heroes, heroIndex, ShelfLayouts.Home.hero, entry, if (candidates.isNotEmpty()) firstApp else FocusRequester.Cancel,
            onActionFocused = {}, onAction = { hero, _ ->
                val preview = LiveSchedulePreview.programmes.first { "live-preview:${it.id}" == hero.id }
                val app = LiveAppDiscovery.appFor(preview.provider, candidates)
                if (app == null) onMessage("${preview.provider} is not installed as a supported launchable app. This programme/time is demo data.") else onLaunch(app)
            }, modifier = Modifier.onFocusChanged { heroFocused = it.hasFocus }) }
        item("apps-heading") {
            Column(Modifier.padding(start = ContentStart, end = 72.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Apps with live TV", style = AuraTypography.SectionTitle)
                Text("Open the provider app for channels and actual schedules. Subscription, country and app support determine availability.", style = AuraTypography.Metadata)
                if (loading) Text("Finding installed TV apps…", style = AuraTypography.Body)
                else if (candidates.isEmpty()) Text("No supported live TV apps were detected on this device.", style = AuraTypography.Body)
            }
        }
        item("apps") {
            LazyRow(Modifier.verticalLinks(up = entry), contentPadding = PaddingValues(start = ContentStart, end = 72.dp, top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                itemsIndexed(candidates, key = { _, app -> app.packageName }) { index, app ->
                    val art by produceState<Bitmap?>(null, app.packageName, banner) { value = withContext(Dispatchers.IO) { banner?.invoke(app.packageName) } }
                    Column(Modifier.width(256.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(256.dp,144.dp).then(if (index == 0) Modifier.focusRequester(firstApp) else Modifier)
                            .auraFocusable(RoundedCornerShape(12.dp)) { onLaunch(app) }.clip(RoundedCornerShape(12.dp)).background(AuraColors.Surface)) {
                            art?.let { Image(it.asImageBitmap(), app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                                ?: Text(app.label, style = AuraTypography.CardTitle, modifier = Modifier.padding(18.dp))
                        }
                    }
                }
            }
        }
        item("demo-note") { Text("Schedule preview · Demo EPG and illustrations only · IPTV is deferred", style = AuraTypography.Label, modifier = Modifier.padding(start = ContentStart)) }
    }
}
