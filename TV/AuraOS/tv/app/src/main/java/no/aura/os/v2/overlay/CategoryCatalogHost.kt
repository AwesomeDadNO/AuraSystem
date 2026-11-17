@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package no.aura.os.v2.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import no.aura.os.v2.hero.HeroLayout
import no.aura.os.v2.focus.verticalLinks
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.data.*
import no.aura.os.v2.pages.*
import no.aura.os.v2.profiles.Profile
import no.aura.os.v2.theme.*
import no.aura.os.v2.tmdb.*

/** Keep this composition mounted beneath details to preserve the category's row, scroll and hero state. */
@Composable
fun CategoryCatalogHost(card: CategoryCard, service: RealCatalogService, profile: Profile, onItem: (MediaItem) -> Unit, onClose: () -> Unit,
    topNavigation: @Composable (FocusRequester) -> Unit = {}, onTrailer: ((HeroEntry) -> Unit)? = null,
) {
    val parts = card.id.split(':')
    val type = MediaType.fromPath(parts.getOrNull(1)) ?: return
    val genre = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 } ?: return
    key(card.id, profile) {
        var value by remember { mutableStateOf<CategoryContent?>(null) }
        var loading by remember { mutableStateOf(true) }
        var busy by remember { mutableStateOf(emptySet<CategoryFeed>()) }
        val scope = rememberCoroutineScope()
        val entry = remember { FocusRequester() }
        val ui = rememberShelfPageUiState(CategoryFeed.values().size)
        LaunchedEffect(Unit) {
            loading = true
            value = service.categoryPage(type, genre, profile)
            loading = false
            withFrameNanos { }
            runCatching { entry.requestFocus() }
        }
        fun loadMore(feed: CategoryFeed) {
            if (feed in busy) return
            busy = busy + feed
            scope.launch {
                try {
                    val before = value ?: return@launch
                    val result = service.categoryMore(type, genre, feed, before, profile)
                    // Merge only this row so simultaneous row requests cannot overwrite each other.
                    value = value?.let { now -> now.copy(
                        content = now.content.copy(hero = now.content.hero.ifEmpty { result.content.hero }, shelves = now.content.shelves.map { row -> if (row.id == feed.name) result.content.shelves.first { it.id == row.id } else row }),
                        nextPage = now.nextPage + result.nextPage.filterKeys { it == feed },
                        more = (now.more - feed) + result.more.filter { it == feed },
                        errors = (now.errors - feed) + result.errors.filterKeys { it == feed },
                    ) }
                } finally { busy = busy - feed }
            }
        }
        // Stable requesters survive removal of a successful retry and insertion of newly populated shelves.
        val statusRequesterByFeed = remember { CategoryFeed.values().associateWith { FocusRequester() } }
        val statusActions = value?.let { state ->
            state.content.shelves.mapNotNull { row ->
                val feed = CategoryFeed.valueOf(row.id)
                when {
                    feed in state.errors -> Triple(feed, "Retry ${feed.label}", state.errors[feed]?.message())
                    row.items.isEmpty() && feed in state.more -> Triple(feed, "Find more ${row.title}", null)
                    else -> null
                }
            }
        }.orEmpty()
        val statusRequesters = statusActions.map { statusRequesterByFeed.getValue(it.first) }
        var focusedStatusFeed by remember { mutableStateOf<CategoryFeed?>(null) }
        LaunchedEffect(statusActions.map { it.first }) {
            val focused = focusedStatusFeed
            if (focused != null && statusActions.none { it.first == focused }) {
                focusedStatusFeed = null
                withFrameNanos { }
                runCatching { (statusRequesters.firstOrNull() ?: entry).requestFocus() }
            }
        }
        val status: @Composable (FocusRequester, FocusRequester) -> Unit = { up, down ->
            Column(Modifier.padding(start = ContentStart, end = 72.dp, top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loading) Text("Loading…", style = AuraTypography.Body)
                if (!loading && value?.content?.shelves?.all { it.items.isEmpty() } == true && value?.errors.isNullOrEmpty()) {
                    Text("No titles available for this profile.", style = AuraTypography.Body)
                }
                statusActions.forEachIndexed { index, (feed, label, error) ->
                    key(feed) {
                        if (error != null) Text("${feed.label}: $error", style = AuraTypography.Body)
                        Text(label, style = AuraTypography.Button,
                            modifier = Modifier.focusRequester(statusRequesters[index])
                                .onFocusChanged { if (it.isFocused) focusedStatusFeed = feed else if (focusedStatusFeed == feed) focusedStatusFeed = null }
                                .verticalLinks(
                                up = statusRequesters.getOrNull(index - 1) ?: up,
                                down = statusRequesters.getOrNull(index + 1) ?: down,
                            ).auraFocusable(RoundedCornerShape(8.dp)) { loadMore(feed) }.padding(8.dp))
                    }
                }
            }
        }
        val categoryChrome = remember { no.aura.os.v2.shell.ChromeScrollState() }
        CanvasDialog(onClose, fullScreen = true) {
            CompositionLocalProvider(no.aura.os.v2.shell.LocalChromeScroll provides categoryChrome) {
            Box(Modifier.fillMaxSize().background(AuraColors.Background)) {
                value?.let { state ->
                    ShelfPage(state.content.copy(shelves = state.content.shelves.filter { it.items.isNotEmpty() }),
                        ShelfLayouts.Movies.copy(hero = HeroLayout(710.dp, 180.dp)), ui, entry, loading, onDialog = {}, onItem = onItem,
                        modifier = if (state.content.hero.isEmpty()) Modifier.padding(top = 170.dp) else Modifier,
                        onHeroAction = { hero, _ -> onItem(MediaItem(hero.id, hero.title, hero.artwork)) },
                        onShelfEnd = { id -> CategoryFeed.values().firstOrNull { it.name == id }?.let { if (it !in state.errors) loadMore(it) } },
                        onTrailer = onTrailer, statusContent = status,
                        statusEntry = statusRequesters.firstOrNull(), statusExit = statusRequesters.lastOrNull(),
                        shelfStateIndex = { CategoryFeed.valueOf(it).ordinal })
                } ?: Box(Modifier.padding(top = 170.dp)) { status(entry, FocusRequester.Cancel) }
                topNavigation(entry)
                Row(Modifier.padding(start = ContentStart, top = 105.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text(card.title, style = AuraTypography.SectionTitle)
                    Text("Back to categories", style = AuraTypography.Button,
                        modifier = Modifier.then(if (value?.content?.hero.isNullOrEmpty()) Modifier.focusRequester(entry) else Modifier)
                            .then(if (value?.content?.hero.isNullOrEmpty()) Modifier.verticalLinks(down = statusRequesters.firstOrNull() ?: FocusRequester.Default) else Modifier)
                            .auraFocusable(RoundedCornerShape(8.dp), onClick = onClose).padding(8.dp))
                }
            }
        }
        }
    }
}
