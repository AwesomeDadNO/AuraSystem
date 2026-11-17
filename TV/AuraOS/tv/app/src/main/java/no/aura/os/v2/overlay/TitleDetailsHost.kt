package no.aura.os.v2.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import no.aura.os.v2.focus.verticalLinks
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.data.Artwork
import no.aura.os.v2.settings.*
import no.aura.os.v2.demo.ArtworkImage
import no.aura.os.v2.profiles.Profile
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.AuraDimensions
import no.aura.os.v2.theme.AuraTypography
import no.aura.os.v2.tmdb.*
import no.aura.os.v2.playback.*
import no.aura.os.v2.ultramax.UltraStream
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import no.aura.os.v2.details.*
import no.aura.os.v2.watch.*
import no.aura.os.v2.cards.PosterCard
import no.aura.os.v2.shell.Route
import no.aura.os.v2.data.MediaItem
import no.aura.os.v2.theme.LocalReduceMotion
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.unit.sp

/** Plain-language, credential-free text for each failure; shown with Retry. */
fun TmdbError.message(): String = when (this) {
    TmdbError.NOT_CONFIGURED -> "TMDB is not configured in this build. Add TMDB_READ_TOKEN to local.properties and rebuild."
    TmdbError.UNAUTHORIZED -> "TMDB rejected the access token (authentication failed)."
    TmdbError.NOT_FOUND -> "TMDB has no record for this title."
    TmdbError.RATE_LIMITED -> "TMDB is limiting requests right now. Try again in a moment."
    TmdbError.NETWORK -> "Could not reach TMDB. Check the network connection."
    TmdbError.SERVER -> "TMDB reported a server problem."
    TmdbError.BAD_RESPONSE -> "TMDB returned a response this app could not read."
}

private sealed interface DetailsUi {
    object Loading : DetailsUi
    data class Ready(val d: TmdbDetails) : DetailsUi
    object Blocked : DetailsUi
    data class Failed(val e: TmdbError) : DetailsUi
}

private sealed interface PlayUi {
    object Idle : PlayUi
    object Resolving : PlayUi
    data class Message(val text: String) : PlayUi
    data class Choose(val streams: List<UltraStream>, val season: Int?, val episode: Int?) : PlayUi
    data class Playing(val stream: UltraStream, val season: Int?, val episode: Int?, val resumeOverride: Long? = null) : PlayUi
}

private sealed interface Rel { object Loading : Rel; data class Ok(val items: List<MediaItem>) : Rel }

/**
 * Cinematic title details (movie + series) in a full-screen Dialog: focus is trapped, Back pops to the previous title (when opened from a
 * recommendation) or closes and restores the focus of the originating card. Everything shown comes from TMDB (metadata, credits,
 * recommendations, trailer key) or UltraMax (sources); missing data is stated, never invented. Child profiles are gated by RealCatalogService.
 */
