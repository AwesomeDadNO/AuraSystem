package no.aura.os.v2.details

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.*
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import no.aura.os.v2.hero.heroFocusable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import no.aura.os.v2.cards.auraGlass
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import no.aura.os.v2.shell.rememberNavigationInteraction
import no.aura.os.v2.shell.auraNavigationFocusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import no.aura.os.v2.overlay.CanvasDialog
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.data.Artwork
import no.aura.os.v2.demo.ArtworkImage
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.AuraDimensions
import no.aura.os.v2.theme.AuraTypography
import no.aura.os.v2.tmdb.TmdbEpisode
import no.aura.os.v2.tmdb.TmdbImages
import no.aura.os.v2.tmdb.TmdbPerson

/** Hero actions use the fixed neutral function-button fill and shared focus style. */
@Composable
internal fun HeroButton(label: String, primary: Boolean, modifier: Modifier = Modifier, onFocused: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(AuraDimensions.RadiusButton)
    Text(label, style = AuraTypography.Button, color = if (primary) Color.White else AuraColors.ButtonInactiveIcon, maxLines = 1,
        modifier = modifier.auraFocusable(shape, onFocused = onFocused, onClick = onClick).clip(shape)
            .then(if (primary) Modifier.background(AuraColors.OrangeGradient) else Modifier.background(AuraColors.ButtonInactiveBg))
            .padding(horizontal = 26.dp, vertical = 12.dp))
}

/** Outlined, non-interactive kind chip (Movie / Series). */
@Composable
internal fun KindChip(text: String) {
    Text(text, style = AuraTypography.Label.copy(color = AuraColors.TextPrimary), maxLines = 1,
        modifier = Modifier.border(1.dp, AuraColors.Border, RoundedCornerShape(AuraDimensions.RadiusBadge)).padding(horizontal = 10.dp, vertical = 3.dp))
}

/** Small pill used for the source chooser / season list entries. */
@Composable
internal fun PillItem(text: String, selected: Boolean, modifier: Modifier = Modifier, onFocused: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Text(text, style = AuraTypography.Button.copy(color = if (selected) AuraColors.TextPrimary else AuraColors.TextSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.auraFocusable(shape, scale = 1.03f, onFocused = onFocused, onClick = onClick).clip(shape)
            .background(if (selected) AuraColors.NavPill else AuraColors.Surface).padding(horizontal = 20.dp, vertical = 12.dp))
}

/** Tab row: focus selects the tab (so DPAD LEFT/RIGHT switches content), orange underline marks the active one. */
@Composable
internal fun DetailsTabs(tabs: List<DetailsTab>, selected: DetailsTab, onSelect: (DetailsTab) -> Unit, firstTab: Modifier = Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
        tabs.forEachIndexed { i, t ->
            val active = t == selected
            val interaction = rememberNavigationInteraction()
            Column(
                (if (i == 0) firstTab else Modifier).onFocusChanged { if(it.isFocused) onSelect(t) }.auraNavigationFocusable(interaction, active, onClick = { onSelect(t) }).padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(t.label, style = AuraTypography.SectionTitle.copy(color = AuraColors.TextPrimary))
                Spacer(Modifier.height(6.dp))
                Spacer(Modifier.height(3.dp))
            }
        }
    }
}

/**
 * Cast/crew card. Portrait from TMDB (w185) or an initial when none exists. Cast: name + character; crew: the real job on top, then name.
 * The caller decides which list a person came from, so cast is never shown under a producer label.
 */
