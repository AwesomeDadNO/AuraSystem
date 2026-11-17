package no.aura.os.v2.data

import no.aura.os.v2.brand.BrandLabelSpec

import androidx.compose.runtime.Immutable

/** Where content comes from. Demo = labelled fixtures, Real = later providers behind the same repository interfaces. */
enum class DataMode { DEMO, REAL }

/** Neutral gradient fallback (no silhouettes). Colours are ARGB longs so the model stays platform-free. */
@Immutable data class DemoArt(val top: Long, val bottom: Long)

/**
 * Artwork reference: a bundled drawable name ([asset], Demo Mode), a remote image (Real Mode, later) or a neutral gradient.
 * Bundled demo assets are interim colour fields derived from the approved mockups until dedicated artwork exists.
 */
@Immutable data class Artwork(val imageUrl: String? = null, val demo: DemoArt? = null, val asset: String? = null)

@Immutable data class MediaItem(val id: String, val title: String, val artwork: Artwork, val subtitle: String? = null, val twoLine: Boolean = false, val focusArtwork: Artwork? = null, val overview: String? = null, val metadata: List<String> = emptyList())

/** Continue-watching entry. [progress] 0..1 must come from real watched progress once integrated (demo: fixture). */
@Immutable data class ContinueItem(val item: MediaItem, val progress: Float, val remainingLabel: String, val showProgress: Boolean = true)

enum class HeroIcon { INFO, PLAY, QUEUE, RESUME, TRAILER, ADD, CHECK, OPEN }

/** A hero button. Exactly one action per hero is [primary] (white fill). */
@Immutable data class HeroAction(val id: String, val label: String, val icon: HeroIcon, val primary: Boolean = false)

val DefaultHeroActions = listOf(HeroAction("play", "Play", HeroIcon.PLAY, primary = true), HeroAction("details", "More details", HeroIcon.INFO))

@Immutable data class HeroEntry(
    val id: String, val sectionLabel: BrandLabelSpec, val title: String, val description: String,
    val ratingLabel: String, val metadata: List<String>, val artwork: Artwork,
    val subtitle: String? = null, val actions: List<HeroAction> = DefaultHeroActions,
)

@Immutable data class PlayerTrack(val id: String, val title: String, val artist: String, val durationSec: Int, val artwork: Artwork)

/** One shelf of a movie/series style page. [continueStyle] shelves show progress + remaining time. */
@Immutable data class Shelf(val id: String, val title: String, val items: List<ContinueItem>, val continueStyle: Boolean, val poster: Boolean = false)

/** Hero + shelves page content (Home, Movies, TV Shows). */
@Immutable data class CategoryCard(val id: String, val title: String)
@Immutable data class ShelfPageContent(val hero: List<HeroEntry>, val shelves: List<Shelf>, val categories: List<CategoryCard> = emptyList())

// ---- AuraBox -------------------------------------------------------------------------------------------------------------
@Immutable data class CastMember(val id: String, val name: String, val role: String, val artwork: Artwork)
@Immutable data class SourceCard(val id: String, val label: String, val highlighted: Boolean = false)
@Immutable data class AuraBoxContent(
    val hero: HeroEntry, val poster: Artwork, val metadata: List<String>, val resumeProgress: Float,
    val availableOn: List<String>, val cast: List<CastMember>, val moreLikeThis: List<ContinueItem>, val sources: List<SourceCard>,
)

// ---- AuraAmp -------------------------------------------------------------------------------------------------------------
@Immutable data class MusicCard(val id: String, val title: String, val artist: String?, val artwork: Artwork, val progress: Float? = null, val subtitle: String? = null)
@Immutable data class MusicShelf(val id: String, val title: String, val items: List<MusicCard>)
@Immutable data class AmpContent(val hero: HeroEntry, val tabs: List<String>, val shelves: List<MusicShelf>)

// ---- Sports (all values illustrative) --------------------------------------------------------------------------------------
enum class MatchState { LIVE, FINAL, UPCOMING }
@Immutable data class Match(
    val id: String, val state: MatchState, val badge: String, val home: String, val away: String,
    val homeScore: Int? = null, val awayScore: Int? = null, val footer: String, val league: String? = null,
)
@Immutable data class FollowChip(val id: String, val name: String, val status: String, val live: Boolean = false)
@Immutable data class MatchGroup(val id: String, val title: String, val matches: List<Match>)
@Immutable data class SportsHero(val label: BrandLabelSpec, val title: String, val chips: List<String>, val homeName: String, val awayName: String, val score: String, val clock: String, val actions: List<HeroAction>)
@Immutable data class SportsContent(val hero: SportsHero, val following: List<FollowChip>, val groups: List<MatchGroup>, val upcoming: List<Match>)

// ---- Live TV (fixed demo timeline) ------------------------------------------------------------------------------------------
@Immutable data class Channel(val id: String, val brand: String, val name: String, val category: String, val number: Int, val artwork: Artwork)
/** Block position as fractions (0..1) of the guide's time grid. */
@Immutable data class Program(val id: String, val channelId: String, val title: String, val start: Float, val end: Float, val live: Boolean = false)
@Immutable data class LiveTvContent(
    val hero: HeroEntry, val channels: List<Channel>, val programs: List<Program>,
    val timeLabels: List<Pair<String, Float>>, val nowFraction: Float, val dateLabel: String, val filters: List<String>,
)

/** Everything the demo shows. Real providers (TMDB, UltraMax, ...) would later fill the same structure. */
@Immutable data class DemoCatalog(
    val mode: DataMode, val home: ShelfPageContent, val movies: ShelfPageContent, val tvShows: ShelfPageContent,
    val auraBox: AuraBoxContent, val auraAmp: AmpContent, val sports: SportsContent, val liveTv: LiveTvContent,
    val queue: List<PlayerTrack>, val apps: List<String>,
)

/** Replaceable provider of demo/real content. */
interface DemoRepository { suspend fun load(): DemoCatalog }