@Composable
fun TitleDetailsHost(
    ref: TitleRef, profile: Profile, service: RealCatalogService, playback: PlaybackService?, prefs: PlaybackPrefs,
    watch: WatchStore = MemoryWatchStore(), progress: no.aura.os.v2.watch.PrefsPlaybackProgress? = null,
    trailer: ((String) -> Boolean)? = null, onNavigate: ((Route) -> Unit)? = null,
    topNavigation: (@Composable (FocusRequester) -> Unit)? = null, initialEpisode: Pair<Int, Int>? = null, onClose: () -> Unit,
) {
    var stack by remember(ref) { mutableStateOf(listOf(ref)) }
    val cur = stack.last()
    val isTv = cur.type == MediaType.TV
    var attempt by remember { mutableIntStateOf(0) }
    var ui by remember(cur, attempt) { mutableStateOf<DetailsUi>(DetailsUi.Loading) }
    LaunchedEffect(cur, profile.id, attempt) {
        ui = when (val r = service.details(cur, profile)) {
            is DetailsResult.Ok -> DetailsUi.Ready(r.details); DetailsResult.Blocked -> DetailsUi.Blocked; is DetailsResult.Failed -> DetailsUi.Failed(r.error)
        }
    }
    SideEffect { no.aura.os.v2.diag.Diag.count("details.host.recompose") }
    val ready = ui as? DetailsUi.Ready
    val d = ready?.d
    val scope = rememberCoroutineScope()
    val heroPlay = remember(cur) { FocusRequester() }
    val primary = remember(cur) { FocusRequester() }
    var tick by remember { mutableIntStateOf(0) }            // bumps on any watch-state change
    var play by remember(cur) { mutableStateOf<PlayUi>(PlayUi.Idle) }
    var resolvedStreams by remember(cur) { mutableStateOf(emptyList<UltraStream>()) }
    var tab by remember(cur) { mutableStateOf(DetailsTab.forType(cur.type).first()) }
    val lastEpisode = remember(cur, profile.id, initialEpisode) { if (isTv) initialEpisode?.takeIf { cur == ref } ?: progress?.lastEpisode(profile.id, cur) else null }
    var season by remember(cur) { mutableIntStateOf(lastEpisode?.first ?: 1) }
    var episodes by remember(cur, season) { mutableStateOf<List<TmdbEpisode>?>(null) }
    var episodesError by remember(cur, season) { mutableStateOf<TmdbError?>(null) }
    val epState = remember(cur, season) { EpState().also { if (lastEpisode?.first == season) it.focused = lastEpisode.second } }                               // focused episode (null = season/overview state); read only by leaf composables
    val memory = remember(cur) { EpisodeMemory() }
    var lastPlayedEp by remember(cur) { mutableStateOf<Int?>(null) }
    val epFocus = remember(cur, season) { HashMap<Int, FocusRequester>() }
    var status by remember(cur) { mutableStateOf<String?>(null) }

    LaunchedEffect(cur, season, profile.id, ready != null) {
        if (isTv && ready != null) {
            when (val r = service.season(cur, season, profile)) {
                is TmdbResult.Ok -> { episodes = r.value; episodesError = null; epState.focused = memory.restore(season, r.value.map { it.number }) ?: epState.focused?.takeIf { n -> r.value.any { it.number == n } } }
                is TmdbResult.Failure -> { episodesError = r.error; episodes = emptyList() }
            }
        }
    }
    // One snapshot of the profile's watched set per change (not one SharedPreferences read + set copy per episode per recomposition).
    val watchedSet = remember(tick, profile.id) { watch.watched(profile.id) }
    fun watchedEp(n: Int): Boolean = WatchKeys.episode(cur, season, n)?.let { it in watchedSet } ?: false
    val eps = episodes.orEmpty()

    fun start(stream: UltraStream?, sn: Int?, en: Int?) {
        val det = d ?: return
        val pb = playback
        if (pb == null) { play = PlayUi.Message("Playback is not available in this build."); return }
        if (isTv && en != null && sn != null) {
            val e = eps.firstOrNull { it.number == en }
            if (e != null && !DetailsLogic.isAired(e.airDate, java.time.LocalDate.now().toString())) { play = PlayUi.Message("${DetailsLogic.episodeCode(sn, en)} has not aired yet."); return }
            lastPlayedEp = en; memory.remember(sn, en)
        }
        if (stream != null) { play = PlayUi.Playing(stream, sn, en); return }
        play = PlayUi.Resolving
        scope.launch {
            play = when (val r = pb.resolve(det.imdbId, sn, en)) {
                PlaybackService.Resolution.NeedsCode -> PlayUi.Message("UltraMax is not connected. Enter your code in Settings → Connections → UltraMax.")
                PlaybackService.Resolution.NoImdbId -> PlayUi.Message("TMDB has no IMDb id for this title, so UltraMax cannot be asked for sources.")
                PlaybackService.Resolution.NoSources -> PlayUi.Message(if (isTv) "UltraMax has no sources for this episode." else "UltraMax has no sources for this movie.")
                is PlaybackService.Resolution.Failed -> PlayUi.Message(r.error.message())
                is PlaybackService.Resolution.Sources -> {
                    resolvedStreams = SourcePicker.sorted(r.streams)
                    if (prefs.autoStart) SourcePicker.pick(r.streams, prefs.quality)?.let { PlayUi.Playing(it, sn, en) } ?: PlayUi.Message("UltraMax has no playable sources.")
                    else PlayUi.Choose(resolvedStreams, sn, en)
                }
            }
        }
    }
    fun toggleWatched(n: Int) { WatchKeys.episode(cur, season, n)?.let { k -> watch.setWatched(profile.id, k, !watch.isWatched(profile.id, k)); tick++ } }

    val detailChrome = remember(cur) { no.aura.os.v2.shell.ChromeScrollState() }
    val detailScroll = rememberLazyListState()
    LaunchedEffect(detailChrome, isTv, detailScroll.firstVisibleItemIndex, detailScroll.firstVisibleItemScrollOffset) {
        detailChrome.scrolled = !isTv && no.aura.os.v2.shell.ChromePolicy.isScrolled(detailScroll.firstVisibleItemIndex, detailScroll.firstVisibleItemScrollOffset)
    }
    val dismiss: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) else onClose() }
    CanvasDialog(dismiss, fullScreen = true) {
        // Sizes come from the window this dialog really got (canvas units after CanvasDialog re-applies the 1920-wide scale), not from an assumed 1080.
        BoxWithConstraints(Modifier.fillMaxSize().background(AuraColors.Background)) {
            val heroHeight = DetailsLayout.heroHeightDp(maxHeight.value)
            val viewportPx = constraints.maxWidth to constraints.maxHeight
            SideEffect {
                no.aura.os.v2.diag.Diag.gauge("details.viewport.widthPx", viewportPx.first.toLong()); no.aura.os.v2.diag.Diag.gauge("details.viewport.heightPx", viewportPx.second.toLong())
                no.aura.os.v2.diag.Diag.gauge("details.viewport.heightCanvas", maxHeight.value.toLong()); no.aura.os.v2.diag.Diag.gauge("details.hero.heightCanvas", heroHeight.toLong())
            }
            when (val s = ui) {
                is DetailsUi.Ready -> {
                    val det = s.d
                    val inList = run { tick; watch.inWatchlist(profile.id, WatchKeys.title(cur)) }
                    val favorite = run { tick; watch.inFavorites(profile.id, WatchKeys.title(cur)) }
                    val sections = DetailsLayout.sections(cur.type, tab)
                    // One vertically scrolling list for the whole page (hero is an item, not the viewport). Focused children are brought into view by
                    // the lazy list; DPAD UP/DOWN walks hero → tabs → content rows; horizontal rows scroll on their own.
                    if (isTv) {
                        val fractions = remember(tick, profile.id, cur, season, play) { progress?.episodeFractions(profile.id, cur, season).orEmpty() }
                        CompositionLocalProvider(no.aura.os.v2.shell.LocalChromeScroll provides detailChrome) {
                        CinemaSeriesPage(det, season, eps, { epState.focused }, episodes == null, episodesError?.message(),
                            watched = ::watchedEp, fractions = fractions, primary = primary, playFocus = heroPlay, episodeFocus = epFocus,
                            status = when(val p=play) { PlayUi.Resolving -> "Looking for sources…"; is PlayUi.Message -> p.text; else -> status.orEmpty() },
                            inWatchlist = inList, favorite = favorite,
                            onSelect = { n -> epState.focused = n; memory.remember(season,n) }, onSeason = { season = it },
                            onPlay = { n -> start(null,season,n) }, onToggle = ::toggleWatched,
                            onWatchlist = { watch.setWatchlist(profile.id,WatchKeys.title(cur),!inList); tick++ },
                            onFavorite = { watch.setFavorite(profile.id,WatchKeys.title(cur),!favorite); tick++ }, onRetry = { attempt++ },
                            belowEpisodes = {
                                Text("Cast & related",style=AuraTypography.PageTitle)
                                PeopleRow("Series Cast",det.cast,"TMDB lists no cast for this title.",roleOnTop=false)
                                DetailsTabs(DetailsTab.forType(MediaType.TV),tab,{tab=it})
                                when(tab) {
                                    DetailsTab.LATEST -> LatestTvTab(profile,service) { stack=stack+it }
                                    DetailsTab.RECOMMEND -> RelatedTab(det.recommendations,"TMDB has no recommendations for this title.",null,profile,service) { stack=stack+it }
                                    else -> RelatedTab(det.similar,"TMDB lists no related titles.","Related titles from TMDB — not personalised to you.",profile,service) { stack=stack+it }
                                }
                            })
                        }
                    } else LazyColumn(Modifier.fillMaxSize(), state = detailScroll, contentPadding = PaddingValues(top = 0.dp, bottom = 92.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item(key = "hero") {
                            HeroSection(
                                heroHeight, det, cur, isTv, eps, season, epState, inList, favorite, watchedEp = ::watchedEp, heroPlay = heroPlay,
                                statusText = when (val p = play) { PlayUi.Resolving -> "Looking for sources…"; is PlayUi.Message -> p.text; else -> status ?: "" },
                                onPlayEp = { n -> start(null, season, n) }, onPlayMovie = { start(null, null, null) }, onNoEpisode = { play = PlayUi.Message("No episode is available to play.") },
                                onWatchlist = { watch.setWatchlist(profile.id, WatchKeys.title(cur), !inList); tick++ },
                                onFavorite = { watch.setFavorite(profile.id, WatchKeys.title(cur), !favorite); tick++ }, onToggleWatched = ::toggleWatched,
                                onTrailer = { key -> status = if (trailer?.invoke(key) == true) null else "The trailer needs the YouTube app, which isn't available on this device." },
                            )
                        }
                        item(key = "tabs") {
                            Box(Modifier.padding(horizontal = 96.dp)) { DetailsTabs(DetailsTab.forType(cur.type), tab, { tab = it }, firstTab = Modifier.focusRequester(primary)) }
                        }
                        item(key = "title-languages") {
                            Text(DetailsLogic.languageLabel(det), style = AuraTypography.Metadata,
                                modifier = Modifier.padding(horizontal = 96.dp))
                        }
                        sections.forEach { sec ->
                            item(key = sec.key) {
                                Box(Modifier.padding(horizontal = 96.dp)) {
                                    when (sec) {
                                        DetailsSection.CAST -> PeopleRow("Cast", det.cast, "TMDB lists no cast for this title.", roleOnTop = false)
                                        DetailsSection.CREW -> PeopleRow("Creators / Producers", det.crew, "TMDB lists no crew for this title.", roleOnTop = true)
                                        DetailsSection.ADVISORY -> Text("Content advisory unavailable — no verified advisory source is connected.", style = AuraTypography.Label.copy(color = AuraColors.TextMuted))
                                        DetailsSection.SEASONS -> Unit // Series use Cinema only.
                                        DetailsSection.RELATED ->
                                            if (tab == DetailsTab.LATEST) LatestTvTab(profile,service) { stack=stack+it }
                                            else if (tab == DetailsTab.RECOMMEND) RelatedTab(det.recommendations, "TMDB has no recommendations for this title.", null, profile, service) { stack = stack + it }
                                            else RelatedTab(det.similar, "TMDB lists no related titles for this title.", "Related titles from TMDB — not personalised to you.", profile, service) { stack = stack + it }
                                    }
                                }
                            }
                        }
                        item(key = "credit") { Text("Data and artwork: TMDB. Sources: UltraMax.", style = AuraTypography.Label.copy(color = AuraColors.TextMuted), modifier = Modifier.padding(horizontal = 96.dp)) }
                    }
                }
                DetailsUi.Loading -> Centered("Loading details…", null, primary, "Back", null, dismiss)
                DetailsUi.Blocked -> Centered("Not available on this profile", "This title is not allowed by this profile's content restrictions.", primary, "Back", null, dismiss)
                is DetailsUi.Failed -> Centered("Could not load details", s.e.message(), primary, "Retry", { attempt++ }, dismiss)
            }
            // Pinned primary navigation inside the full-screen dialog window; unlike the former Back-only page,
            // this remains available without leaving the Details view. Modal sub-dialogs still trap focus.
            if (play !is PlayUi.Playing && topNavigation != null) {
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()) { CompositionLocalProvider(no.aura.os.v2.shell.LocalChromeScroll provides detailChrome) { topNavigation(primary) } }
            } else if (onNavigate != null && play !is PlayUi.Playing) {
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .padding(start = 116.dp, end = 96.dp, top = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("AURA", style = AuraTypography.SectionTitle.copy(color = AuraColors.Focus),
                        modifier = Modifier.padding(end = 16.dp))
                    listOf(Route.HOME, Route.MOVIES, Route.TV_SHOWS, Route.SPORTS, Route.LIVE_TV).forEach { destination ->
                        PillItem(destination.title, false) { onNavigate(destination) }
                    }
                    Spacer(Modifier.weight(1f))
                    PillItem("Close details", false) { onClose() }
                }
            }
            (play as? PlayUi.Choose)?.let { c ->
                CanvasDialog({ play = PlayUi.Idle }) {
                    Column(Modifier.width(560.dp).clip(RoundedCornerShape(AuraDimensions.RadiusLargeCard)).background(AuraColors.Panel).padding(28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Choose a source", style = AuraTypography.SectionTitle)
                        c.streams.take(6).forEachIndexed { i, st -> PillItem("${st.quality.label}  ·  ${st.label}".take(60), false, if (i == 0) Modifier.focusRequester(primary) else Modifier) { start(st, c.season, c.episode) } }
                        PillItem("Cancel", false) { play = PlayUi.Idle }
                    }
                }
            }
        }
    }
    (play as? PlayUi.Playing)?.let { p ->
        val sources = resolvedStreams.mapIndexed { index, stream -> no.aura.os.v2.playback.PlayerSource("source:$index", "${stream.quality.label} · ${stream.label}", stream.playUrl()) }
        VideoPlayerHost(d?.title ?: "", if (p.season != null && p.episode != null) DetailsLogic.episodeCode(p.season, p.episode) else d?.year, p.stream.playUrl(), prefs,
            metadata = no.aura.os.v2.playback.PlaybackMetadata(
                artwork = Artwork(imageUrl = TmdbImages.url(TmdbImages.POSTER, d?.posterPath ?: d?.backdropPath)),
                certification = d?.certification, ratingSource = "TMDB",
                backdropArtwork = Artwork(imageUrl = TmdbImages.url(TmdbImages.HERO, eps.firstOrNull { it.number == p.episode }?.stillPath ?: d?.backdropPath ?: d?.posterPath)),
            ),
            onClose = { play = PlayUi.Idle },
            onProgress = { position, duration ->
                val detail = d
                if (detail != null) progress?.record(profile.id, cur, detail.title,
                    TmdbImages.url(TmdbImages.HERO, detail.backdropPath), p.season, p.episode, position, duration)
            }, startPositionMs = p.resumeOverride ?: (progress?.resumePosition(profile.id, cur, p.season, p.episode) ?: 0L),
            alternatives = sources, onSourceSelected = { choice, position ->
                val index = sources.indexOfFirst { it.key == choice.key && it.url == choice.url }
                resolvedStreams.getOrNull(index)?.let { stream -> play = PlayUi.Playing(stream, p.season, p.episode, position) }
            })
    }
    // Initial focus = hero Play; after the player closes, focus returns to the episode that was played (else hero Play).
    LaunchedEffect(ready != null, cur, play is PlayUi.Idle) {
        if (play is PlayUi.Idle || play is PlayUi.Message || ui !is DetailsUi.Ready) {
            withFrame { }
            val target = lastPlayedEp?.takeIf { isTv }?.let { epFocus[it] }
            runCatching { if (ui is DetailsUi.Ready) (target ?: heroPlay).requestFocus() else primary.requestFocus() }
        }
    }
}

