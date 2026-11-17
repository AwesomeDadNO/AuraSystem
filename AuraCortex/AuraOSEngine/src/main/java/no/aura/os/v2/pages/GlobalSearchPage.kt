package no.aura.os.v2.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.profiles.Profile
import no.aura.os.v2.search.*
import no.aura.os.v2.theme.*

@Stable
class GlobalSearchUiState(val scroll: LazyListState) {
    var query by mutableStateOf("")
    var page by mutableIntStateOf(1)
    var filter by mutableStateOf<SearchKind?>(null)
    var results by mutableStateOf<SearchResults?>(null)
    var loading by mutableStateOf(false)
    fun query(value: String) { query = value.take(160); page = 1; results = null }
}

@Composable
fun GlobalSearchPage(service: GlobalSearchService, profile: Profile, entry: FocusRequester, state: GlobalSearchUiState, onHit: (SearchHit) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val firstFilter = remember { FocusRequester() }
    val query = state.query
    val page = state.page
    var filter by state::filter
    var results by state::results
    var loading by state::loading
    LaunchedEffect(service, profile, query, page) {
        if (query.trim().length < 2) { loading = false; return@LaunchedEffect }
        loading = true
        delay(350)
        val next = service.search(SearchRequest(profile.id, query, page, profile.isChild))
        val old = results
        results = if (page == 1 || old == null) next else next.copy(groups = next.groups.mapValues { (kind, batch) ->
            batch.copy(hits = (old.groups[kind]?.hits.orEmpty() + batch.hits).distinctBy { it.target })
        })
        loading = false
    }
    LazyColumn(state = state.scroll, modifier = Modifier.fillMaxSize().padding(start = 144.dp, top = 130.dp, end = 72.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 140.dp)) {
        item { Text("Search AuraOS", style = AuraTypography.PageTitle) }
        item { BasicTextField(value = query, onValueChange = { state.query(it) }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); runCatching { firstFilter.requestFocus() } }), textStyle = AuraTypography.Body.copy(color = AuraColors.TextPrimary),
            modifier = Modifier.fillMaxWidth().focusRequester(entry).onFocusChanged { if (it.isFocused) keyboard?.show() }.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key in listOf(Key.Enter, Key.DirectionCenter)) { keyboard?.show(); true }
                else { if (event.key == Key.DirectionDown) keyboard?.hide(); false }
            }.background(AuraColors.Surface, RoundedCornerShape(12.dp)).padding(20.dp),
            decorationBox = { inner -> if (query.isEmpty()) Text("Movies, TV shows, apps, music and settings", style = AuraTypography.Body, color = AuraColors.TextMuted); inner() }) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            (listOf<SearchKind?>(null) + SearchKind.values()).forEach { kind ->
                Text((if (filter == kind) "● " else "") + (kind?.label ?: "All"), style = AuraTypography.Button,
                    modifier = (if (kind == null) Modifier.focusRequester(firstFilter) else Modifier).auraFocusable(RoundedCornerShape(8.dp)) { filter = kind }.padding(12.dp))
            }
        } }
        if (loading) item { Text("Searching…", style = AuraTypography.Body) }
        if (query.trim().length < 2) item { Text("Enter at least two characters.", style = AuraTypography.Body) }
        results?.groups?.filterKeys { filter == null || it == filter }?.forEach { (kind, batch) ->
            item(key = "group:${kind.name}") { Text(kind.label, style = AuraTypography.SectionTitle) }
            batch.message?.let { message -> item { Text(message, style = AuraTypography.Label, color = AuraColors.TextSecondary) } }
            if (batch.hits.isEmpty() && !loading) item { Text("No results.", style = AuraTypography.Label) }
            items(batch.hits, key = { kind.name + it.target }) { hit ->
                Column(Modifier.fillMaxWidth().auraFocusable(RoundedCornerShape(12.dp)) { onHit(hit) }.background(AuraColors.Surface, RoundedCornerShape(12.dp)).padding(18.dp)) {
                    Text(hit.title, style = AuraTypography.CardTitle)
                    Text(listOfNotNull(hit.subtitle, hit.source).joinToString(" · "), style = AuraTypography.Label, color = AuraColors.TextSecondary)
                }
            }
        }
        if (results?.groups?.values?.any { it.hasMore } == true && page < 20) item {
            Text("More results", style = AuraTypography.Button, modifier = Modifier.auraFocusable(RoundedCornerShape(10.dp)) { if (!loading) state.page++ }.padding(16.dp))
        }
    }
}
