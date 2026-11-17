@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package no.aura.os.v2.pages

import no.aura.os.v2.data.HeroAction
import no.aura.os.v2.data.HeroEntry
import no.aura.os.v2.data.MediaItem
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.onFocusChanged
import no.aura.os.v2.focus.verticalLinks
import androidx.compose.ui.focus.focusRequester
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.AuraTypography
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.aura.os.v2.cards.CardSizes
import no.aura.os.v2.data.ShelfPageContent
import no.aura.os.v2.hero.CinematicHero
import no.aura.os.v2.hero.HeroLayout
import no.aura.os.v2.hero.HeroRotation
import no.aura.os.v2.overlay.DemoDialog
import no.aura.os.v2.shelves.ShelfRow
import no.aura.os.v2.theme.AuraDimensions

/** Geometry that differs between Home, Movies and TV Shows (measured on each mockup, 1920x1080 canvas units). */
data class ShelfLayout(val hero: HeroLayout, val sizes: CardSizes, val shelfGap: Dp)

object ShelfLayouts {
    val Home = ShelfLayout(HeroLayout(660.dp, 128.dp), CardSizes(DpSize(298.dp, 168.dp), DpSize(238.dp, 106.dp)), 16.dp)
    val Movies = ShelfLayout(HeroLayout(645.dp, 112.dp), CardSizes(DpSize(298.dp, 168.dp), DpSize(281.dp, 104.dp), compactGap = 7.dp), 7.dp)
    val TvShows = ShelfLayout(HeroLayout(645.dp, 108.dp), CardSizes(DpSize(298.dp, 168.dp), DpSize(277.dp, 98.dp)), 8.dp)
}

/** Left inset of shelf content: rail (94) + 43 = 137, measured on the mockups. */
val ContentStart = 137.dp

/** Page state hoisted by the shell so scroll position, shelf positions and hero index survive route switches. */
@Stable
class ShelfPageUiState(val page: LazyListState, val rows: List<LazyListState>) { var heroIndex by mutableIntStateOf(0) }

@Composable
fun rememberShelfPageUiState(rowCount: Int): ShelfPageUiState {
    val page = rememberLazyListState()
    val rows = List(rowCount) { rememberLazyListState() }
    return remember(rowCount) { ShelfPageUiState(page, rows) }
}

