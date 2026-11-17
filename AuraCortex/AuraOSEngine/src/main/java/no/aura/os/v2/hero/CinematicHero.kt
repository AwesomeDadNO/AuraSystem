package no.aura.os.v2.hero

import no.aura.os.v2.brand.AuraBrandLabel

import no.aura.os.v2.cards.auraGlass
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.aura.os.v2.cards.auraFocusable
import no.aura.os.v2.cards.splitPrefix
import no.aura.os.v2.data.HeroAction
import no.aura.os.v2.data.HeroEntry
import no.aura.os.v2.data.HeroIcon
import no.aura.os.v2.focus.leftToRail
import no.aura.os.v2.focus.verticalLinks
import no.aura.os.v2.demo.ArtworkImage
import no.aura.os.v2.theme.LocalReduceMotion
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.AuraDimensions
import no.aura.os.v2.theme.AuraTypography

/** Hero geometry measured on the approved Home mockup (1920x1080 canvas units; y is measured from the top of the screen). */
object HeroSpec {
    val ArtworkTop = 0.dp        // art runs under overlaid OS navigation; shared with title details
    val Height = 525.dp            // Home hero item height; Continue Watching header centre sits at y=540
    val LabelTop = 117.dp          // Home "AURA Recommended" line (Movies 100, TV Shows 96 - see ShelfLayouts)
    val TextStart = 149.dp         // left edge of hero text (rail 94 + 55)
    val DescriptionWidth = 520.dp
    val PrefixIndent = 50.dp       // "THE" is indented relative to the title
}

/** "Silver Realm" -> large first letters, remaining letters in smaller caps. Pure, unit-tested. */
fun smallCapsTitle(title: String, small: Float = 0.78f): AnnotatedString = buildAnnotatedString {
    title.split(' ').forEachIndexed { i, word ->
        if (i > 0) append(' ')
        if (word.isNotEmpty()) {
            append(word.first().uppercaseChar())
            withStyle(SpanStyle(fontSize = AuraTypography.HeroTitle.fontSize * small)) { append(word.drop(1).uppercase()) }
        }
    }
}

/** Layout knobs that differ per page in the mockups (hero height and label position); everything else is shared. */
data class HeroLayout(val height: androidx.compose.ui.unit.Dp = HeroSpec.Height, val labelTop: androidx.compose.ui.unit.Dp = HeroSpec.LabelTop, val reservePrefixSlot: Boolean = true)

fun HeroIcon.vector(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    HeroIcon.INFO -> Icons.Outlined.Info; HeroIcon.PLAY -> Icons.Rounded.PlayArrow; HeroIcon.QUEUE -> Icons.Rounded.PlaylistAdd
    HeroIcon.RESUME -> Icons.Outlined.PlayCircleOutline; HeroIcon.TRAILER -> Icons.Outlined.Movie; HeroIcon.ADD -> Icons.Rounded.Add
    HeroIcon.CHECK -> Icons.Rounded.Check; HeroIcon.OPEN -> Icons.Outlined.OpenInNew
}

/**
 * The ONE shared hero for Home, Movies, TV Shows and AuraAmp. First item of the page's scrolling container, so it scrolls away with the content.
 * Navigation and rail are overlays above it; text positions are absolute canvas measurements so they match the mockups on any resolution.
 * Focus: the primary action receives [primaryRequester]; DPAD_DOWN from any action goes to [downTarget]; DPAD_LEFT from the first action enters the rail.
 */