@Composable
internal fun PersonCard(p: TmdbPerson, roleOnTop: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.width(150.dp).clip(shape).auraFocusable(shape, scale = 1.04f, onClick = {}), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(150.dp, 200.dp).clip(shape).background(AuraColors.Surface), contentAlignment = Alignment.Center) {
            val url = TmdbImages.url(TmdbImages.PROFILE, p.profilePath)
            if (url != null) ArtworkImage(Artwork(imageUrl = url), Modifier.fillMaxSize())
            else Text(p.name.take(1).uppercase(), style = AuraTypography.PageTitle.copy(color = AuraColors.TextMuted))
        }
        if (roleOnTop && p.role.isNotBlank()) Text(p.role, style = AuraTypography.Label.copy(color = AuraColors.Focus), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(p.name, style = AuraTypography.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!roleOnTop && p.role.isNotBlank()) Text(p.role, style = AuraTypography.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Episode tile: clean 16:9 still, text over the image, 80/20 info and watch controls below,
 * and a real synopsis dialog. All focusable actions are accessible by DPAD independently.
 */
@Composable
internal fun EpisodeCard(
    ep: TmdbEpisode, watched: Boolean, playFocus: androidx.compose.ui.focus.FocusRequester,
    onSelected: (Int) -> Unit, onPlay: (Int) -> Unit, onToggleWatched: (Int) -> Unit,
    upFocus: FocusRequester = FocusRequester.Default,
) {
    SideEffect { no.aura.os.v2.diag.Diag.count("details.episodeCard.recompose") }
    val n = ep.number
    val shape = RoundedCornerShape(10.dp)
    var infoOpen by remember { mutableStateOf(false) }
    val infoFocus = remember { FocusRequester() }
    val watchedFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val dismissInfo: () -> Unit = {
        infoOpen = false
        scope.launch {
            withFrameNanos { }
            runCatching { infoFocus.requestFocus() }
        }
    }
    val imageWidth = EpisodeActionGeometry.WIDTH.dp
    Column(Modifier.width(imageWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().height(187.dp).focusRequester(playFocus)
                .focusProperties { up = upFocus; down = infoFocus }
                .auraFocusable(shape, scale = 1.035f, elevation = 3.dp,
                    onFocused = { onSelected(n) }, onClick = { onSelected(n); onPlay(n) })
                .clip(shape).auraGlass(shape),
        ) {
            val url = TmdbImages.url(TmdbImages.STILL, ep.stillPath)
            if (url != null) ArtworkImage(Artwork(imageUrl = url), Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize().background(AuraColors.Surface))
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent,
                    0.47f to Color.Black.copy(alpha = 0.35f),
                    1f to Color.Black.copy(alpha = 0.9f))))
            Text("AURA", style = AuraTypography.BrandLabel.copy(fontSize = 13.sp, color = AuraColors.TextPrimary),
                modifier = Modifier.align(Alignment.TopStart).padding(13.dp))
            AuraPlayGlyph(Modifier.align(Alignment.TopEnd).padding(10.dp).size(54.dp))
            Column(
                Modifier.align(Alignment.BottomStart).padding(horizontal = 13.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("EPISODE $n" +
                    (DetailsLogic.runtimeLabel(ep.runtimeMinutes)?.let { "  ·  $it" } ?: "") +
                    if (watched) "  ·  WATCHED" else "",
                    style = AuraTypography.Label.copy(color = AuraColors.Focus), maxLines = 1)
                Text(ep.name, style = AuraTypography.CardTitle, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth().height(EpisodeActionGeometry.HEIGHT.dp)) {
            EpisodeMoreInfo(Modifier.weight(EpisodeActionGeometry.INFO_WEIGHT)
                .focusRequester(infoFocus).focusProperties { up = playFocus; right = watchedFocus }) {
                onSelected(n)
                infoOpen = true
            }
            EpisodeWatchCheckbox(watched, n, Modifier.weight(EpisodeActionGeometry.WATCH_WEIGHT)
                .focusRequester(watchedFocus).focusProperties { up = playFocus; left = infoFocus }) {
                onSelected(n); onToggleWatched(n)
            }
        }
    }
    if (infoOpen) {
        CanvasDialog(onDismissRequest = dismissInfo) {
            val closeFocus = remember { FocusRequester() }
            val contentFocus = remember { FocusRequester() }
            val infoScroll = rememberScrollState()
            LaunchedEffect(Unit) {
                withFrameNanos { }
                runCatching { closeFocus.requestFocus() }
            }
            Column(
                Modifier.width(660.dp).heightIn(max = 720.dp).clip(RoundedCornerShape(10.dp))
                    .background(AuraColors.BackgroundDeep)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Focus lives on a stationary viewport; only its content scrolls.
                Column(Modifier.fillMaxWidth().weight(1f, fill = false)
                    .focusRequester(contentFocus)
                    .focusProperties { up = closeFocus; down = closeFocus }
                    .onPreviewKeyEvent { event ->
                        val direction = when (event.key) {
                            Key.DirectionDown -> 1
                            Key.DirectionUp -> -1
                            else -> 0
                        }
                        if (direction == 0) false
                        else {
                            if (event.type == KeyEventType.KeyDown) {
                                val atEdge = if (direction < 0) infoScroll.value == 0 else infoScroll.value == infoScroll.maxValue
                                if (atEdge) closeFocus.requestFocus()
                                else scope.launch { infoScroll.scrollBy(direction * 180f) }
                            }
                            true
                        }
                    }
                    .semantics { contentDescription = "Episode details. Use Up and Down to scroll." }
                    .focusable()
                    .verticalScroll(infoScroll),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TmdbImages.url(TmdbImages.STILL, ep.stillPath)?.let { url ->
                        ArtworkImage(Artwork(imageUrl = url), Modifier.fillMaxWidth().height(300.dp).clip(shape))
                    }
                    Text("Episode $n · ${ep.name}", style = AuraTypography.SectionTitle)
                    val metadata = listOfNotNull(
                        ep.airDate?.takeIf { it.isNotBlank() }?.let { "Air date: $it" },
                        DetailsLogic.runtimeLabel(ep.runtimeMinutes),
                        if (watched) "Watched" else "Not marked watched",
                    )
                    Text(metadata.joinToString("  ·  "), style = AuraTypography.Metadata)
                    Text(ep.overview.ifBlank { "No synopsis is available for this episode." },
                        style = AuraTypography.Body, color = AuraColors.TextSecondary)
                }
                HeroButton("Close", true, Modifier.focusRequester(closeFocus)
                    .focusProperties { up = contentFocus; down = contentFocus }, onClick = dismissInfo)
            }
        }
    }
}

/** Decorative glyph inside the episode image's single focus target. */
@Composable
private fun AuraPlayGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width * 0.5f, size.height * 0.5f)
        drawCircle(Brush.radialGradient(listOf(AuraColors.Focus.copy(alpha = 0.7f), Color.Transparent), center, size.minDimension * 0.5f))
        val play = Path().apply {
            moveTo(size.width * 0.36f, size.height * 0.24f)
            lineTo(size.width * 0.77f, size.height * 0.5f)
            lineTo(size.width * 0.36f, size.height * 0.76f)
            close()
        }
        drawPath(play, AuraColors.PrimaryAction)
    }
}

