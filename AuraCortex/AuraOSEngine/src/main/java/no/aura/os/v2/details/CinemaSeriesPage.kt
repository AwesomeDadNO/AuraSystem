@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package no.aura.os.v2.details

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import no.aura.os.v2.cards.auraEpisodeGloss
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.data.Artwork
import no.aura.os.v2.demo.ArtworkImage
import no.aura.os.v2.overlay.CanvasDialog
import no.aura.os.v2.shell.*
import no.aura.os.v2.theme.*
import no.aura.os.v2.tmdb.*

/** Approved Cinema mode: one contextual action lane, fixed-size episode chapter cards. */
@Composable
internal fun CinemaSeriesPage(
    details: TmdbDetails, season: Int, episodes: List<TmdbEpisode>, focusedEpisode: ()->Int?, loading: Boolean, error: String?,
    watched: (Int)->Boolean, fractions: Map<Int,Float>, primary: FocusRequester, playFocus: FocusRequester,
    episodeFocus: MutableMap<Int,FocusRequester>, status: String, inWatchlist: Boolean, favorite: Boolean,
    onSelect: (Int)->Unit, onSeason: (Int)->Unit, onPlay: (Int)->Unit, onToggle: (Int)->Unit,
    onWatchlist: ()->Unit, onFavorite: ()->Unit, onRetry: ()->Unit,
    belowEpisodes: @Composable ()->Unit,
) {
    val selected = CinemaSeriesLogic.selected(episodes, focusedEpisode(), watched)
    val infoFocus = remember { FocusRequester() }
    val markFocus = remember { FocusRequester() }
    val seasonsFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var infoOpen by remember(details.ref, season) { mutableStateOf(false) }
    val chapters = remember(season) { LazyListState() }
    fun cardFocus(): FocusRequester = selected?.let { episodeFocus.getOrPut(it.number) { FocusRequester() } } ?: FocusRequester.Cancel
    LaunchedEffect(season, episodes) {
        val index = episodes.indexOfFirst { it.number == selected?.number }
        if(index >= 0) chapters.scrollToItem(index)
    }
    val outerPage = rememberLazyListState()
    ReportChromeScroll(outerPage)
    val relatedEntry = remember { FocusRequester() }
    BoxWithConstraints(Modifier.fillMaxSize().background(AuraColors.Background)) {
        val cinemaHeight=maxHeight
        val path = CinemaSeriesLogic.backdrop(selected, details.backdropPath, details.posterPath)
        TmdbImages.url(TmdbImages.HERO, path)?.let { ArtworkImage(Artwork(imageUrl=it), Modifier.fillMaxSize()) }
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to AuraColors.Background.copy(alpha=.94f), .45f to AuraColors.Background.copy(alpha=.64f), 1f to Color.Transparent)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to AuraColors.Background.copy(alpha=.2f), .55f to Color.Transparent, 1f to AuraColors.Background)))
        LazyColumn(Modifier.fillMaxSize(), state=outerPage, contentPadding=PaddingValues(bottom=40.dp)) {
        item("cinema") {
        Column(Modifier.fillMaxWidth().height(cinemaHeight).padding(start=96.dp, end=72.dp, top=112.dp, bottom=28.dp)) {
            Text(details.title, style=AuraTypography.PageTitle, maxLines=1, overflow=TextOverflow.Ellipsis)
            Text(listOfNotNull(details.year, details.genres.take(2).joinToString(" · ").takeIf { it.isNotBlank() }, details.certification).joinToString("  ·  "), style=AuraTypography.Metadata, modifier=Modifier.padding(top=6.dp))
            Row(Modifier.padding(top=12.dp), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                Box(Modifier.width(2.dp).height(38.dp).background(AuraColors.Focus.copy(alpha=.55f)))
                Text("Seasons:", style=AuraTypography.Button, color=AuraColors.TextSecondary)
                LazyRow(Modifier.weight(1f), horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                items((1..(details.seasons ?: 1).coerceIn(1,40)).toList(), key={it}) { sn ->
                    val interaction=rememberNavigationInteraction()
                    Text("Season $sn", color=AuraColors.TextPrimary, style=AuraTypography.Button,
                        modifier=Modifier.then(if(sn==1) Modifier.focusRequester(seasonsFocus) else Modifier).focusProperties { down=playFocus }
                            .auraNavigationFocusable(interaction, season==sn) { onSeason(sn) }.padding(horizontal=18.dp, vertical=12.dp))
                }
            }
            }
            Spacer(Modifier.weight(1f))
            Text(selected?.let { "Season $season · Episode ${it.number}" } ?: if(loading) "Loading episodes…" else "Season $season", style=AuraTypography.SectionTitle)
            no.aura.os.v2.hero.HeroTitleText(selected?.name ?: details.title,width=1250.dp,height=58.dp,maxFont=52,minFont=18,modifier=Modifier.padding(top=8.dp))
            Text(listOfNotNull(DetailsLogic.runtimeLabel(selected?.runtimeMinutes), selected?.airDate?.let { "Air date: $it" }, selected?.takeIf { watched(it.number) }?.let { "✓ Watched" }).joinToString("  ·  "), style=AuraTypography.Metadata, modifier=Modifier.padding(top=8.dp))
            Text((selected?.overview ?: details.overview).ifBlank { "No synopsis available." }, style=AuraTypography.Body.copy(fontSize=23.sp, lineHeight=29.sp), maxLines=2, overflow=TextOverflow.Ellipsis, modifier=Modifier.widthIn(max=920.dp).height(70.dp).padding(top=8.dp))
            Row(Modifier.padding(top=12.dp), horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                HeroButton(selected?.let { if(!watched(it.number) && (fractions[it.number] ?: 0f)>0f) "▶ Resume" else "▶ Play" } ?: "Episodes unavailable", true,
                    Modifier.focusRequester(primary).focusRequester(playFocus).focusProperties { up=seasonsFocus; down=cardFocus(); right=infoFocus }) {
                    selected?.let { onSelect(it.number); onPlay(it.number) }
                }
                HeroButton("ⓘ More info", false, Modifier.focusRequester(infoFocus).focusProperties { left=playFocus; right=if(selected==null) FocusRequester.Cancel else markFocus; up=seasonsFocus; down=cardFocus() }) { selected?.let { onSelect(it.number) }; infoOpen=true }
                selected?.let { ep ->
                    HeroButton(if(watched(ep.number)) "✓ Watched" else "✓ Mark watched", false,
                        Modifier.focusRequester(markFocus).semantics { role=Role.Checkbox; toggleableState=if(watched(ep.number)) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off; stateDescription=if(watched(ep.number)) "Watched" else "Unwatched" }
                            .focusProperties { left=infoFocus; up=seasonsFocus; down=cardFocus() }) { onSelect(ep.number); onToggle(ep.number) }
                }
            }
            Text(error ?: status, style=AuraTypography.Metadata, maxLines=1, modifier=Modifier.height(30.dp).padding(top=5.dp))
            if(error!=null) HeroButton("Retry episodes", false, onClick=onRetry)
            LazyRow(state=chapters, contentPadding=PaddingValues(vertical=12.dp, horizontal=4.dp), horizontalArrangement=Arrangement.spacedBy(18.dp), modifier=Modifier.fillMaxWidth().padding(top=18.dp)) {
                items(episodes, key={it.number}) { ep ->
                    val current=ep.number==selected?.number
                    val shape=RoundedCornerShape(10.dp)
                    Box(Modifier.width(408.dp).aspectRatio(16f/9f).focusRequester(episodeFocus.getOrPut(ep.number) { FocusRequester() })
                        .focusProperties { up=playFocus; down=relatedEntry }
                        .auraFocusable(shape, scale=1f, elevation=4.dp, onFocused={onSelect(ep.number)}, onClick={onSelect(ep.number); onPlay(ep.number)})
                        .clip(shape).background(AuraColors.Surface).border(1.dp, if(current) AuraColors.Focus.copy(alpha=.6f) else AuraColors.Border, shape)) {
                        TmdbImages.url(TmdbImages.STILL, ep.stillPath)?.let { ArtworkImage(Artwork(imageUrl=it),Modifier.fillMaxSize()) }
                        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha=.9f))))
                        Box(Modifier.matchParentSize().auraEpisodeGloss(shape))
                        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text("Episode ${ep.number}",style=AuraTypography.Label)
                            Text(ep.name,style=AuraTypography.CardTitle,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.drawWithContent { drawContent(); if(current) drawLine(AuraColors.Focus,Offset(0f,size.height),Offset(size.width,size.height),2.dp.toPx()) })
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                Text(DetailsLogic.runtimeLabel(ep.runtimeMinutes).orEmpty(),style=AuraTypography.Label)
                                if(watched(ep.number)) Text("✓ Watched",style=AuraTypography.Label)
                            }
                            val fraction=if(watched(ep.number)) 1f else fractions[ep.number]
                            if(fraction!=null) Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha=.25f))) {
                                Box(Modifier.fillMaxWidth(fraction.coerceIn(0f,1f)).fillMaxHeight().background(AuraColors.Progress))
                            }
                        }
                    }
                }
            }
            if(!loading && error==null && episodes.isEmpty()) Text("No episodes available for this profile.",style=AuraTypography.Metadata)
        }
        }
        item("cast-related") {
            Column(Modifier.padding(horizontal=96.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                HeroButton("Back to episode",false,Modifier.focusRequester(relatedEntry)) { scope.launch { outerPage.scrollToItem(0); withFrameNanos{}; runCatching { playFocus.requestFocus() } } }
                belowEpisodes()
            }
        }
        }
    }
    if(infoOpen) {
        fun dismiss() { infoOpen=false; scope.launch { withFrameNanos{}; runCatching { infoFocus.requestFocus() } } }
        CanvasDialog(::dismiss) {
            val close=remember { FocusRequester() }
            val content=remember { FocusRequester() }
            val scroll=rememberScrollState()
            LaunchedEffect(Unit) { withFrameNanos{}; close.requestFocus() }
            Column(Modifier.width(1020.dp).heightIn(max=880.dp).background(AuraColors.Panel,RoundedCornerShape(20.dp)).padding(28.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f,fill=false).focusRequester(content).onPreviewKeyEvent { event ->
                    if(event.type!=KeyEventType.KeyDown) false else {
                        val delta=when(event.key) { Key.DirectionDown -> 180f; Key.DirectionUp -> -180f; else -> 0f }
                        if(delta==0f) false else { if((delta<0 && scroll.value==0)||(delta>0 && scroll.value==scroll.maxValue)) close.requestFocus() else scope.launch { scroll.scrollBy(delta) }; true }
                    }
                }.semantics { contentDescription="Episode information. Up and Down scroll." }.focusable().verticalScroll(scroll),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    TmdbImages.url(TmdbImages.STILL,selected?.stillPath ?: details.backdropPath)?.let { ArtworkImage(Artwork(imageUrl=it),Modifier.fillMaxWidth().height(330.dp)) }
                    Text(selected?.let { "S$season:E${it.number} · ${it.name}" } ?: details.title,style=AuraTypography.SectionTitle)
                    Text(listOfNotNull(selected?.airDate,DetailsLogic.runtimeLabel(selected?.runtimeMinutes)).joinToString(" · "),style=AuraTypography.Metadata)
                    Text((selected?.overview ?: details.overview).ifBlank { "No synopsis available." },style=AuraTypography.Body)
                    Text("Series: ${details.title}",style=AuraTypography.SectionTitle)
                    Text(details.overview.ifBlank { "No series synopsis available." },style=AuraTypography.Body)
                    if(details.cast.isNotEmpty()) Text("Cast: "+details.cast.take(12).joinToString { it.name },style=AuraTypography.Metadata)
                    Text(DetailsLogic.languageLabel(details),style=AuraTypography.Metadata)
                    Text("Metadata and artwork: TMDB. Stream tracks depend on the selected source.",style=AuraTypography.Label)
                }
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    HeroButton("Close",true,Modifier.focusRequester(close).focusProperties { up=content; down=content },onClick=::dismiss)
                    HeroButton(if(inWatchlist) "✓ In Watchlist" else "Add to Watchlist",false,onClick=onWatchlist)
                    HeroButton(if(favorite) "♥ Favourite" else "♡ Favourite",false,onClick=onFavorite)
                }
            }
        }
    }
}