@Composable
fun CinematicHero(
    entries: List<HeroEntry>, index: Int, layout: HeroLayout, primaryRequester: FocusRequester, downTarget: FocusRequester,
    onActionFocused: () -> Unit, onAction: (HeroEntry, HeroAction) -> Unit, modifier: Modifier = Modifier,
    onTrailer: ((HeroEntry) -> Unit)? = null,
) {
    if (entries.isEmpty()) return
    val e = entries[index.coerceIn(0, entries.lastIndex)]
    androidx.compose.runtime.SideEffect { no.aura.os.v2.diag.Diag.count("hero.recompose") }
    androidx.compose.runtime.LaunchedEffect(e.id) { no.aura.os.v2.diag.Diag.count("hero.transition") }
    Box(modifier.fillMaxWidth().height(layout.height).background(AuraColors.BackgroundDeep)) {
        Box(Modifier.fillMaxSize().padding(top = HeroSpec.ArtworkTop).clipToBounds()) {
            Crossfade(e.id, modifier = Modifier.fillMaxSize(), animationSpec = tween(if (LocalReduceMotion.current) 0 else 500), label = "heroArt") { id ->
                entries.firstOrNull { it.id == id }?.let { ArtworkImage(it.artwork, Modifier.fillMaxSize(), alignment = Alignment.TopCenter) }
            }
        }
        // left scrim for text legibility + bottom fade into the page background (two cheap gradients, no blur)
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to AuraColors.Background.copy(alpha = 0.70f), 0.5f to Color.Transparent)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.62f to Color.Transparent, 1f to AuraColors.Background)))
        Column(Modifier.padding(start = HeroSpec.TextStart, top = layout.labelTop)) {
            AuraBrandLabel(e.sectionLabel)
            Spacer(Modifier.height(20.dp))   // keeps the title position of the approved layout (former 6 + 2 + 12)
            HeroTitleText(e.title,height=if(layout.reservePrefixSlot) 122.dp else 88.dp)
            e.subtitle?.let { Text(it, style = AuraTypography.Body.copy(fontSize = 26.sp, lineHeight = 32.sp), maxLines = 1) }
            Spacer(Modifier.height(2.dp))
            Text(e.description, style = AuraTypography.Body.copy(color = AuraColors.TextPrimary.copy(alpha = 0.92f)), maxLines = 2, modifier = Modifier.width(HeroSpec.DescriptionWidth))
            Spacer(Modifier.height(13.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.ratingLabel, style = AuraTypography.Label.copy(color = AuraColors.TextPrimary), modifier = Modifier.border(1.dp, AuraColors.TextPrimary.copy(alpha = 0.7f), RoundedCornerShape(6.dp)).padding(horizontal = 9.dp, vertical = 2.dp))
                Box(Modifier.padding(horizontal = 18.dp).width(1.dp).height(18.dp).background(AuraColors.TextMuted))
                Text(e.metadata.joinToString("   •   "), style = AuraTypography.Metadata)
            }
            Spacer(Modifier.height(30.dp))
            Row(Modifier.verticalLinks(down = downTarget), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                val actions = if (onTrailer != null) e.actions.filterNot { it.icon == HeroIcon.TRAILER } else e.actions
                actions.forEachIndexed { i, a ->
                    HeroActionButton(a, Modifier.leftToRail(i == 0).then(if (a.primary) Modifier.focusRequester(primaryRequester) else Modifier), onActionFocused) { onAction(e, a) }
                }
                if (onTrailer != null) HeroActionButton(HeroAction("trailer", "Trailer", HeroIcon.TRAILER),
                    onFocused = onActionFocused) { onTrailer(e) }
            }
            Spacer(Modifier.height(17.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(19.dp), verticalAlignment = Alignment.CenterVertically) {
                entries.forEachIndexed { i, _ -> Box(Modifier.then(if (i == index) Modifier.size(23.dp, 10.dp) else Modifier.size(9.dp)).clip(CircleShape).background(if (i == index) AuraColors.TextPrimary else AuraColors.TextMuted.copy(alpha = 0.7f))) }
            }
        }
    }
}

/** Shared hero button: fixed neutral fill and muted icon, with the shared focus style. 49 high. */
@Composable
fun HeroActionButton(action: HeroAction, modifier: Modifier = Modifier, onFocused: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(AuraDimensions.RadiusButton + 2.dp)
    val c = if (action.primary) Color.White else AuraColors.ButtonInactiveIcon
    Row(
        modifier.height(49.dp).widthIn(min = if (action.primary) 170.dp else 0.dp).auraFocusable(shape, scale = 1.05f, onFocused = onFocused, onClick = onClick)
            .clip(shape).then(if (action.primary) Modifier.background(AuraColors.OrangeGradient) else Modifier.background(AuraColors.ButtonInactiveBg))
            .padding(horizontal = 26.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(action.icon.vector(), null, tint = c, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Text(action.label, style = AuraTypography.Button, color = c, maxLines = 1, softWrap = false)
    }
}
