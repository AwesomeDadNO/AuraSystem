package no.aura.os.v2.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import no.aura.os.v2.cards.CompactCard
import no.aura.os.v2.data.MediaItem
import no.aura.os.v2.youtube.PublicYouTubeVideo
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import no.aura.os.v2.brand.BrandLabels
import no.aura.os.v2.data.Artwork
import no.aura.os.v2.data.DemoArt
import no.aura.os.v2.data.HeroAction
import no.aura.os.v2.data.HeroEntry
import no.aura.os.v2.data.HeroIcon
import no.aura.os.v2.hero.CinematicHero
import no.aura.os.v2.hero.HeroLayout
import no.aura.os.v2.hero.HeroSpec
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.AuraTypography

/** Action ids of the AuraTube landing hero. */
object AuraTubeActions { const val OPEN_YOUTUBE = "open_youtube"; const val STATUS = "connection_status" }

/** What the landing page says about YouTube data. Pure, so the honest "not configured" state is unit-tested. */
data class AuraTubeState(val keyPresent: Boolean, val verifiedPublic: Boolean = false) {
    val headline get() = if (verifiedPublic) "Public YouTube catalogue connected" else if (keyPresent) "Connection not verified yet" else "YouTube data isn't set up"
    val lines: List<String> get() = listOf(
        if (verifiedPublic) "Public YouTube videos are available. Google account data requires separate OAuth authorization." else if (keyPresent) "A developer API key is present, but AuraOS has not verified it against YouTube yet." else "This build has no YouTube API key, so videos, channels and playlists can't be loaded.",
        "Playback opens in the official YouTube app. AuraOS never extracts or re-hosts streams.",
    )
}

private val TubeArt = Artwork(demo = DemoArt(0xFF1B2433, 0xFF0A0F18))
private val TubeLayout = HeroLayout(height = 450.dp, labelTop = 100.dp, reservePrefixSlot = false)

/** AuraTube landing page: the shared cinematic hero plus an honest status panel. No videos are shown until real YouTube data exists (F4c). */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun AuraTubePage(state: AuraTubeState, entry: FocusRequester, onAction: (String) -> Unit, modifier: Modifier = Modifier,
    videos: List<PublicYouTubeVideo> = emptyList(), loading: Boolean = false, onVideo: (String) -> Unit = {}) {
    val hero = HeroEntry(
        id = "auratube", sectionLabel = BrandLabels.AURATUBE, title = "AuraTube",
        description = "Browse YouTube videos, channels and playlists from AuraOS and watch them in the YouTube app.",
        ratingLabel = "YouTube", metadata = listOf(state.headline), artwork = TubeArt,
        actions = listOf(
            HeroAction(AuraTubeActions.OPEN_YOUTUBE, "Open YouTube", HeroIcon.OPEN, primary = true),
            HeroAction(AuraTubeActions.STATUS, "Connection status", HeroIcon.INFO),
        ),
    )
    Column(modifier.fillMaxSize()) {
        CinematicHero(listOf(hero), 0, TubeLayout, entry, FocusRequester.Cancel, onActionFocused = {}, onAction = { _, a -> onAction(a.id) })
        Column(Modifier.padding(start = HeroSpec.TextStart, top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(state.headline, style = AuraTypography.SectionTitle)
            if (videos.isNotEmpty()) {
                Text("Popular on YouTube · Norway", style = AuraTypography.SectionTitle)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(videos, key = { it.id }) { video ->
                        CompactCard(MediaItem(video.id, video.title, Artwork(imageUrl = video.artworkUrl), subtitle = video.channel)) { onVideo(video.id) }
                    }
                }
                Text("Official YouTube Data API v3 · videos open in YouTube TV", style = AuraTypography.Metadata)
            } else if (loading) Text("Loading YouTube videos…", style = AuraTypography.Body)
            else state.lines.forEach { Text(it, style = AuraTypography.Body, color = AuraColors.TextSecondary, modifier = Modifier.widthIn(max = 900.dp)) }
        }
    }
}
