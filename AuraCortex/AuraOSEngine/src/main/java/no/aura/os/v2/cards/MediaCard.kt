package no.aura.os.v2.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.aura.os.v2.data.ContinueItem
import no.aura.os.v2.data.MediaItem
import no.aura.os.v2.demo.ArtworkImage
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.AuraDimensions
import no.aura.os.v2.theme.AuraTypography

/** Card geometry measured on the approved Home mockup, in 1920x1080 canvas units. Both variants share the same ~2.24:1 aspect. */
object CardSpec {
    val ContinueWidth = 298.dp; val ContinueHeight = 168.dp
    val CompactWidth = 238.dp; val CompactHeight = 106.dp
    val Gap = 12.dp
    val Radius = AuraDimensions.RadiusCard
    val Home = CardSizes(DpSize(ContinueWidth, ContinueHeight), DpSize(CompactWidth, CompactHeight))
}

/** Card sizes of one page (Home, Movies and TV Shows differ slightly in the mockups). */
data class CardSizes(val continueSize: DpSize, val compactSize: DpSize, val continueGap: Dp = 12.dp, val compactGap: Dp = 12.dp)

/** "The Silver Realm" -> ("The", "Silver Realm"); otherwise (null, title). */
fun splitPrefix(title: String): Pair<String?, String> =
    if (title.startsWith("The ", ignoreCase = true) && title.length > 4) title.substring(0, 3) to title.substring(4) else null to title

/** Letter-spaced serif title block used inside cards: optional small "THE" above, remainder in caps, optionally wrapped over two lines. */
@Composable
private fun CardTitleBlock(title: String, twoLine: Boolean, modifier: Modifier = Modifier) {
    val (prefix, rest) = splitPrefix(title)
    Column(modifier) {
        if (prefix != null) Text(prefix.uppercase(), style = AuraTypography.ArtTitlePrefix, maxLines = 1)
        Text(
            if (twoLine) rest.uppercase().replace(' ', '\n') else rest.uppercase(),
            style = AuraTypography.ArtTitle.copy(lineHeight = 22.sp), maxLines = 2, overflow = TextOverflow.Clip,
        )
    }
}

/**
 * Diagnostics only (no-ops unless Diag is enabled): `card.compose` = cards composed for the first time, `card.recompose` = every execution of a
 * card body including the first. recompose - compose = how often existing cards were re-executed (focus changes, parent recompositions).
 */
@Composable
private fun CardDiag() {
    androidx.compose.runtime.remember { no.aura.os.v2.diag.Diag.count("card.compose") }
    androidx.compose.runtime.SideEffect { no.aura.os.v2.diag.Diag.count("card.recompose") }
}

private val CardShape = RoundedCornerShape(CardSpec.Radius)
private val CardBorder = AuraColors.Border.copy(alpha = 0.75f)

/** Continue Watching card: artwork, letter-spaced title, thin blue progress track and "42m left" along the bottom edge. */
@Composable
fun ContinueCard(item: ContinueItem, modifier: Modifier = Modifier, cardSize: DpSize = CardSpec.Home.continueSize, onClick: () -> Unit) {
    CardDiag()
    Box(
        modifier.size(cardSize).auraFocusable(CardShape, onClick = onClick)
            .clip(CardShape).background(AuraColors.Surface).border(1.dp, CardBorder, CardShape).auraGlass(CardShape),
    ) {
        ArtworkImage(item.item.artwork)
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.30f), 0.7f to Color.Transparent)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.62f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
        Text(item.item.title, style = AuraTypography.CardTitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 18.dp, end = 12.dp, bottom = 42.dp))
        Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 18.dp, end = 14.dp, bottom = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.showProgress) Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).drawBehind {
                drawRect(Color.White.copy(alpha = 0.30f))
                val filled = size.width * item.progress.coerceIn(0f, 1f)
                drawRect(AuraColors.Focus.copy(alpha = 0.24f), Offset(0f, -2f), Size(filled, size.height + 4f))
                drawRect(brush = Brush.horizontalGradient(listOf(AuraColors.Focus.copy(alpha = .55f), AuraColors.Focus)), topLeft = Offset.Zero, size = Size(filled, size.height))
            })
            if (item.remainingLabel.isNotEmpty()) Text(item.remainingLabel, style = AuraTypography.Label.copy(color = AuraColors.TextPrimary), maxLines = 1, modifier = Modifier.padding(start = 14.dp))
        }
    }
}

/** Compact movie/series card (Aura Latest, Trending, and later Movies/TV Shows shelves). */
@Composable
fun CompactCard(item: MediaItem, modifier: Modifier = Modifier, cardSize: DpSize = CardSpec.Home.compactSize, onClick: () -> Unit) {
    CardDiag()
    Box(
        modifier.size(cardSize).auraFocusable(CardShape, onClick = onClick)
            .clip(CardShape).background(AuraColors.Surface).border(1.dp, CardBorder, CardShape).auraGlass(CardShape),
    ) {
        ArtworkImage(item.artwork)
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.30f), 0.7f to Color.Transparent)))
        CardTitleBlock(item.title, item.twoLine, Modifier.align(Alignment.CenterStart).padding(start = 18.dp))
    }
}

/** Static poster geometry: owner-approved 35% reduction, exact 2:3 and no reserved expansion slot. */
object PosterGeometry {
    const val WIDTH = 184f
    const val HEIGHT = 276f
    const val GAP = 12f
}

object PosterSpec {
    val Width = PosterGeometry.WIDTH.dp
    val Height = PosterGeometry.HEIGHT.dp
    val Gap = PosterGeometry.GAP.dp
}

/** Artwork remains a poster at rest and on focus. The shared outline supplies focus feedback. */
@Composable
fun PosterCard(item: MediaItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    CardDiag()
    Box(
        modifier.size(PosterSpec.Width, PosterSpec.Height)
            .auraFocusable(CardShape, scale = 1f, elevation = 0.dp, onClick = onClick)
            .clip(CardShape).background(AuraColors.Surface).border(1.dp, CardBorder, CardShape).auraGlass(CardShape),
    ) {
        ArtworkImage(item.artwork, Modifier.fillMaxSize())
        if (item.artwork.imageUrl.isNullOrBlank()) {
            Text(item.title, style = AuraTypography.CardTitle, maxLines = 3,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(14.dp))
        }
    }
}
