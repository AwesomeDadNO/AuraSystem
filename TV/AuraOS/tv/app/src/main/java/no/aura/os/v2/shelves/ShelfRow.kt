@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package no.aura.os.v2.shelves

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import no.aura.os.v2.cards.CardSizes
import no.aura.os.v2.cards.CardSpec
import no.aura.os.v2.cards.CompactCard
import no.aura.os.v2.cards.ContinueCard
import no.aura.os.v2.cards.PosterCard
import no.aura.os.v2.cards.PosterSpec
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import no.aura.os.v2.data.ContinueItem
import no.aura.os.v2.data.MediaItem
import no.aura.os.v2.data.Shelf
import no.aura.os.v2.focus.leftToRail
import no.aura.os.v2.focus.verticalLinks
import no.aura.os.v2.theme.AuraDimensions
import no.aura.os.v2.theme.AuraTypography

/**
 * Shelf vertical rhythm (canvas units, measured on the mockups): header line 30, 2 spacer + 8 row padding to the cards
 * (the 8 keeps the focus scale/shadow from being clipped), 8 bottom padding + [gap] to the next header.
 */
object ShelfSpec { val HeaderHeight = 30.dp; val RowPad = 8.dp; val DefaultGap = 16.dp }

private const val END_THRESHOLD = 4

@Composable
fun ShelfHeader(title: String, startInset: Dp) {
    Text(title, style = AuraTypography.SectionTitle, modifier = Modifier.padding(start = startInset).height(ShelfSpec.HeaderHeight))
    Spacer(Modifier.height(2.dp))
}

/**
 * One horizontal shelf. [rowRequester] lets the previous/next row (and the hero) jump here; the LazyRow remembers its last focused card
 * (focusRestorer). [up]/[down] are explicit so DPAD_UP/DOWN never depend on spatial search.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ShelfRow(
    shelf: Shelf, sizes: CardSizes, gap: Dp, state: LazyListState, startInset: Dp, rowRequester: FocusRequester,
    up: FocusRequester, down: FocusRequester, onItem: (MediaItem) -> Unit, modifier: Modifier = Modifier, onEndReached: (() -> Unit)? = null,
) {
    androidx.compose.runtime.SideEffect { no.aura.os.v2.diag.Diag.count("shelf.recompose") }
    // Lazy pagination: ask for the next page once the last cards are near (a few cards before the end), at most once per growth of the list.
    if (onEndReached != null) LaunchedEffect(state, shelf.items.size) {
        snapshotFlow { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= shelf.items.size - END_THRESHOLD }.distinctUntilChanged().filter { it }.collect { onEndReached() }
    }
    Column(modifier) {
        ShelfHeader(shelf.title, startInset)
        LazyRow(
            state = state,
            modifier = Modifier.verticalLinks(up, down).focusRequester(rowRequester).focusRestorer(),
            contentPadding = PaddingValues(start = startInset, end = AuraDimensions.PageMargin, top = ShelfSpec.RowPad, bottom = ShelfSpec.RowPad),
            horizontalArrangement = Arrangement.spacedBy(if (shelf.poster) PosterSpec.Gap else if (shelf.continueStyle) sizes.continueGap else sizes.compactGap),
        ) {
            itemsIndexed(shelf.items, key = { _, c -> c.item.id }) { i, c ->
                if (shelf.poster) PosterCard(c.item, Modifier.leftToRail(i == 0)) { onItem(c.item) }
                else if (shelf.continueStyle) ContinueCard(c, Modifier.leftToRail(i == 0), sizes.continueSize) { onItem(c.item) }
                else CompactCard(c.item, Modifier.leftToRail(i == 0), sizes.compactSize) { onItem(c.item) }
            }
        }
        Spacer(Modifier.height(gap))
    }
}