/** Boxless season line with independent focus underline. */
@Composable
internal fun SeasonTab(text: String, selected: Boolean, onFocused: () -> Unit, onClick: () -> Unit) {
    val interaction = rememberNavigationInteraction()
    Text(text, style = AuraTypography.Button, color = AuraColors.TextPrimary,
        modifier = Modifier.onFocusChanged { if(it.isFocused) onFocused() }.auraNavigationFocusable(interaction, selected, onClick = onClick).padding(horizontal = 10.dp, vertical = 12.dp))
}

/** Fractions apply to the whole usable row: no spacing subtracts from the 80/20 split. */
internal object EpisodeActionGeometry {
    const val WIDTH = 332f
    const val HEIGHT = 48f
    const val INFO_WEIGHT = 8f
    const val WATCH_WEIGHT = 2f
    const val MIN_WATCH_WIDTH = 32f
    fun watchWidth(rowWidth: Float): Float = rowWidth * WATCH_WEIGHT / (INFO_WEIGHT + WATCH_WEIGHT)
}

@Composable
private fun EpisodeMoreInfo(modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.fillMaxHeight().auraFocusable(shape, onClick = onClick)
        .clip(shape).background(AuraColors.ButtonInactiveBg), contentAlignment = Alignment.Center) {
        Text("More info", style = AuraTypography.Button.copy(fontSize = 17.sp), color = AuraColors.ButtonInactiveIcon)
    }
}

@Composable
private fun EpisodeWatchCheckbox(watched: Boolean, episode: Int, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.fillMaxHeight().widthIn(min = EpisodeActionGeometry.MIN_WATCH_WIDTH.dp)
        .semantics {
            role = Role.Checkbox
            contentDescription = "Episode $episode watched"
            toggleableState = if (watched) ToggleableState.On else ToggleableState.Off
            stateDescription = if (watched) "Watched" else "Not marked watched"
        }
        .auraFocusable(shape, onClick = onClick)
        .clip(shape).background(AuraColors.ButtonInactiveBg), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Check, null, tint = if (watched) Color.White else AuraColors.ButtonInactiveIcon, modifier = Modifier.size(26.dp))
    }
}