private suspend fun withFrame(block: () -> Unit) = androidx.compose.runtime.withFrameNanos { block() }

/** Focused episode number shared with leaf composables only: the host never reads [focused], so a focus change does not recompose the whole page. */
@Stable
private class EpState { var focused by mutableStateOf<Int?>(null) }

@Composable
private fun PeopleRow(title: String, people: List<TmdbPerson>, empty: String, roleOnTop: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = AuraTypography.SectionTitle)
        if (people.isEmpty()) Text(empty, style = AuraTypography.Metadata)
        else LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(people, key = { it.id.toString() + it.role }) { PersonCard(it, roleOnTop) }
        }
    }
}

@Composable
private fun HeroSection(
    heightDp: Int, det: TmdbDetails, cur: TitleRef, isTv: Boolean, eps: List<TmdbEpisode>, season: Int, epState: EpState, inList: Boolean, favorite: Boolean, watchedEp: (Int) -> Boolean,
    heroPlay: FocusRequester, statusText: String, onPlayEp: (Int) -> Unit, onPlayMovie: () -> Unit, onNoEpisode: () -> Unit,
    onWatchlist: () -> Unit, onFavorite: () -> Unit, onToggleWatched: (Int) -> Unit, onTrailer: (String) -> Unit,
) {
    SideEffect { no.aura.os.v2.diag.Diag.count("details.hero.recompose") }
    val reduce = LocalReduceMotion.current
    val focusedEp = epState.focused
    val targetEp: TmdbEpisode? = eps.firstOrNull { it.number == focusedEp } ?: DetailsLogic.defaultEpisode(eps, watchedEp)?.let { n -> eps.firstOrNull { it.number == n } }
    Box(Modifier.fillMaxWidth().height(heightDp.dp).background(AuraColors.Background)) {
        Box(Modifier.fillMaxSize().padding(top = no.aura.os.v2.hero.HeroSpec.ArtworkTop).clip(androidx.compose.ui.graphics.RectangleShape)) {
            ArtworkImage(Artwork(imageUrl = TmdbImages.url(TmdbImages.HERO, det.backdropPath)),
                Modifier.align(Alignment.TopEnd).fillMaxHeight().fillMaxWidth(0.68f), alignment = Alignment.TopCenter)
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.25f to AuraColors.Background, 0.6f to AuraColors.Background.copy(alpha = 0.80f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to AuraColors.Background)))
        Column(Modifier.fillMaxSize().padding(start = 96.dp, end = 96.dp, top = 112.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(4.dp).height(24.dp).background(AuraColors.BrandOrange))
                Text("AURA BOX", style = AuraTypography.BrandLabel)
            }
            Spacer(Modifier.height(8.dp))
            no.aura.os.v2.hero.HeroTitleText(det.title,width=900.dp,height=64.dp,maxFont=60,minFont=20)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                KindChip(cur.type.label)
                val meta = DetailsLogic.metaParts(det)
                if (meta.isNotEmpty()) Text(meta.joinToString("  ·  "), style = AuraTypography.Metadata)
                if (det.genres.isNotEmpty()) Text(det.genres.take(3).joinToString(" · "), style = AuraTypography.Metadata.copy(color = AuraColors.TextMuted), maxLines = 1)
            }
            Spacer(Modifier.height(8.dp))
            // Fixed-height block: series overview <-> focused-episode info crossfade without moving anything below.
            Box(Modifier.height(DetailsLayout.HERO_TEXT_BLOCK_DP.dp).widthIn(max = 820.dp)) {
                val heroEp = if (isTv) eps.firstOrNull { it.number == focusedEp } else null
                Crossfade(heroEp, animationSpec = tween(if (reduce) 0 else 160), label = "heroEp") { e ->
                    if (e == null) Text(det.overview.ifBlank { "No description is available for this title." }, style = AuraTypography.Body, color = AuraColors.TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    else Column {
                        Text(DetailsLogic.episodeCode(season, e.number) + "  ·  " + e.name + (DetailsLogic.runtimeLabel(e.runtimeMinutes)?.let { "  ·  $it" } ?: "") + if (watchedEp(e.number)) "  ·  Watched" else "",
                            style = AuraTypography.CardTitle.copy(color = AuraColors.TextPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(e.overview.ifBlank { "No description is available for this episode." }, style = AuraTypography.Body, color = AuraColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                val anyWatched = isTv && eps.any { watchedEp(it.number) }
                val label = DetailsLogic.playLabel(cur.type, anyWatched) + if (isTv && targetEp != null) "  ${DetailsLogic.episodeCode(season, targetEp.number)}" else ""
                HeroButton(label, true, Modifier.focusRequester(heroPlay)) { if (!isTv) onPlayMovie() else if (targetEp != null) onPlayEp(targetEp.number) else onNoEpisode() }
                HeroButton(DetailsLogic.watchlistLabel(inList), false, onClick = onWatchlist)
                HeroButton(if (favorite) "♥ Favorited" else "♡ Favorite", false, onClick = onFavorite)
                if (isTv) targetEp?.let { te -> HeroButton(DetailsLogic.mark(watchedEp(te.number)), false) { onToggleWatched(te.number) } }
                else det.trailerKey?.let { key -> HeroButton("Trailer", false) { onTrailer(key) } }
            }
            Text(statusText, style = AuraTypography.Metadata, color = AuraColors.TextSecondary, maxLines = 1, modifier = Modifier.padding(top = 6.dp).height(22.dp))
        }
    }
}

@Composable
private fun LatestTvTab(profile: Profile, service: RealCatalogService, onOpen: (TitleRef)->Unit) {
    var result by remember(profile) { mutableStateOf<TmdbResult<List<no.aura.os.v2.data.MediaItem>>?>(null) }
    var attempt by remember(profile) { mutableIntStateOf(0) }
    LaunchedEffect(profile, attempt) { result=null; result=service.latestTv(profile) }
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Latest TV Shows",style=AuraTypography.SectionTitle)
        Text("Newest series premieres listed by TMDB — availability depends on your provider.",style=AuraTypography.Metadata)
        when(val value=result) {
            null -> Text("Loading latest series…",style=AuraTypography.Metadata)
            is TmdbResult.Failure -> {
                Text(value.error.message(),style=AuraTypography.Metadata)
            }
            is TmdbResult.Ok -> if(value.value.isEmpty()) Text("No latest series available for this profile.",style=AuraTypography.Metadata)
                else LazyRow(horizontalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(vertical=8.dp)) {
                    items(value.value,key={it.id}) { item -> PosterCard(item) { TitleRef.parse(item.id)?.let(onOpen) } }
                }
        }
        // One stable action survives loading/error/results, retaining remote focus after retry.
        HeroButton(when(result) { null -> "Loading latest series…"; is TmdbResult.Failure -> "Retry latest series"; else -> "Refresh latest series" }, false) {
            if(result!=null) attempt++
        }
    }
}

@Composable
private fun RelatedTab(src: List<TmdbTitle>, empty: String, note: String?, profile: Profile, service: RealCatalogService, onOpen: (TitleRef) -> Unit) {
    var rel by remember(src, profile.id) { mutableStateOf<Rel>(Rel.Loading) }
    LaunchedEffect(src, profile.id) { rel = Rel.Ok(service.related(src, profile)) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        note?.let { Text(it, style = AuraTypography.Metadata) }
        when (val r = rel) {
            Rel.Loading -> Text("Loading…", style = AuraTypography.Metadata)
            is Rel.Ok -> if (r.items.isEmpty()) Text(empty, style = AuraTypography.Metadata)
            else LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(r.items, key = { it.id }) { m -> PosterCard(m) { TitleRef.parse(m.id)?.let(onOpen) } }
            }
        }
    }
}

@Composable
private fun Centered(title: String, body: String?, primary: FocusRequester, closeLabel: String, onPrimary: (() -> Unit)?, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = AuraTypography.SectionTitle)
        body?.let { Text(it, style = AuraTypography.Body, color = AuraColors.TextSecondary, modifier = Modifier.padding(top = 10.dp).widthIn(max = 720.dp)) }
        Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            HeroButton(closeLabel, true, Modifier.focusRequester(primary), onClick = onPrimary ?: onClose)
            if (onPrimary != null) HeroButton("Back", false, onClick = onClose)
        }
    }
}