/** Home / Movies / TV Shows: hero + shelves with deterministic focus (hero -> row 0 -> ... -> last row; DPAD_UP reverses). */
@Composable
fun ShelfPage(
    content: ShelfPageContent, layout: ShelfLayout, ui: ShelfPageUiState, entry: FocusRequester, rotationPaused: Boolean,
    onDialog: (DemoDialog) -> Unit, modifier: Modifier = Modifier,
    /** Real Mode hooks: when set they replace the demo dialogs. [onShelfEnd] requests the next page of a shelf. */
    onItem: ((MediaItem) -> Unit)? = null, onHeroAction: ((HeroEntry, HeroAction) -> Unit)? = null, onShelfEnd: ((String) -> Unit)? = null, onCategory: ((no.aura.os.v2.data.CategoryCard) -> Unit)? = null, onTrailer: ((HeroEntry) -> Unit)? = null, statusContent: (@Composable (up: FocusRequester, down: FocusRequester) -> Unit)? = null,
    statusEntry: FocusRequester? = null, statusExit: FocusRequester? = null,
    shelfStateIndex: ((String) -> Int)? = null,
) {
    no.aura.os.v2.shell.ReportChromeScroll(ui.page)
    SideEffect { no.aura.os.v2.diag.Diag.count("shelfpage.recompose") }
    var heroFocused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val requestersById = remember { mutableMapOf<String, FocusRequester>() }
    val rowRequesters = content.shelves.map { requestersById.getOrPut(it.id) { FocusRequester() } }
    val categoryRequester = remember { FocusRequester() }
    val firstShelf = rowRequesters.firstOrNull() ?: FocusRequester.Cancel
    val afterStatus = if (content.categories.isNotEmpty()) categoryRequester else firstShelf
    val heroPx = with(androidx.compose.ui.platform.LocalDensity.current) { layout.hero.height.roundToPx() }
    val rotating by remember { derivedStateOf { HeroRotation.shouldRotate(content.hero.size, ui.page.firstVisibleItemIndex, ui.page.firstVisibleItemScrollOffset, heroPx) } }
    LaunchedEffect(rotating, rotationPaused, heroFocused, content.hero.size) {
        while (rotating && !rotationPaused && !heroFocused) { delay(HeroRotation.INTERVAL_MS); ui.heroIndex = HeroRotation.next(ui.heroIndex, content.hero.size) }
    }
    LazyColumn(modifier.fillMaxSize(), state = ui.page, contentPadding = PaddingValues(bottom = 140.dp)) {
        item(key = "hero") {
            CinematicHero(
                modifier = Modifier.onFocusChanged { heroFocused = it.hasFocus }, entries = content.hero, index = ui.heroIndex, layout = layout.hero, primaryRequester = entry, downTarget = statusEntry ?: afterStatus,
                onActionFocused = { if (ui.page.firstVisibleItemIndex != 0 || ui.page.firstVisibleItemScrollOffset != 0) scope.launch { ui.page.animateScrollToItem(0) } },
                onTrailer = onTrailer,
                onAction = { e, a -> if (onHeroAction != null) onHeroAction(e, a) else onDialog(heroDialog(e.title, a.label)) },
            )
        }
        if (statusContent != null) item(key = "status") { statusContent(entry, afterStatus) }
        if (content.categories.isNotEmpty()) item(key = "categories") {
            Column(Modifier.padding(bottom = 20.dp)) {
                Text("Categories", style = AuraTypography.SectionTitle, modifier = Modifier.padding(start = ContentStart, bottom = 12.dp))
                LazyRow(modifier = Modifier.verticalLinks(up = statusExit ?: entry, down = firstShelf), contentPadding = PaddingValues(start = ContentStart, end = 72.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(content.categories.size, key = { content.categories[it].id }) { index ->
                        val card = content.categories[index]
                        no.aura.os.v2.cards.CategoryArtCard(card,
                            Modifier.then(if (index == 0) Modifier.focusRequester(categoryRequester) else Modifier)) { onCategory?.invoke(card) }
                    }
                }
            }
        }
        content.shelves.forEachIndexed { i, shelf ->
            item(key = shelf.id) {
                ShelfRow(
                    shelf, layout.sizes, layout.shelfGap, ui.rows[shelfStateIndex?.invoke(shelf.id) ?: i], ContentStart, rowRequesters[i],
                    up = if (i == 0) { if (content.categories.isNotEmpty()) categoryRequester else statusExit ?: entry } else rowRequesters[i - 1], down = rowRequesters.getOrNull(i + 1) ?: FocusRequester.Cancel,
                    onItem = { if (onItem != null) onItem(it) else onDialog(DemoDialog("Demo Mode", listOf("\"${it.title}\" — demo detail overlay.", "Details, playback and availability are simulated.", "No network, accounts or live sources are used."))) },
                    onEndReached = onShelfEnd?.let { cb -> { cb(shelf.id) } },
                )
            }
        }
    }
}

internal fun heroDialog(title: String, action: String): DemoDialog = when (action) {
    "Play", "Watch Now", "Watch Live" -> DemoDialog("Simulated playback", listOf("\"$title\" would start playing here.", "Demo Mode: no stream is opened and no network is used."))
    "More details", "Match Hub", "View Album" -> DemoDialog("Demo details", listOf("\"$title\" — detail overlay.", "Synopsis, cast and availability are illustrative."))
    "Open Queue" -> DemoDialog("Queue (demo)", listOf("Through the Mist — Elara", "Afterglow — Orion Flux", "Tidal Memories — Cresthaven"))
    else -> DemoDialog.simple("$action \"$title\"")
}
