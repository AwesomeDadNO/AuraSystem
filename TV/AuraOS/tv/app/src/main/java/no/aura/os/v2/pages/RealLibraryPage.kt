package no.aura.os.v2.pages

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import no.aura.os.v2.cards.ContinueCard
import no.aura.os.v2.cards.PosterCard
import no.aura.os.v2.data.*
import no.aura.os.v2.shell.Route
import no.aura.os.v2.theme.*
import no.aura.os.v2.tmdb.*

object LibrarySections {
    fun titles(items: List<MediaItem>, type: MediaType)=items.filter { TitleRef.parse(it.id)?.type==type }.sortedBy { it.title.lowercase() }
}
/** Saved titles and real playback rows only, scoped to the active profile. */
@Composable
fun RealLibraryPage(route:Route, favorites:List<MediaItem>, watchlist:List<MediaItem>, watching:List<ContinueItem>, entry:FocusRequester,
    onOpen:(MediaItem)->Unit, systemWatching:List<ContinueItem> = emptyList(), systemStatus:String="") {
    Column(Modifier.fillMaxSize().padding(start=150.dp,top=122.dp,end=60.dp)) {
        // Navigation must never point into a lazy row that may not be composed yet.
        Text(if(route==Route.SAVED) "Favorites / Watchlist" else "Library",
            style=AuraTypography.PageTitle, modifier=Modifier.focusRequester(entry).focusable())
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(top=16.dp,bottom=100.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item { Text("Continue Watching",style=AuraTypography.SectionTitle) }
            item {
                if(watching.isEmpty()) Text("Start playing a movie or episode to see it here.",style=AuraTypography.Body)
                else LazyRow(horizontalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(vertical=8.dp)) {
                    itemsIndexed(watching,key={index,row -> "${row.item.id}:$index"}) { _,row -> ContinueCard(row) { onOpen(row.item) } }
                }
            }
            item { Text("Android TV · Continue Watching",style=AuraTypography.SectionTitle) }
            item {
                if(systemWatching.isEmpty()) Text(systemStatus.ifBlank { "No shared Android TV rows available." },style=AuraTypography.Body,color=AuraColors.TextSecondary)
                else LazyRow(horizontalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(vertical=8.dp)) {
                    itemsIndexed(systemWatching,key={index,row -> "${row.item.id}:$index"}) { _,row -> ContinueCard(row) { onOpen(row.item) } }
                }
            }
            listOf("Favorites" to favorites,"Watchlist" to watchlist).forEach { (label,items) ->
                MediaType.entries.forEach { type ->
                    val titles=LibrarySections.titles(items,type)
                    item { Text(label+" · "+if(type==MediaType.MOVIE) "Movies" else "TV Shows",style=AuraTypography.SectionTitle) }
                    item {
                        if(titles.isEmpty()) Text("No saved titles here yet.",style=AuraTypography.Body,color=AuraColors.TextSecondary)
                        else LazyRow(horizontalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(vertical=8.dp)) {
                            items(titles,key={it.id}) { title -> PosterCard(title) { onOpen(title) } }
                        }
                    }
                }
            }
        }
    }
}
