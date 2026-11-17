package no.aura.os.v2.brand

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import no.aura.os.v2.theme.AuraColors
import no.aura.os.v2.theme.AuraTypography

/**
 * The single shared AURA section-label component: narrow vertical orange line, white primary text, orange accent text, bold sans-serif, compact.
 * No glow, no animation. Used for every branded hero label (replaces the old blue underlined label).
 */
@Composable
fun AuraBrandLabel(spec: BrandLabelSpec, modifier: Modifier = Modifier) {
    Row(modifier.height(BrandLabelMetrics.Height), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(BrandLabelMetrics.BarWidth).height(BrandLabelMetrics.BarHeight).background(AuraColors.BrandOrange))
        Spacer(Modifier.width(BrandLabelMetrics.BarGap))
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = AuraColors.TextPrimary)) { append(spec.primary) }
                append(" ")
                withStyle(SpanStyle(color = AuraColors.BrandOrange)) { append(spec.accent) }
            },
            style = AuraTypography.BrandLabel, maxLines = 1, softWrap = false,
        )
    }
}

object BrandLabelMetrics {
    val Height = 34.dp
    val BarWidth = 4.dp
    val BarHeight = 28.dp
    val BarGap = 12.dp
}

/**
 * Official AURAOS wordmark (white AURA, orange OS., transparent). Uses the approved bitmap `auraos_wordmark`; if that resource is absent
 * (e.g. a harness without resources) a faithful text fallback is drawn instead. The fallback is a development aid, not the brand asset.
 */
@Composable
fun AuraWordmark(modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = WordmarkMetrics.NavHeight) {
    val ctx = LocalContext.current
    val id = remember { ctx.resources.getIdentifier("auraos_wordmark", "drawable", ctx.packageName) }
    if (id != 0) {
        Image(painterResource(id), "AURAOS", modifier.height(height).width(height * WordmarkMetrics.AspectRatio), contentScale = ContentScale.Fit)
    } else {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = AuraColors.TextPrimary, fontWeight = FontWeight.ExtraBold)) { append("AURA") }
                withStyle(SpanStyle(color = AuraColors.BrandOrange, fontWeight = FontWeight.ExtraBold)) { append("OS.") }
            },
            style = AuraTypography.Brand.copy(fontWeight = FontWeight.ExtraBold), modifier = modifier, maxLines = 1, softWrap = false,
        )
    }
}

object WordmarkMetrics {
    /** Bitmap is 720x126. */
    const val AspectRatio = 720f / 126f
    val NavHeight = 28.dp
}
